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
   * @param {HTMLElement} element #chart-fulfilment
   * @param {WorkOrderFulfilmentDTO} data payload from /work-orders/fulfilment
   * @param {number} slaHours service target, used to tint compliant departments
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

    chart.setOption({
      backgroundColor: 'transparent',
      animationDuration: 420,
      grid: { left: 8, right: 54, top: 12, bottom: 6, containLabel: true },
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
          return `
            <div style="font-family:Consolas,monospace;font-size:12px">
              <div style="color:#22d3ee;font-weight:700;margin-bottom:5px">${row.department}</div>
              <div>Tempo médio <b>${row.averageFulfilmentHours}h</b></div>
              <div>Ordens concluídas <b>${row.completedCount}</b></div>
              <div>Em aberto <b>${row.backlogCount}</b></div>
              <div>Dentro do SLA <b>${row.slaCompliancePercent}%</b></div>
            </div>`;
        },
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
            // Green inside the target, amber approaching it, red beyond.
            color: Number(r.averageFulfilmentHours) <= target ? '#2ecc71' : '#ef4a4a',
            borderRadius: [0, 3, 3, 0],
          },
        })),
        barMaxWidth: 18,
        label: {
          show: true,
          position: 'right',
          formatter: (p) => `${p.value}h`,
          color: TEXT,
          fontSize: 10.5,
          fontFamily: 'Consolas, monospace',
        },
        markLine: {
          silent: true,
          symbol: 'none',
          label: {
            formatter: `SLA ${target}h`,
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