/* =====================================================================
   Warehouse Control Tower - dashboard orchestration
   Polls the API, renders KPIs, charts and tables, and drives the
   telemetry simulation controls.

   Note on language: identifiers, API field names and routes are English
   (matching the backend), while every operator-visible string stays in
   Portuguese, since this dashboard is presented to the shop floor team.
   ===================================================================== */

(() => {
  'use strict';

  const DEFAULT_INTERVAL_MS = 5000;
  const MAX_ALERT_ROWS = 40;

  /* Previous values, so only changed figures flash */
  const previous = new Map();

  let refreshTimer = null;
  let simulationActive = true;
  let currentIntervalMs = DEFAULT_INTERVAL_MS;

  /* ------------------------------------------------------------------
     Analysis filters
     Applied client-side: the catalogue is small, so filtering locally is
     instant and avoids a round trip on every keystroke.
     ------------------------------------------------------------------ */
  const filters = {
    abcClass: 'ALL',
    severity: '',
    search: '',
    topN: 0,
  };

  /* Latest payloads, kept so filters can be re-applied without refetching */
  let latestCurve = null;
  let latestAlerts = [];
  let latestItems = [];

  // -------------------------------------------------------------------
  // Formatting helpers (pt-BR)
  // -------------------------------------------------------------------
  const formatCurrency = (v) => new Intl.NumberFormat('pt-BR', {
    style: 'currency', currency: 'BRL', maximumFractionDigits: 2,
  }).format(Number(v || 0));

  const formatCurrencyCompact = (v) => {
    const n = Number(v || 0);
    if (Math.abs(n) >= 1_000_000) return `R$ ${(n / 1_000_000).toFixed(2)}M`;
    if (Math.abs(n) >= 1_000) return `R$ ${(n / 1_000).toFixed(1)}k`;
    return formatCurrency(n);
  };

  const formatNumber = (v, decimals = 0) => new Intl.NumberFormat('pt-BR', {
    minimumFractionDigits: decimals, maximumFractionDigits: decimals,
  }).format(Number(v || 0));

  const formatDateTime = (iso) => {
    if (!iso) return '—';
    const d = new Date(iso);
    return Number.isNaN(d.getTime())
      ? '—'
      : d.toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'medium' });
  };

  /** Escapes text coming from the database before injecting it as HTML. */
  function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>"']/g, (c) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
    }[c]));
  }

  const $ = (id) => document.getElementById(id);

  /** Updates a KPI value, flashing it when the value changes. */
  function setKpi(id, value, options = {}) {
    const el = $(id);
    if (!el) return;
    const text = options.format ? options.format(value) : String(value ?? '—');

    if (previous.get(id) !== text) {
      el.textContent = text;
      if (previous.has(id)) {
        el.classList.remove('is-flashing');
        // Force reflow so the animation restarts
        void el.offsetWidth;
        el.classList.add('is-flashing');
      }
      previous.set(id, text);
    }
    if (options.meta !== undefined) {
      const metaEl = $(options.metaId);
      if (metaEl) metaEl.textContent = options.meta;
    }
  }

  /** Ephemeral notification in the corner. */
  let toastTimer = null;
  function toast(message, variant = '') {
    const el = $('toast');
    if (!el) return;
    el.textContent = message;
    el.className = `toast is-visible ${variant ? `toast--${variant}` : ''}`;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove('is-visible'), 4200);
  }

  /** Connection state in the header. */
  function setConnection(state, label) {
    const chip = $('connection-chip');
    const text = $('connection-label');
    if (!chip || !text) return;
    chip.className = 'status-chip' + (state ? ` ${state}` : '');
    text.textContent = label;
  }

  // ===================================================================
  // KPI rendering
  // ===================================================================
  function renderKpis(kpis) {
    setKpi('kpi-valor', kpis.totalInventoryValue, {
      format: formatCurrencyCompact,
      meta: formatCurrency(kpis.totalInventoryValue),
      metaId: 'kpi-valor-meta',
    });

    setKpi('kpi-insumos', kpis.totalSupplyItems, {
      format: formatNumber,
      meta: `${kpis.classAItems}A · ${kpis.classBItems}B · ${kpis.classCItems}C`,
      metaId: 'kpi-insumos-meta',
    });

    setKpi('kpi-ruptura', kpis.stockoutItems, { format: formatNumber });
    setKpi('kpi-critico', kpis.criticalItems, { format: formatNumber });
    setKpi('kpi-atencao', kpis.warningItems, { format: formatNumber });

    setKpi('kpi-classe-a', kpis.classAValuePercentage, {
      format: (v) => `${formatNumber(v, 1)}%`,
      meta: `${kpis.classAItems} itens concentram 80% do valor`,
      metaId: 'kpi-classe-a-meta',
    });

    setKpi('kpi-cobertura', kpis.averageCoverageDays, {
      format: (v) => `${formatNumber(v, 0)}d`,
      meta: `consumo ${formatNumber(kpis.averageDailyConsumption, 1)} un/dia`,
      metaId: 'kpi-consumo-meta',
    });

    setKpi('kpi-ordens', (kpis.openWorkOrders || 0) + (kpis.inProgressWorkOrders || 0), {
      format: formatNumber,
      meta: `${kpis.openWorkOrders || 0} abertas · ${kpis.inProgressWorkOrders || 0} em andamento`,
      metaId: 'kpi-ordens-meta',
    });

    // Giro de estoque
    setKpi('kpi-giro', kpis.inventoryTurnover, {
      format: (v) => `${formatNumber(v, 2)}x`,
      meta: `COGS ${formatCurrencyCompact(kpis.cogsLast30Days)} / estoque médio`,
      metaId: 'kpi-giro-meta',
    });

    // Tempo médio de atendimento
    setKpi('kpi-atendimento', kpis.averageFulfilmentHours, {
      format: (v) => `${formatNumber(v, 1)}h`,
      meta: `${formatNumber(kpis.workOrdersCompletedPercent, 1)}% dentro do SLA`,
      metaId: 'kpi-atendimento-meta',
    });

    setKpi('stat-movs24h', kpis.movementsLast24h, { format: formatNumber });

    GaugeChart.renderGauge(
      $('gauge-health'),
      kpis.inventoryHealthIndex,
      kpis.stockoutItems,
      kpis.criticalItems,
    );

    $('footer-updated').textContent =
      `Última atualização: ${formatDateTime(kpis.computedAt)}`;
  }

  // ===================================================================
  // ABC curve rendering (filter-aware)
  // ===================================================================
  function renderAbcCurve(curve) {
    if (!curve) return;
    latestCurve = curve;
    renderFilteredCurve();
  }

  /**
   * Applies the active filters to the ABC curve and redraws it.
   *
   * <p>Recomputing the cumulative percentage over the filtered subset is
   * deliberate: filtering to class A and still showing 0-100% cumulative
   * would misrepresent the curve, so the share is rebuilt against the visible
   * items only.</p>
   */
  function renderFilteredCurve() {
    const curve = latestCurve;
    if (!curve) return;

    let items = curve.items || [];

    if (filters.abcClass !== 'ALL') {
      items = items.filter((i) => i.abcClass === filters.abcClass);
    }
    if (filters.search) {
      const term = filters.search.toLowerCase();
      items = items.filter((i) =>
        String(i.code).toLowerCase().includes(term)
        || String(i.name).toLowerCase().includes(term));
    }

    // Top N is applied first, so the cumulative line always ends at 100% of
    // the bars actually on screen. Slicing afterwards would leave the last
    // bar short of 100%, which misreads as "there is more value off-chart".
    // The value ordering is already guaranteed by the API.
    const visible = filters.topN > 0 ? items.slice(0, filters.topN) : items;

    const subsetTotal = visible.reduce(
      (sum, i) => sum + Number(i.inventoryValue || 0), 0);

    // Cumulative share recomputed over the visible subset.
    let running = 0;
    const withCumulative = visible.map((i) => {
      running += Number(i.inventoryValue || 0);
      return {
        ...i,
        cumulativePercentage: subsetTotal > 0
          ? Number(((running / subsetTotal) * 100).toFixed(2))
          : 0,
        // Flag items with no stock on hand: they contribute no value, so the
        // bar would render with zero height and look like a rendering glitch.
        noStock: Number(i.inventoryValue || 0) === 0,
      };
    });

    const zeroValueCount = withCumulative.filter((i) => i.noStock).length;
    if (zeroValueCount > 0) {
      $('pareto-badge').title =
        `${zeroValueCount} item(ns) sem saldo em mãos: contribute R$ 0 para a valorização.`;
    } else {
      $('pareto-badge').removeAttribute('title');
    }

    ParetoChart.render($('pareto-chart'), {
      ...curve,
      items: withCumulative,
      totalValue: subsetTotal,
    });

    const summary = curve.summaryByCategory || {};
    $('legend-a').textContent = `${summary.classAItems ?? 0} itens · ${formatNumber(summary.classAPercentage, 1)}%`;
    $('legend-b').textContent = `${summary.classBItems ?? 0} itens · ${formatNumber(summary.classBPercentage, 1)}%`;
    $('legend-c').textContent = `${summary.classCItems ?? 0} itens · ${formatNumber(summary.classCPercentage, 1)}%`;

    $('pareto-badge').textContent =
      `${visible.length} de ${curve.items?.length ?? 0} itens · ${formatCurrencyCompact(subsetTotal)}`;

    GaugeChart.renderDonut($('chart-abc-donut'), summary);

    updateFilterResult();
  }

  function updateFilterResult() {
    const total = latestCurve?.items?.length ?? 0;
    const visible = countVisible();
    const active = [];
    if (filters.abcClass !== 'ALL') active.push(`classe ${filters.abcClass}`);
    if (filters.severity) active.push(severityLabel(filters.severity).toLowerCase());
    if (filters.search) active.push(`"${filters.search}"`);
    if (filters.topN > 0) active.push(`top ${filters.topN}`);

    // "Exibindo N de M" is explicit about which number is on screen.
    // Previously this showed the hidden count, which read as the visible one
    // and contradicted the Pareto badge.
    $('filter-result').textContent = active.length
      ? `Exibindo ${visible} de ${total} · ${active.join(' + ')}`
      : `Exibindo ${total} de ${total} · sem filtros`;
  }

  function countVisible() {
    if (!latestCurve) return 0;
    let items = latestCurve.items || [];
    if (filters.abcClass !== 'ALL') items = items.filter((i) => i.abcClass === filters.abcClass);
    if (filters.search) {
      const term = filters.search.toLowerCase();
      items = items.filter((i) =>
        String(i.code).toLowerCase().includes(term)
        || String(i.name).toLowerCase().includes(term));
    }
    if (filters.topN > 0) items = items.slice(0, filters.topN);
    return items.length;
  }

  // ===================================================================
  // Stockout alerts (filter-aware)
  // ===================================================================
  function renderAlerts(alerts) {
    latestAlerts = alerts || [];
    renderFilteredAlerts();
  }

  function renderFilteredAlerts() {
    const tbody = $('alertas-body');
    if (!tbody) return;

    let alerts = latestAlerts;

    if (filters.severity) {
      alerts = alerts.filter((a) => a.severity === filters.severity);
    }
    if (filters.search) {
      const term = filters.search.toLowerCase();
      alerts = alerts.filter((a) =>
        String(a.code).toLowerCase().includes(term)
        || String(a.name).toLowerCase().includes(term));
    }

    if (!alerts || alerts.length === 0) {
      tbody.innerHTML = `
        <tr><td colspan="9">
          <div class="state">
            <span class="state__icon">✅</span>
            <span>Nenhum item em risco de ruptura.</span>
          </div>
        </td></tr>`;
      $('alertas-badge').textContent = '0 itens';
      return;
    }

    $('alertas-badge').textContent = `${alerts.length} itens em risco`;

    // Coverage is normalised against the worst case in the list so the bars
    // stay comparable between rows.
    const coverages = alerts
      .map((a) => Number(a.daysUntilStockout))
      .filter((d) => Number.isFinite(d));
    const maxCoverage = coverages.length ? Math.max(...coverages) : 1;

    tbody.innerHTML = alerts.slice(0, MAX_ALERT_ROWS).map((a) => {
      const coverage = Number.isFinite(Number(a.daysUntilStockout))
        ? Number(a.daysUntilStockout)
        : null;
      const percent = coverage !== null && maxCoverage > 0
        ? Math.max(2, Math.min(100, (coverage / maxCoverage) * 100))
        : 0;
      const barClass = coverage === null ? ''
        : coverage <= 7 ? 'coverage-bar__fill--danger'
        : coverage <= 21 ? 'coverage-bar__fill--warn'
        : 'coverage-bar__fill--ok';

      return `
        <tr>
          <td class="code">${escapeHtml(a.code)}</td>
          <td title="${escapeHtml(a.name)}">${escapeHtml(truncate(a.name, 34))}</td>
          <td class="num">${formatNumber(a.currentQuantity)}</td>
          <td class="num">${formatNumber(a.reorderPoint, 1)}</td>
          <td class="num">${coverage !== null ? `${formatNumber(coverage, 1)}d` : '—'}</td>
          <td><div class="coverage-bar">
            <div class="coverage-bar__fill ${barClass}" style="width:${percent}%"></div>
          </div></td>
          <td class="num">${formatNumber(a.quantityToRequest)}</td>
          <td class="num">${formatCurrency(a.valueAtRisk)}</td>
          <td><span class="tag tag--${escapeHtml(a.severity.toLowerCase())}">${escapeHtml(severityLabel(a.severity))}</span></td>
        </tr>`;
    }).join('');

    if (alerts.length > MAX_ALERT_ROWS) {
      tbody.insertAdjacentHTML('beforeend', `
        <tr><td colspan="9" style="text-align:center;color:var(--text-muted)">
          + ${alerts.length - MAX_ALERT_ROWS} itens não exibidos
        </td></tr>`);
    }
  }

  /** Maps the API severity enum to its pt-BR label. */
  function severityLabel(severity) {
    return {
      STOCKOUT: 'RUPTURA',
      CRITICAL: 'CRÍTICO',
      WARNING: 'ATENÇÃO',
      HEALTHY: 'SAUDÁVEL',
    }[severity] || severity;
  }

  function truncate(text, limit) {
    const t = String(text ?? '');
    return t.length > limit ? `${t.slice(0, limit - 1)}…` : t;
  }

  // ===================================================================
  // Reorder point table (filter-aware)
  // ===================================================================
  function renderCatalogue(items) {
    latestItems = items || [];
    renderFilteredCatalogue();
  }

  function renderFilteredCatalogue() {
    const tbody = $('rp-body');
    if (!tbody) return;

    let items = latestItems;

    if (filters.abcClass !== 'ALL') {
      items = items.filter((i) => i.abcCategory === filters.abcClass);
    }
    if (filters.search) {
      const term = filters.search.toLowerCase();
      items = items.filter((i) =>
        String(i.code).toLowerCase().includes(term)
        || String(i.name).toLowerCase().includes(term));
    }

    if (!items || items.length === 0) {
      tbody.innerHTML = `
        <tr><td colspan="7">
          <div class="state">
            <span class="state__icon">📭</span><span>Catálogo vazio.</span>
          </div>
        </td></tr>`;
      $('rp-badge').textContent = '0 itens';
      return;
    }

    $('rp-badge').textContent = `${items.length} insumos ativos`;

    const SAFETY_FACTOR = 0.5;
    const WARNING_MULTIPLIER = 1.25;
    const computed = items.map((i) => {
      const consumption = Number(i.averageDailyConsumption || 0);
      const leadTime = Number(i.replenishmentLeadTimeDays || 0);
      const balance = Number(i.currentQuantity || 0);
      const minimum = Number(i.minimumStock || 0);
      const reorderPoint = consumption * leadTime * (1 + SAFETY_FACTOR);
      let status = 'healthy';
      if (balance <= 0) status = 'stockout';
      else if (consumption > 0 && balance < reorderPoint) status = 'critical';
      else if (balance < reorderPoint * WARNING_MULTIPLIER) status = 'warning';
      return { ...i, reorderPoint, status };
    });

    // Most critical first: stockout, then critical, then warning.
    const ORDER = { stockout: 0, critical: 1, warning: 2, healthy: 3 };
    computed.sort((a, b) =>
      ORDER[a.status] - ORDER[b.status] || a.currentQuantity - b.currentQuantity);

    tbody.innerHTML = computed.map((i) => `
      <tr>
        <td class="code">${escapeHtml(i.code)}</td>
        <td><span class="tag tag--${escapeHtml((i.abcCategory || 'c').toLowerCase())}">${escapeHtml(i.abcCategory || 'C')}</span></td>
        <td class="num">${formatNumber(i.currentQuantity)}</td>
        <td class="num">${formatNumber(i.minimumStock)}</td>
        <td class="num">${formatNumber(i.replenishmentLeadTimeDays)}d</td>
        <td class="num">${Number(i.averageDailyConsumption) > 0 ? formatNumber(i.reorderPoint, 1) : '—'}</td>
        <td><span class="tag tag--${i.status}">${severityLabel(i.status.toUpperCase())}</span></td>
      </tr>`).join('');
  }

  // ===================================================================
  // Supply-chain panels: turnover, fulfilment, suppliers
  // ===================================================================
  function renderTurnover(data) {
    if (!data) return;

    SupplyChainChart.renderTurnover($('chart-turnover'), data);

    $('turnover-badge').textContent = `janela de ${data.windowDays} dias`;
    $('turnover-cogs').textContent = formatCurrency(data.cogs);
    $('turnover-avg').textContent = formatCurrency(data.averageInventory);
    $('turnover-annual').textContent = `${formatNumber(data.annualisedTurnover, 1)}x`;
    $('turnover-dio').textContent = `${formatNumber(data.daysOfInventory, 1)} dias`;
  }

  function renderFulfilment(data) {
    if (!data) return;

    SupplyChainChart.renderFulfilment($('chart-fulfilment'), data, data.slaTargetHours);

    $('fulfilment-badge').textContent = `${data.completedCount} ordens concluídas`;
    $('fulfilment-median').textContent = `${formatNumber(data.medianFulfilmentHours, 1)}h`;
    $('fulfilment-min').textContent = `${formatNumber(data.minFulfilmentHours, 1)}h`;
    $('fulfilment-max').textContent = `${formatNumber(data.maxFulfilmentHours, 1)}h`;
    $('fulfilment-sla').textContent = `${formatNumber(data.slaCompliancePercent, 1)}%`;
    $('fulfilment-sla-target').textContent = formatNumber(data.slaTargetHours, 0);
  }

  function renderSuppliers(data) {
    const tbody = $('suppliers-body');
    if (!tbody) return;

    const suppliers = (data && data.suppliers) || [];
    if (suppliers.length === 0) {
      tbody.innerHTML = `
        <tr><td colspan="7">
          <div class="state"><span class="state__icon">📭</span><span>Sem fornecedores.</span></div>
        </td></tr>`;
      $('suppliers-badge').textContent = '0 fornecedores';
      return;
    }

    $('suppliers-badge').textContent = `${suppliers.length} fornecedores`;

    tbody.innerHTML = suppliers.map((s) => `
      <tr>
        <td title="${escapeHtml(s.supplier)}">${escapeHtml(truncate(s.supplier, 26))}</td>
        <td class="num">${formatNumber(s.itemCount)}</td>
        <td class="num">${formatCurrencyCompact(s.totalValue)}</td>
        <td class="num">${formatNumber(s.valueSharePercent, 1)}%</td>
        <td class="num">${formatNumber(s.averageLeadTimeDays, 0)}d</td>
        <td class="num">${formatNumber(s.itemsAtRisk)}</td>
        <td><span class="tag tag--${riskClass(s.riskLevel)}">${riskLabel(s.riskLevel)}</span></td>
      </tr>`).join('');
  }

  function riskClass(level) {
    return { HIGH: 'stockout', MEDIUM: 'warning', LOW: 'healthy' }[level] || 'healthy';
  }

  function riskLabel(level) {
    return { HIGH: 'ALTO', MEDIUM: 'MÉDIO', LOW: 'BAIXO' }[level] || '—';
  }

  // ===================================================================
  // Simulation
  // ===================================================================
  function renderSimulationStats(stats) {
    if (!stats) return;
    simulationActive = stats.active;
    currentIntervalMs = Number(stats.intervalMs || DEFAULT_INTERVAL_MS);

    $('btn-toggle-sim').textContent = stats.active ? 'Pausar' : 'Retomar';
    $('btn-toggle-sim').className = `btn ${stats.active ? 'btn--danger' : 'btn--primary'}`;

    $('stat-ciclos').textContent = formatNumber(stats.cyclesExecuted);
    $('stat-ordens').textContent = formatNumber(stats.workOrdersGenerated);
    $('stat-movs').textContent = formatNumber(stats.movementsGenerated);
    $('stat-reposicoes').textContent = formatNumber(stats.replenishmentsGenerated);
  }

  async function sendParameters(payload) {
    try {
      const params = await API.updateSimulationParameters(payload);
      renderSimulationStats(await API.simulationParameters());
      syncSliders(params);
      toast('Parâmetros de telemetria atualizados.', 'success');
    } catch (error) {
      toast(error.message || 'Falha ao atualizar parâmetros.', 'error');
      syncSliders({ active: simulationActive, intervalMs: currentIntervalMs });
    }
  }

  function syncSliders(params) {
    if (!params) return;
    if (params.intensity != null) {
      $('intensity-slider').value = params.intensity;
      $('intensity-value').textContent = `${Number(params.intensity).toFixed(1)}x`;
    }
    if (params.intervalMs != null) {
      $('interval-slider').value = params.intervalMs;
      $('interval-value').textContent = `${params.intervalMs}ms`;
      $('refresh-label').textContent = `${Math.round(params.intervalMs / 1000)}s`;
      currentIntervalMs = Number(params.intervalMs);
    }
  }

  function registerControls() {
    $('btn-toggle-sim').addEventListener('click', () => {
      sendParameters({ active: !simulationActive });
    });

    // Debounce: avoid spamming the POST while the slider is being dragged.
    let debounce = null;
    const onInput = (event, build) => {
      clearTimeout(debounce);
      debounce = setTimeout(() => build(event), 350);
    };

    $('intensity-slider').addEventListener('input', (e) => {
      $('intensity-value').textContent = `${Number(e.target.value).toFixed(1)}x`;
      onInput(e, (ev) => sendParameters({ intensity: Number(ev.target.value) }));
    });

    $('interval-slider').addEventListener('input', (e) => {
      const ms = Number(e.target.value);
      $('interval-value').textContent = `${ms}ms`;
      onInput(e, (ev) => sendParameters({ intervalMs: Number(ev.target.value) }));
    });

    registerFilters();
  }

  /** Wires the analysis filters and re-renders every dependent view. */
  function registerFilters() {
    const applyAll = () => {
      renderFilteredCurve();
      renderFilteredAlerts();
      renderFilteredCatalogue();
    };

    // ABC segmented control
    document.querySelectorAll('.segmented__btn').forEach((btn) => {
      btn.addEventListener('click', () => {
        document.querySelectorAll('.segmented__btn').forEach((b) => b.classList.remove('is-active'));
        btn.classList.add('is-active');
        filters.abcClass = btn.dataset.abc;
        applyAll();
      });
    });

    $('filter-severity').addEventListener('change', (e) => {
      filters.severity = e.target.value;
      applyAll();
    });

    $('filter-top').addEventListener('change', (e) => {
      filters.topN = Number(e.target.value) || 0;
      renderFilteredCurve();
      updateFilterResult();
    });

    // Debounced search: filtering on every keystroke is wasteful on long terms.
    let searchTimer = null;
    $('filter-search').addEventListener('input', (e) => {
      clearTimeout(searchTimer);
      const value = e.target.value.trim();
      searchTimer = setTimeout(() => {
        filters.search = value;
        applyAll();
      }, 220);
    });

    $('btn-clear-filters').addEventListener('click', () => {
      filters.abcClass = 'ALL';
      filters.severity = '';
      filters.search = '';
      filters.topN = 0;

      document.querySelectorAll('.segmented__btn').forEach((b) => {
        b.classList.toggle('is-active', b.dataset.abc === 'ALL');
      });
      $('filter-severity').value = '';
      $('filter-search').value = '';
      $('filter-top').value = '0';
      applyAll();
    });
  }

  // ===================================================================
  // Refresh cycle
  // ===================================================================
  async function refreshAll() {
    try {
      // Everything loads in parallel: the dashboard fills in from one round trip.
      const [kpis, curve, alerts, items, turnover, fulfilment, suppliers, stats] =
        await Promise.all([
          API.kpis(),
          API.abcCurve(),
          API.stockoutAlerts(),
          API.supplyItems(),
          API.turnover().catch(() => null),
          API.workOrderFulfilment().catch(() => null),
          API.supplierPerformance().catch(() => null),
          API.simulationParameters().catch(() => null),
        ]);

      renderKpis(kpis);
      renderAbcCurve(curve);
      renderAlerts(alerts);
      renderCatalogue(items);
      renderTurnover(turnover);
      renderFulfilment(fulfilment);
      renderSuppliers(suppliers);
      if (stats) renderSimulationStats(stats);

      setConnection('', simulationActive ? 'Telemetria ao vivo' : 'Telemetria pausada');
    } catch (error) {
      setConnection('status-chip--offline', 'API indisponível');
      const body = $('alertas-body');
      if (body && !body.children.length) {
        body.innerHTML = `
          <tr><td colspan="9">
            <div class="state">
              <span class="state__icon">⚠️</span>
              <span>${escapeHtml(error.message || 'Erro ao consultar a API.')}</span>
            </div>
          </td></tr>`;
      }
    }
  }

  function scheduleRefresh() {
    clearTimeout(refreshTimer);
    const interval = Math.max(1000, currentIntervalMs);
    refreshTimer = setTimeout(async () => {
      await refreshAll();
      scheduleRefresh();
    }, interval);
  }

  function startClock() {
    const el = $('clock');
    const tick = () => {
      el.textContent = new Date().toLocaleTimeString('pt-BR');
    };
    tick();
    setInterval(tick, 1000);
  }

  // ===================================================================
  // Responsive chart sizing
  // ===================================================================

  /**
   * Keeps every chart matched to its container.
   *
   * <p>A window resize listener is not enough here. Browser zoom narrows the
   * viewport without necessarily changing window dimensions in a way the
   * debounced handler can catch in time, and a chart that grew to fill its
   * panel (see .panel--fill) also changes size without a window resize. A
   * ResizeObserver reacts to the element's own box, which covers zoom, panel
   * reflow and font loading alike.</p>
   */
  function observeChartResize() {
    if (typeof ResizeObserver === 'undefined') return;

    // ResizeObserver fires per frame while a chart is resizing, which is
    // wasteful; coalesce to one call per frame.
    const pending = new Set();
    let frame = null;

    const flush = () => {
      frame = null;
      const ids = Array.from(pending);
      pending.clear();
      for (const id of ids) {
        const chart = echarts.getInstanceByDom($(id));
        if (chart && !chart.isDisposed()) chart.resize();
      }
    };

    const observer = new ResizeObserver((entries) => {
      for (const entry of entries) {
        if (entry.contentRect.width > 0 && entry.contentRect.height > 0) {
          pending.add(entry.target.id);
        }
      }
      if (pending.size && frame === null) {
        frame = requestAnimationFrame(flush);
      }
    });

    ['pareto-chart', 'gauge-health', 'chart-abc-donut',
      'chart-turnover', 'chart-fulfilment'].forEach((id) => {
      const el = $(id);
      if (el) observer.observe(el);
    });

    window.addEventListener('beforeunload', () => observer.disconnect());
  }
  document.addEventListener('DOMContentLoaded', () => {
    if (typeof echarts === 'undefined') {
      setConnection('status-chip--offline', 'ECharts não carregado');
      toast('Biblioteca de gráficos indisponível em /vendor/echarts.min.js', 'error');
    }

    startClock();
    registerControls();
    observeChartResize();

    let resizeTimer = null;
    window.addEventListener('resize', () => {
      clearTimeout(resizeTimer);
      resizeTimer = setTimeout(() => {
        ParetoChart.resize();
        GaugeChart.resize();
        SupplyChainChart.resize();
      }, 160);
    });

    refreshAll().then(scheduleRefresh);

    // Pause polling while the tab is in the background: there is no point
    // hitting the API when nobody is looking at it.
    document.addEventListener('visibilitychange', () => {
      if (!document.hidden) {
        clearTimeout(refreshTimer);
        refreshAll().then(scheduleRefresh);
      }
    });
  });
})();