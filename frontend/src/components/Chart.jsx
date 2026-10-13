import React, { useEffect, useRef } from 'react';
import * as echarts from 'echarts';

/**
 * ECharts 轻量封装：统一浅色风控主题。
 */
export default function Chart({ option, height = 260, className = '' }) {
  const ref = useRef(null);
  const chartRef = useRef(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const chart = echarts.init(el);
    chartRef.current = chart;
    return () => {
      chart.dispose();
      chartRef.current = null;
    };
  }, []);

  useEffect(() => {
    if (chartRef.current && option) {
      chartRef.current.setOption(option, true);
    }
  }, [option]);

  return <div ref={ref} className={`chart ${className}`} style={{ height }} />;
}
