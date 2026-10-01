/* =====================================================================
   Control Tower gauges  (Apache ECharts)
   - gaugeHealth : inventory health index (0-100)
   - donutAbc    : catalogue composition per ABC class
   ===================================================================== */

const GaugeChart = (() => {
  let gauge = null;
  let donut = null;

  /* -------------------------------------------------------------------
     Inventory health index
     ------------------------------------------------------------------- */
  function mountGauge(element) {
    if (!element || typeof echarts === 'undefined') {
      return null;
    }
    if (!gauge || gauge.isDisposed()) {
      gauge = echarts.init(element, null, { renderer: 'canvas' });
    }
    return gauge;
  }

  /**
   * @param {HTMLElement} element  #gauge-health
   * @param {number} healthIndex   0-100 (100 = no item at risk)
   * @param {number} stockoutCount
   * @param {number} criticalCount
   */
  function renderGauge(element, healthIndex, stockoutCount, criticalCount) {
    const chart = mountGauge(element);
    if (!chart) return;

    const index = Math.max(0, Math.min(100, Number(healthIndex) || 0));
    const color = index >= 80 ? '#2ecc71' : index >= 50 ? '#f5a524' : '#ef4a4a';
    const status = index >= 80 ? 'ESTÁVEL' : index >= 50 ? 'ATENÇÃO' : 'CRÍTICO';

    chart.setOption({
      backgroundColor: 'transparent',
      series: [{
        type: 'gauge',
        startAngle: 210,
        endAngle: -30,
        min: 0,
        max: 100,
        radius: '96%',
        center: ['50%', '62%'],
        progress: {
          show: true,
          width: 15,
          roundCap: true,
          itemStyle: { color },
        },
        axisLine: {
          lineStyle: { width: 15, color: [[1, 'rgba(36, 48, 60, 0.9)']] },
        },
        pointer: { show: false },
        axisTick: { show: false },
        splitLine: { show: false },
        axisLabel: { show: false },
        anchor: { show: false },
        title: {
          show: true,
          offsetCenter: [0, '30%'],
          color: '#6b7d8f',
          fontSize: 10.5,
          fontWeight: 600,
        },
        detail: {
          valueAnimation: true,
          offsetCenter: [0, '2%'],
          formatter: (v) => `{v|${v.toFixed(1)}}{u|/100}`,
          rich: {
            v: {
              fontSize: 34, fontWeight: 700,
              fontFamily: 'Consolas, monospace', color,
            },
            u: { fontSize: 13, color: '#6b7d8f', padding: [0, 0, 6, 2] },
          },
        },
        data: [{
          value: index,
          name: status,
        }],
      }],
    }, true);

    // Contextual caption: where the problem is concentrated.
    if (stockoutCount + criticalCount > 0) {
      chart.setOption({
        graphic: [{
          type: 'text',
          left: 'center',
          bottom: 2,
          style: {
            text: `${stockoutCount} ruptura · ${criticalCount} crítico(s)`,
            fill: '#6b7d8f',
            fontSize: 10.5,
            fontFamily: 'Consolas, monospace',
          },
        }],
      });
    } else {
      chart.setOption({ graphic: [] });
    }
  }

  /* -------------------------------------------------------------------
     ABC composition (donut)
     ------------------------------------------------------------------- */
  function mountDonut(element) {
    if (!element || typeof echarts === 'undefined') {
      return null;
    }
    if (!donut || donut.isDisposed()) {
      donut = echarts.init(element, null, { renderer: 'canvas' });
    }
    return donut;
  }

  /**
   * @param {HTMLElement} element #chart-abc-donut
   * @param {AbcCurveDTO.SummaryByCategoryDTO} summary
   */
  function renderDonut(element, summary) {
    const chart = mountDonut(element);
    if (!chart || !summary) return;

    const classA = Number(summary.classAItems || 0);
    const classB = Number(summary.classBItems || 0);
    const classC = Number(summary.classCItems || 0);
    const total = classA + classB + classC;

    if (total === 0) {
      chart.clear();
      return;
    }

    chart.setOption({
      backgroundColor: 'transparent',
      tooltip: {
        trigger: 'item',
        backgroundColor: 'rgba(19, 26, 34, 0.97)',
        borderColor: '#32404f',
        borderWidth: 1,
        textStyle: { color: '#e6edf3', fontSize: 12 },
        formatter: (p) => `<b>${p.name}</b><br/>${p.value} itens (${p.percent}%)`,
      },
      legend: {
        bottom: 0,
        itemWidth: 11,
        itemHeight: 11,
        textStyle: { color: '#9fb0c0', fontSize: 11 },
      },
      series: [{
        type: 'pie',
        radius: ['52%', '76%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        itemStyle: {
          borderColor: '#131a22',
          borderWidth: 2,
          borderRadius: 3,
        },
        label: { show: false },
        emphasis: {
          scaleSize: 6,
          label: {
            show: true,
            formatter: (p) => `${p.percent}%`,
            color: '#e6edf3',
            fontSize: 12,
            fontWeight: 700,
            fontFamily: 'Consolas, monospace',
          },
        },
        data: [
          { name: `Classe A (${classA})`, value: classA, itemStyle: { color: '#ef4a4a' } },
          { name: `Classe B (${classB})`, value: classB, itemStyle: { color: '#f5a524' } },
          { name: `Classe C (${classC})`, value: classC, itemStyle: { color: '#3ba9e0' } },
        ],
      }],
    }, true);
  }

  function resize() {
    gauge && !gauge.isDisposed() && gauge.resize();
    donut && !donut.isDisposed() && donut.resize();
  }

  return { renderGauge, renderDonut, resize };
})();