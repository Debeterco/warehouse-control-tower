/* =====================================================================
   Supply-chain charts  (Apache ECharts)
   - turnoverByClass : inventory turnover per ABC class (giro de estoque)
   - fulfilmentByDept: average fulfilment time per department
   ===================================================================== */

const SupplyChainChart = (() => {
  const CLASS_COLORS = { A: '#ef4a4a', B: '#f5a524', C: '#3ba9e0' };

  const TEXT = '#9fb0c0';
  const GRID = 'rgba(36, 48, 60, 0.55)';
  const TOOLTIP_BG = 'rgba(19, 26, 34, 0.97)';
  const TOOLTIP_BORDER = '#32404f';

  let turnover = null;
  let fulfilment = null;

  /* -------------------------------------------------------------------
     Giro de estoque por classe ABC
     ------------------------------------------------------------------- */
  function mountTurnover(element) {
    if (!element || typeof echarts === 'undefined') return null;
    if (!turnover || turnover.isDisposed()) {
      turnover = echarts.init(element, null, { renderer: 'canvas' });
    }
    return turnover;
  }

  /**
   * @param {HTMLElement} element #chart-turnover
   * @param {InventoryTurnoverDTO} data payload from GET /supplies/turnover
   */
  function renderTurnover(element, data) {
    const chart = mountTurnover(element);
    if (!chart) return;

    const rows = (data && data.turnoverByClass) || [];
    if (rows.length === 0) {
      chart.clear();
      return;
    }

    chart.setOption({
      backgroundColor: 'transparent',
      animationDuration: 420,
      grid: { left: 8, right: 46, top: 28, bottom: 6, containLabel: true },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        backgroundColor: TOOLTIP_BG,
        borderColor: TOOLTIP_BORDER,
        borderWidth: 1,
        textStyle: { color: '#e6edf3', fontSize: 12 },
        formatter(params) {
          const bar = params.find((p) => p.seriesType === 'bar');
          if (!bar) return '';
          const row = rows[bar.dataIndex];
          return `
            <div style="font-family:Consolas,monospace;font-size:12px">
              <div style="color:${CLASS_COLORS[row.abcClass]};font-weight:700;margin-bottom:5px">
                Classe ${row.abcClass}
              </div>
              <div>Giro <b>${row.turnover}x</b></div>
              <div>Itens <b>${row.itemCount}</b></div>
              <div>COGS <b>${formatCurrency(row.cogs)}</b></div>
              <div>Estoque médio <b>${formatCurrency(row.averageInventory)}</b></div>
              <div>Dias de estoque <b>${row.daysOfInventory}</b></div>
              <div>Fatia do valor <b>${row.classSharePercent}%</b></div>
            </div>`;
        },
      },
      legend: {
        data: ['Giro (x)', 'Dias de estoque'],
        top: 0,
        right: 0,
        itemWidth: 14,
        itemHeight: 9,
        textStyle: { color: TEXT, fontSize: 11 },
      },
      xAxis: {
        type: 'category',
        data: rows.map((r) => `Classe ${r.abcClass}`),
        axisLine: { lineStyle: { color: '#24303c' } },
        axisTick: { show: false },
        axisLabel: { color: TEXT, fontSize: 11, fontFamily: 'Consolas, monospace' },
      },
      yAxis: [
        {
          type: 'value',
          name: 'giros',
          nameTextStyle: { color: TEXT, fontSize: 10 },
          axisLine: { show: false },
          axisTick: { show: false },
          axisLabel: { color: TEXT, fontSize: 10, fontFamily: 'Consolas, monospace' },
          splitLine: { lineStyle: { color: GRID, type: 'dashed' } },
        },
        {
          type: 'value',
          name: 'dias',
          nameTextStyle: { color: '#22d3ee', fontSize: 10 },
          axisLine: { show: false },
          axisTick: { show: false },
          axisLabel: { color: '#22d3ee', fontSize: 10, fontFamily: 'Consolas, monospace' },
          splitLine: { show: false },
        },
      ],
      series: [
        {
          name: 'Giro (x)',
          type: 'bar',
          data: rows.map((r) => ({
            value: Number(r.turnover || 0),
            itemStyle: {
              color: CLASS_COLORS[r.abcClass] || '#3ba9e0',
              borderRadius: [3, 3, 0, 0],
            },
          })),
          barMaxWidth: 48,
          label: {
            show: true,
            position: 'top',
            formatter: (p) => `${p.value}x`,
            color: TEXT,
            fontSize: 11,
            fontFamily: 'Consolas, monospace',
          },
        },
        {
          name: 'Dias de estoque',
          type: 'line',
          yAxisIndex: 1,
          data: rows.map((r) => Number(r.daysOfInventory || 0)),
          symbol: 'circle',
          symbolSize: 7,
          lineStyle: { width: 2, color: '#22d3ee' },
          itemStyle: { color: '#22d3ee' },
        },
      ],
    }, true);
  }

  /* -------------------------------------------------------------------
     Tempo medio de atendimento por setor
     ------------------------------------------------------------------- */
  function mountFulfilment(element) {
    if (!element || typeof echarts === 'undefined') return null;
    if (!fulfilment || fulfilment.isDisposed()) {
      fulfilment = echarts.init(element, null, { renderer: 'canvas' });
    }
    return fulfilment;
  }

  /**
   * Horizontal bars: department names are long, so the category axis is
   * vertical and the bars read left-to-right.
   *
   * <p>Bar length is the <b>average</b> fulfilment time, but the bar colour
   * follows the <b>share of individual orders</b> inside the SLA. Colouring by
   * average alone is misleading: a department can average 34h (green) while a
   * fifth of its orders breach the 48h target, which is exactly the department
   * a supply manager needs to see. The average hides that long tail.</p>
   *
   * @param {HTMLElement} element #chart-fulfilment
   * @param {WorkOrderFulfilmentDTO} data payload from /work-orders/fulfilment
   * @param {number} slaHours service target
   */
  function renderFulfilment(element, data, slaHours) {
    const chart = mountFulfilment(element);
    if (!chart) return;

    const rows = (data && data.byDepartment) || [];
    if (rows.length === 0) {
      chart.clear();
      return;
    }

    // Slowest on top reads better than alphabetical order for a bottleneck view.
    const sorted = [...rows].sort((a, b) => Number(a.averageFulfilmentHours) - Number(b.averageFulfilmentHours));
    const target = Number(slaHours || 48);

    // Colour by SLA compliance, with a fallback for missing values.
    const complianceColor = (pct) => {
      if (pct === null || pct === undefined || Number.isNaN(pct)) return '#6b7d8f';
      if (pct >= 90) return '#2ecc71';
      if (pct >= 80) return '#f5a524';
      return '#ef4a4a';
    };

    const breaching = (row) => {
      const total = Number(row.completedCount || 0);
      const pct = Number(row.slaCompliancePercent);
      if (!total || Number.isNaN(pct)) return null;
      return Math.max(0, Math.round(total * (1 - pct / 100)));
    };

    chart.setOption({
      backgroundColor: 'transparent',
      animationDuration: 420,
      grid: { left: 8, right: 96, top: 30, bottom: 6, containLabel: true },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        backgroundColor: TOOLTIP_BG,
        borderColor: TOOLTIP_BORDER,
        borderWidth: 1,
        textStyle: { color: '#e6edf3', fontSize: 12 },
        formatter(params) {
          const bar = params.find((p) => p.seriesType === 'bar');
          if (!bar) return '';
          const row = sorted[bar.dataIndex];
          const late = breaching(row);
          return `
            <div style="font-family:Consolas,monospace;font-size:12px">
              <div style="color:#22d3ee;font-weight:700;margin-bottom:5px">${row.department}</div>
              <div>Tempo médio <b>${row.averageFulfilmentHours}h</b></div>
              <div>Ordens concluídas <b>${row.completedCount}</b></div>
              <div>Dentro do SLA (${target}h) <b>${row.slaCompliancePercent}%</b></div>
              ${late !== null
                ? `<div style="color:#ef4a4a">Acima do SLA <b>${late}</b> de ${row.completedCount}</div>`
                : ''}
              <div>Em aberto <b>${row.backlogCount}</b></div>
            </div>`;
        },
      },
      legend: {
        data: ['≥90% no SLA', '80–90%', '<80%'],
        top: 0,
        right: 0,
        itemWidth: 11,
        itemHeight: 9,
        itemGap: 12,
        textStyle: { color: TEXT, fontSize: 10 },
        data: [
          { name: '≥90% no SLA', itemStyle: { color: '#2ecc71' } },
          { name: '80–90%', itemStyle: { color: '#f5a524' } },
          { name: '<80%', itemStyle: { color: '#ef4a4a' } },
        ],
      },
      xAxis: {
        type: 'value',
        name: 'horas',
        nameTextStyle: { color: TEXT, fontSize: 10 },
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: TEXT, fontSize: 10, fontFamily: 'Consolas, monospace' },
        splitLine: { lineStyle: { color: GRID, type: 'dashed' } },
      },
      yAxis: {
        type: 'category',
        data: sorted.map((r) => r.department),
        axisLine: { lineStyle: { color: '#24303c' } },
        axisTick: { show: false },
        axisLabel: {
          color: TEXT,
          fontSize: 10.5,
          width: 130,
          overflow: 'truncate',
        },
      },
      series: [{
        name: 'Tempo médio (h)',
        type: 'bar',
        data: sorted.map((r) => ({
          value: Number(r.averageFulfilmentHours || 0),
          itemStyle: {
            color: complianceColor(Number(r.slaCompliancePercent)),
            borderRadius: [0, 3, 3, 0],
          },
        })),
        barMaxWidth: 18,
        label: {
          show: true,
          position: 'right',
          // Shows average and compliance together, so the two numbers can
          // never look contradictory: "34h · 81%" reads as average-fine,
          // compliance-poor at a glance.
          formatter: (p) => {
            const row = sorted[p.dataIndex];
            const pct = Number(row.slaCompliancePercent);
            return Number.isNaN(pct)
              ? `${p.value}h`
              : `${p.value}h · ${pct}%`;
          },
          color: TEXT,
          fontSize: 10.5,
          fontFamily: 'Consolas, monospace',
        },
        markLine: {
          silent: true,
          symbol: 'none',
          label: {
            formatter: `média meta ${target}h`,
            color: '#f5a524',
            fontSize: 9.5,
            fontFamily: 'Consolas, monospace',
            position: 'insideEndTop',
          },
          lineStyle: { color: '#f5a524', type: 'dashed', width: 1, opacity: 0.8 },
          data: [{ xAxis: target }],
        },
      }],
    }, true);
  }

  function formatCurrency(value) {
    return new Intl.NumberFormat('pt-BR', {
      style: 'currency', currency: 'BRL', maximumFractionDigits: 0,
    }).format(Number(value || 0));
  }

  function resize() {
    turnover && !turnover.isDisposed() && turnover.resize();
    fulfilment && !fulfilment.isDisposed() && fulfilment.resize();
  }

  return { renderTurnover, renderFulfilment, resize };
})();