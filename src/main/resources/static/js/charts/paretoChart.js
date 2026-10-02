/* =====================================================================
   ABC / Pareto chart  (Apache ECharts)
   Bars: inventory value per item, coloured by ABC class.
   Line: cumulative share — that is what shows the 80/20 rule.
   ===================================================================== */

const ParetoChart = (() => {
  const PALETTE = {
    A: '#ef4a4a',
    B: '#f5a524',
    C: '#3ba9e0',
    cumulative: '#22d3ee',
  };

  const TEXT = '#9fb0c0';
  const AXIS = '#24303c';
  const GRID = 'rgba(36, 48, 60, 0.55)';

  let instance = null;

  /** Creates (or reuses) the chart and keeps it sized to the container. */
  function mount(el) {
    if (!el || typeof echarts === 'undefined') {
      return null;
    }
    if (!instance || instance.isDisposed()) {
      instance = echarts.init(el, null, { renderer: 'canvas' });
    }
    return instance;
  }

  /**
   * Renders the chart.
   * @param {HTMLElement} element   element with id #pareto-chart
   * @param {AbcCurveDTO} curve     payload from GET /api/v1/supplies/abc-curve
   */
  function render(element, curve) {
    const chart = mount(element);
    if (!chart || !curve || !curve.items || curve.items.length === 0) {
      chart && chart.clear();
      return;
    }

    const items = curve.items;
    const codes = items.map((i) => i.code);
    const values = items.map((i) => Number(i.inventoryValue));
    const cumulative = items.map((i) => Number(i.cumulativePercentage || 0));
    // One item = one bar. On large catalogues the labels become unreadable,
    // so we label every Nth bar instead.
    const labelInterval = Math.ceil(items.length / 18);

    chart.setOption({
      backgroundColor: 'transparent',
      animationDuration: 420,
      animationEasing: 'cubicOut',
      grid: {
        left: 8,
        right: 8,
        top: 34,
        bottom: 8,
        containLabel: true,
      },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        backgroundColor: 'rgba(19, 26, 34, 0.97)',
        borderColor: '#32404f',
        borderWidth: 1,
        padding: 11,
        textStyle: { color: '#e6edf3', fontSize: 12 },
        formatter(params) {
          const bar = params.find((p) => p.seriesType === 'bar');
          if (!bar) return '';
          const item = items[bar.dataIndex];
          const semEstoque = Number(item.inventoryValue || 0) === 0;
          return `
            <div style="font-family:Consolas,monospace;font-size:12px">
              <div style="color:#22d3ee;font-weight:700;margin-bottom:5px">${item.code}</div>
              <div style="max-width:270px;white-space:normal;margin-bottom:7px;color:#9fb0c0">
                ${item.name}
              </div>
              <div>Valorização <b style="color:#e6edf3">${formatCurrency(item.inventoryValue)}</b></div>
              <div>Quantidade <b style="color:#e6edf3">${item.currentQuantity} un</b></div>
              <div>Custo unitário <b style="color:#e6edf3">${formatCurrency(item.unitCost)}</b></div>
              <div>Acumulado <b style="color:#22d3ee">${item.cumulativePercentage}%</b></div>
              <div>Classe <b style="color:${PALETTE[item.abcClass]}">${item.abcClass}</b></div>
              ${semEstoque
                ? '<div style="margin-top:6px;color:#ef4a4a">Sem saldo em mãos: sem valor imobilizado.</div>'
                : ''}
            </div>`;
        },
      },
      legend: {
        data: ['Valorização', '% Acumulado'],
        top: 0,
        right: 0,
        itemWidth: 14,
        itemHeight: 9,
        textStyle: { color: TEXT, fontSize: 11 },
      },
      xAxis: {
        type: 'category',
        data: codes,
        axisLine: { lineStyle: { color: AXIS } },
        axisTick: { show: false },
        axisLabel: {
          color: TEXT,
          fontSize: 10,
          fontFamily: 'Consolas, monospace',
          interval: labelInterval,
          rotate: 45,
        },
      },
      yAxis: [
        {
          type: 'value',
          name: 'R$',
          nameTextStyle: { color: TEXT, fontSize: 10, padding: [0, 0, 0, -18] },
          axisLine: { show: false },
          axisTick: { show: false },
          axisLabel: {
            color: TEXT,
            fontSize: 10,
            fontFamily: 'Consolas, monospace',
            formatter: (v) => formatCurrencyShort(v),
          },
          splitLine: { lineStyle: { color: GRID, type: 'dashed' } },
        },
        {
          type: 'value',
          name: '%',
          min: 0,
          max: 100,
          nameTextStyle: { color: PALETTE.cumulative, fontSize: 10 },
          axisLine: { show: false },
          axisTick: { show: false },
          axisLabel: {
            color: PALETTE.cumulative,
            fontSize: 10,
            fontFamily: 'Consolas, monospace',
            formatter: '{value}%',
          },
          splitLine: { show: false },
        },
      ],
      series: [
        {
          name: 'Valorização',
          type: 'bar',
          // An item with no stock on hand has no value tied up, so its bar
          // would have zero height and read as a rendering glitch. Give those a
          // thin hatched stub instead: the bar count then always matches the
          // item count, and the reason stays visible.
          data: values.map((value, i) => {
            const abcClass = items[i].abcClass;
            if (value > 0) {
              return {
                value,
                itemStyle: {
                  color: PALETTE[abcClass] || PALETTE.C,
                  borderRadius: [3, 3, 0, 0],
                },
              };
            }
            return {
              value: 0,
              itemStyle: {
                color: PALETTE[abcClass] || PALETTE.C,
                opacity: 0.35,
                borderColor: PALETTE[abcClass] || PALETTE.C,
                borderWidth: 1,
                borderType: [3, 2],
              },
            };
          }),
          barMaxWidth: 34,
          emphasis: { itemStyle: { opacity: 0.85 } },
        },
        {
          name: '% Acumulado',
          type: 'line',
          yAxisIndex: 1,
          data: cumulative,
          smooth: 0.25,
          symbol: 'circle',
          symbolSize: 5,
          lineStyle: { width: 2, color: PALETTE.cumulative },
          itemStyle: { color: PALETTE.cumulative },
          // Marks the 80% cutoff — the threshold that defines class A.
          markLine: {
            silent: true,
            symbol: 'none',
            label: {
              formatter: '80%',
              color: PALETTE.A,
              fontSize: 10,
              fontFamily: 'Consolas, monospace',
              position: 'insideEndTop',
            },
            lineStyle: { color: PALETTE.A, type: 'dashed', width: 1, opacity: 0.7 },
            data: [{ yAxis: 80 }],
          },
        },
      ],
    }, true);
  }

  function formatCurrency(value) {
    return new Intl.NumberFormat('pt-BR', {
      style: 'currency', currency: 'BRL',
    }).format(Number(value || 0));
  }

  function formatCurrencyShort(value) {
    const n = Number(value || 0);
    if (Math.abs(n) >= 1_000_000) return `R$${(n / 1_000_000).toFixed(1)}M`;
    if (Math.abs(n) >= 1_000) return `R$${(n / 1_000).toFixed(1)}k`;
    return `R$${n.toFixed(0)}`;
  }

  function resize() {
    instance && !instance.isDisposed() && instance.resize();
  }

  return { render, resize, PALETTE };
})();