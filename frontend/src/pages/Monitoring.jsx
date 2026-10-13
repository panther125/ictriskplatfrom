import React, { useEffect, useMemo, useRef, useState } from 'react';
import { api } from '../api/client.js';
import Chart from '../components/Chart.jsx';
import { MetricCard, formatNum } from '../components/Common.jsx';

/**
 * 运行监控 · 全链路
 *
 * 组织方式：一笔请求从进来到出结论，中间经过七层。页面就按这七层铺开，
 * 每层只放该层真正会被问责的指标——不堆无意义的计数卡。
 *
 *   L1 接入   L2 取数   L3 特征   L4 模型   L5 规则   L6 决策   L7 贷后
 *
 * 状态口径（PASS/WARN/FAIL、告警级别）全部由后端判定后下发，前端不做猜测。
 */

const LAYERS = [
  { code: 'L1', name: '接入', desc: '网关 · 端到端耗时 · 成功率' },
  { code: 'L2', name: '取数', desc: '外部数据源 · 熔断降级' },
  { code: 'L3', name: '特征', desc: 'PSI 漂移 · 缺失率' },
  { code: 'L4', name: '模型', desc: 'KS/AUC · 评分分布' },
  { code: 'L5', name: '规则', desc: '命中率 · 死规则' },
  { code: 'L6', name: '决策', desc: '通过率 · 渠道' },
  { code: 'L7', name: '贷后', desc: 'FPD · Vintage' }
];

/* 复用现有 tag-* 配色，不新增全局样式 */
const PILL = {
  PASS: 'stable', STABLE: 'stable', NORMAL: 'normal', 正常: 'normal',
  WARN: 'warn', HOT: 'warn', 关注: 'warn',
  FAIL: 'break', FATAL: 'break', BREAK: 'break', DRIFT: 'break', 漂移: 'break', 熔断: 'break',
  INFO: 'default', DEAD: 'default'
};

function Pill({ text, title }) {
  const cls = PILL[text] || 'default';
  return <span className={`tag tag-${cls}`} title={title}>{text}</span>;
}

const NUM = (v, d = 2) => (v == null ? '—' : Number(v).toFixed(d));
const ts = (v) => String(v ?? '').slice(11, 16);

export default function Monitoring() {
  const [d, setD] = useState(null);
  const [err, setErr] = useState(null);
  useEffect(() => {
    api.monitoring().then(setD).catch((e) => setErr(e?.message || '加载失败'));
  }, []);

  if (err) return <div className="empty">加载失败：{err}</div>;
  if (!d) return <div className="empty">加载中…</div>;

  return (
    <div>
      <div className="page-header">
        <h1>运行监控</h1>
        <div className="sub">
          发布与运行 / 运行监控 · 全链路 L1 接入 → L7 贷后
          {d.meta && ` · ${d.meta.window} · 更新于 ${d.meta.updatedAt}`}
        </div>
      </div>

      <LayerNav />
      <SloBar slo={d.slo} />
      <KpiRow kpi={d.kpi} />
      <AlarmList alarms={d.alarms} />

      <LayerSection id="L1" title="L1 · 接入与链路耗时" sub="端到端 143ms 是怎么花掉的">
        <StageSection stages={d.stages} trend={d.trend} />
      </LayerSection>

      <LayerSection id="L2" title="L2 · 外部数据取数" sub="银行风控最大的耗时来源，也是最容易炸的一段">
        <DatasourceTable rows={d.datasources} />
      </LayerSection>

      <LayerSection id="L3" title="L3 · 特征稳定性" sub="PSI 是每天必看的漂移信号">
        <FeatureTable rows={d.features} />
      </LayerSection>

      <LayerSection id="L4" title="L4 · 模型表现" sub="冠军 / 挑战者对比，评分分布与分箱坏账">
        <ModelSection models={d.models} buckets={d.scoreBuckets} />
      </LayerSection>

      <LayerSection id="L5" title="L5 · 规则命中" sub="死规则与过热规则是策略腐化的两个典型信号">
        <RuleTable rows={d.rules} />
      </LayerSection>

      <LayerSection id="L6" title="L6 · 决策与业务表现" sub="通过率是银行最敏感的业务指标">
        <TrendSection trend={d.trend} channels={d.channels} />
      </LayerSection>

      <LayerSection id="L7" title="L7 · 贷后资产质量" sub="放出去的钱，到底坏没坏">
        <OutcomeSection outcomes={d.outcomes} kpi={d.kpi} />
      </LayerSection>

      {d.meta?.note && (
        <div className="sub" style={{ marginTop: 12, fontSize: 12.5 }}>口径说明：{d.meta.note}</div>
      )}
    </div>
  );
}

/* ------------------------------------------------------------------ 导航 */

function LayerNav() {
  const jump = (id) => document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  return (
    <div className="card" style={{ padding: '10px 12px', marginBottom: 14 }}>
      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
        {LAYERS.map((l) => (
          <button key={l.code} className="btn" title={l.desc} onClick={() => jump(l.code)}
            style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
            <span className="mono" style={{ fontSize: 11.5, color: 'var(--ink-3)' }}>{l.code}</span>
            <span>{l.name}</span>
          </button>
        ))}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ SLO */

function SloBar({ slo }) {
  if (!slo?.length) return null;
  const fail = slo.filter((s) => s.status === 'FAIL').length;
  const warn = slo.filter((s) => s.status === 'WARN').length;
  return (
    <div className="card" style={{ marginBottom: 14 }}>
      <div className="card-title">
        <span>SLO 红绿灯</span>
        <span className="sub">
          {fail > 0 ? `${fail} 项未达标 · ` : ''}{warn > 0 ? `${warn} 项需关注 · ` : ''}
          共 {slo.length} 项，阈值由后端判定
        </span>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(168px,1fr))', gap: 8 }}>
        {slo.map((s) => (
          <div key={s.key} title={s.hint}
            style={{
              border: '1px solid var(--stroke)', borderRadius: 6, padding: '8px 10px',
              background: s.status === 'FAIL' ? 'var(--bg-soft)' : 'var(--bg-card)',
              borderLeft: `3px solid ${s.status === 'PASS' ? 'var(--pass)' : s.status === 'WARN' ? '#C2410C' : 'var(--reject)'}`
            }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 4 }}>
              <span style={{ fontSize: 12, color: 'var(--ink-2)' }}>{s.label}</span>
              <span className="mono" style={{ fontSize: 10.5, color: 'var(--ink-weak)' }}>{s.layer}</span>
            </div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginTop: 4 }}>
              <span className="mono" style={{ fontSize: 15, fontWeight: 600, color: 'var(--ink-1)' }}>{s.actual}</span>
              <span className="mono" style={{ fontSize: 11, color: 'var(--ink-3)' }}>{s.threshold}</span>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function KpiRow({ kpi }) {
  if (!kpi?.length) return null;
  return (
    <div className="grid grid-4" style={{ marginBottom: 14 }}>
      {kpi.map((k) => {
        const color = k.state === 'BREAK' ? 'var(--reject)' : k.state === 'WARN' ? '#C2410C' : null;
        return (
          <div key={k.key} style={color ? { borderLeft: `3px solid ${color}`, borderRadius: 6, overflow: 'hidden' } : {}}>
            <MetricCard label={k.label} value={k.value + (k.unit || '')} note={k.comparison} />
          </div>
        );
      })}
    </div>
  );
}

/* ------------------------------------------------------------------ 告警 */

function AlarmList({ alarms }) {
  const [open, setOpen] = useState(true);
  if (!alarms?.length) return null;
  const fatal = alarms.filter((a) => a.level === 'FATAL').length;
  return (
    <div className="card" style={{ marginBottom: 14 }}>
      <div className="card-title">
        <span>告警</span>
        <span className="sub">
          {fatal > 0 && <b style={{ color: 'var(--reject)' }}>{fatal} 项严重 · </b>}
          共 {alarms.length} 项
        </span>
        <button className="btn" style={{ marginLeft: 'auto' }} onClick={() => setOpen(!open)}>
          {open ? '收起' : '展开'}
        </button>
      </div>
      {open && alarms.map((a, i) => (
        <div className="row" key={i} style={{ alignItems: 'flex-start' }}>
          <div style={{ minWidth: 44 }}><Pill text={a.level} /></div>
          <div style={{ flex: 1 }}>
            <div className="title" style={{ fontSize: 13.5 }}>
              <span className="mono" style={{ color: 'var(--ink-3)' }}>{a.layer} </span>
              {a.target} · {a.title}
            </div>
            <div className="desc">{a.detail}</div>
            <div className="desc" style={{ color: 'var(--ink-3)' }}>建议：{a.suggestion}</div>
          </div>
          <span className="sub" style={{ whiteSpace: 'nowrap' }}>{a.occurredAt}</span>
        </div>
      ))}
    </div>
  );
}

/* ------------------------------------------------------------------ 分层容器 */

function LayerSection({ id, title, sub, children }) {
  return (
    <div id={id} style={{ scrollMarginTop: 12, marginBottom: 16 }}>
      <div className="card-title" style={{ padding: '0 0 6px' }}>
        <span style={{ fontSize: 14.5, fontWeight: 600, color: 'var(--ink-1)' }}>{title}</span>
        <span className="sub">{sub}</span>
      </div>
      {children}
    </div>
  );
}

/* ------------------------------------------------------------------ L1 链路耗时 */

function StageSection({ stages, trend }) {
  const sorted = useMemo(() => [...(stages || [])].sort((a, b) => a.sortNo - b.sortNo), [stages]);
  // 端到端 = 串行段累加 + 并行段取 max，与埋点口径一致
  const { total, fetchP99, fetchShare } = useMemo(() => {
    const serial = sorted.filter((s) => !s.parallel).reduce((a, s) => a + Number(s.p99Ms || 0), 0);
    const parallel = sorted.filter((s) => s.parallel).reduce((a, s) => Math.max(a, Number(s.p99Ms || 0)), 0);
    const t = serial + parallel;
    const f = sorted.find((s) => s.stageCode === 'fetch');
    return { total: t, fetchP99: Number(f?.p99Ms || 0), fetchShare: t ? (Number(f?.p99Ms || 0) / t) * 100 : 0 };
  }, [sorted]);

  const option = useMemo(() => ({
    tooltip: {
      trigger: 'axis', axisPointer: { type: 'shadow' },
      formatter: (p) => {
        const s = sorted.find((x) => x.stageName === p[0].name);
        return `<b>${p[0].name}</b><br/>P99 ${p[0].value}ms<br/>P95 ${s?.p95Ms}ms · P50 ${s?.p50Ms}ms`
          + `<br/>调用 ${formatNum(s?.calls)} 次 · 错误率 ${s?.errPct}%`
          + (s?.note ? `<br/><span style="opacity:.7">${s.note}</span>` : '');
      }
    },
    grid: { left: 120, right: 46, top: 12, bottom: 24 },
    xAxis: { type: 'value', name: 'ms', nameTextStyle: { fontSize: 11, color: 'var(--ink-3)' }, axisLabel: { fontSize: 11, color: 'var(--ink-3)' } },
    yAxis: {
      type: 'category', inverse: true,
      data: sorted.map((s) => s.stageName),
      axisLabel: { fontSize: 11.5, color: 'var(--ink-2)' }
    },
    series: [{
      type: 'bar', barWidth: 13,
      data: sorted.map((s) => ({
        value: Number(s.p99Ms),
        itemStyle: {
          borderRadius: [0, 3, 3, 0],
          color: s.parallel ? '#C2410C' : (Number(s.p99Ms) >= 12 ? '#E8740F' : '#D9A03C')
        }
      })),
      label: { show: true, position: 'right', fontSize: 11, color: 'var(--ink-2)', formatter: '{c}ms' }
    }]
  }), [sorted]);

  const trendOption = useMemo(() => ({
    tooltip: { trigger: 'axis' },
    legend: { data: ['进件量', 'P99耗时'], top: 0, textStyle: { fontSize: 11.5, color: 'var(--ink-2)' } },
    grid: { left: 48, right: 48, top: 30, bottom: 30 },
    xAxis: { type: 'category', data: (trend || []).map((t) => ts(t.ts)), axisLabel: { fontSize: 11, color: 'var(--ink-3)', interval: 2 } },
    yAxis: [
      { type: 'value', name: '笔', axisLabel: { fontSize: 11, color: 'var(--ink-3)', formatter: (v) => v / 1000 + 'k' } },
      { type: 'value', name: 'ms', axisLabel: { fontSize: 11, color: 'var(--ink-3)' } }
    ],
    series: [
      { name: '进件量', type: 'bar', data: (trend || []).map((t) => t.reqCnt), itemStyle: { color: '#D9A03C', borderRadius: [2, 2, 0, 0] } },
      {
        name: 'P99耗时', type: 'line', yAxisIndex: 1, smooth: true, symbol: 'none',
        data: (trend || []).map((t) => Number(t.p99Ms)), lineStyle: { width: 1.8, color: '#C2410C' },
        markLine: { silent: true, symbol: 'none', data: [{ yAxis: 200, name: 'SLO' }], lineStyle: { color: 'var(--reject)', type: 'dashed', width: 1 }, label: { formatter: 'SLO 200ms', fontSize: 10 } }
      }
    ]
  }), [trend]);

  return (
    <div className="grid grid-2">
      <div className="card">
        <div className="card-title">
          <span>链路分段耗时 · P99</span>
          <span className="sub">端到端 {NUM(total, 0)}ms · 取数段占 {NUM(fetchShare, 0)}%</span>
        </div>
        <Chart option={option} height={280} />
      </div>
      <div className="card">
        <div className="card-title"><span>逐小时进件量与耗时</span><span className="sub">10:00 大促预热为峰值</span></div>
        <Chart option={trendOption} height={280} />
      </div>
      <div className="card" style={{ gridColumn: '1 / -1' }}>
        <table className="table">
          <thead>
            <tr><th>#</th><th>阶段</th><th>层</th><th>调用次数</th><th>P50</th><th>P95</th><th>P99</th><th>错误率</th><th>超时率</th></tr>
          </thead>
          <tbody>
            {sorted.map((s) => (
              <tr key={s.stageCode}>
                <td className="mono" style={{ color: 'var(--ink-3)' }}>{String(s.sortNo).padStart(2, '0')}</td>
                <td>
                  <div>{s.stageName}</div>
                  <div className="sub" style={{ fontSize: 11.5 }}>{s.note}</div>
                </td>
                <td><span className="mono" style={{ fontSize: 11.5, color: 'var(--ink-3)' }}>{s.layer}{s.parallel ? ' ·并行' : ''}</span></td>
                <td className="mono">{formatNum(s.calls)}</td>
                <td className="mono">{NUM(s.p50Ms, 1)}ms</td>
                <td className="mono">{NUM(s.p95Ms, 1)}ms</td>
                <td className="mono" style={{ fontWeight: 600 }}>{NUM(s.p99Ms, 1)}ms</td>
                <td className="mono" style={{ color: Number(s.errPct) > 0.1 ? 'var(--reject)' : undefined }}>{NUM(s.errPct, 3)}%</td>
                <td className="mono">{NUM(s.timeoutPct, 3)}%</td>
              </tr>
            ))}
            <tr>
              <td colSpan={6} style={{ textAlign: 'right', color: 'var(--ink-3)' }}>端到端合计</td>
              <td className="mono" style={{ fontWeight: 600 }}>{NUM(total, 1)}ms</td>
              <td colSpan={2} className="sub">取数段 {NUM(fetchP99, 0)}ms</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ L2 数据源 */

function DatasourceTable({ rows }) {
  return (
    <div className="card">
      <table className="table">
        <thead>
          <tr>
            <th>数据源</th><th>类型</th><th>调用量</th><th>成功率</th><th>超时率</th>
            <th>P99</th><th>缓存命中</th><th>陈旧时长</th><th>配额</th><th>降级</th><th>状态</th>
          </tr>
        </thead>
        <tbody>
          {(rows || []).map((r) => (
            <tr key={r.dsCode}>
              <td>
                <div className="code-cell">{r.dsName}</div>
                <div className="sub" style={{ fontSize: 11.5 }}>{r.note}</div>
              </td>
              <td className="mono" style={{ fontSize: 11.5 }}>{r.dsType}</td>
              <td className="mono">{formatNum(r.calls)}</td>
              <td className="mono" style={{ color: Number(r.successPct) < 99.5 ? 'var(--reject)' : undefined }}>{NUM(r.successPct, 2)}%</td>
              <td className="mono" style={{ color: Number(r.timeoutPct) > 0.5 ? 'var(--reject)' : undefined }}>{NUM(r.timeoutPct, 2)}%</td>
              <td className="mono" style={{ fontWeight: Number(r.p99Ms) > 200 ? 600 : undefined }}>{NUM(r.p99Ms, 0)}ms</td>
              <td className="mono">{NUM(r.cacheHitPct, 1)}%</td>
              <td className="mono" style={{ color: r.staleMin > 60 ? 'var(--reject)' : undefined }}>
                {r.staleMin === 0 ? '—' : r.staleMin >= 60 ? `${(r.staleMin / 60).toFixed(0)} 小时` : `${r.staleMin} 分钟`}
              </td>
              <td className="mono">{r.quotaPct > 0 ? `${NUM(r.quotaPct, 1)}%` : '—'}</td>
              <td className="mono" style={{ color: r.degradeCnt > 0 ? '#C2410C' : undefined }}>{r.degradeCnt}</td>
              <td><Pill text={r.status === 'NORMAL' ? '正常' : r.status === 'CIRCUIT_BREAKER' ? '熔断' : 'WARN'} /></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/* ------------------------------------------------------------------ L3 特征 */

function FeatureTable({ rows }) {
  const psiColor = (psi) => (psi < 0.1 ? 'var(--pass)' : psi < 0.25 ? '#C2410C' : 'var(--reject)');
  return (
    <div className="card">
      <table className="table">
        <thead>
          <tr><th>特征</th><th>调用量</th><th>空值率</th><th>兜底命中</th><th>PSI</th><th>分级</th><th>平均耗时</th><th>P99</th><th>状态</th></tr>
        </thead>
        <tbody>
          {(rows || []).map((f) => (
            <tr key={f.featCode}>
              <td>
                <div className="code-cell">{f.featName}</div>
                <div className="sub mono" style={{ fontSize: 11.5 }}>{f.featCode}</div>
              </td>
              <td className="mono">{formatNum(f.calls)}</td>
              <td className="mono" style={{ color: Number(f.nullPct) > 1 ? 'var(--reject)' : undefined }}>{NUM(f.nullPct, 2)}%</td>
              <td className="mono">{NUM(f.defaultHitPct, 2)}%</td>
              <td className="mono" style={{ fontWeight: 600, color: psiColor(Number(f.psi)) }}>{NUM(f.psi, 3)}</td>
              <td>
                <span className="mono" style={{ fontSize: 11.5, color: psiColor(Number(f.psi)) }}>
                  {f.psiLevel === 'STABLE' ? '稳定' : f.psiLevel === 'WARN' ? '关注' : '漂移'}
                </span>
              </td>
              <td className="mono">{NUM(f.avgMs, 1)}ms</td>
              <td className="mono">{NUM(f.p99Ms, 0)}ms</td>
              <td><Pill text={f.status === 'NORMAL' ? '正常' : f.status === 'WARN' ? 'WARN' : 'BREAK'} /></td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="sub" style={{ padding: '8px 10px 2px', fontSize: 12 }}>
        PSI 口径：&lt;0.1 稳定 · 0.1~0.25 关注 · &gt;0.25 漂移需排查上游
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ L4 模型 */

function ModelSection({ models, buckets }) {
  const option = useMemo(() => ({
    tooltip: { trigger: 'axis' },
    legend: { data: ['训练基线', '当前线上', '分箱坏账率'], top: 0, textStyle: { fontSize: 11.5, color: 'var(--ink-2)' } },
    grid: { left: 44, right: 48, top: 32, bottom: 28 },
    xAxis: { type: 'category', data: (buckets || []).map((b) => b.bucketLabel), axisLabel: { fontSize: 11, color: 'var(--ink-3)' } },
    yAxis: [
      { type: 'value', name: '占比%', axisLabel: { fontSize: 11, color: 'var(--ink-3)' } },
      { type: 'value', name: '坏账%', axisLabel: { fontSize: 11, color: 'var(--ink-3)' } }
    ],
    series: [
      { name: '训练基线', type: 'bar', data: (buckets || []).map((b) => Number(b.basePct)), itemStyle: { color: '#C9BBA9', borderRadius: [2, 2, 0, 0] } },
      { name: '当前线上', type: 'bar', data: (buckets || []).map((b) => Number(b.curPct)), itemStyle: { color: '#E8740F', borderRadius: [2, 2, 0, 0] } },
      { name: '分箱坏账率', type: 'line', yAxisIndex: 1, smooth: true, data: (buckets || []).map((b) => Number(b.badRate)), lineStyle: { width: 1.8, color: 'var(--reject)' }, itemStyle: { color: 'var(--reject)' }, symbolSize: 5 }
    ]
  }), [buckets]);

  return (
    <div>
      <div className="grid grid-3" style={{ marginBottom: 14 }}>
        {(models || []).map((m) => (
          <div className="card" key={m.modelCode}>
            <div className="card-title">
              <span>{m.modelName}</span>
              <span className="sub mono">{m.version} · {m.role}</span>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '6px 10px', padding: '0 2px 4px' }}>
              <Stat label="PSI" value={NUM(m.psi, 3)} color={Number(m.psi) < 0.1 ? 'var(--pass)' : Number(m.psi) < 0.25 ? '#C2410C' : 'var(--reject)'} />
              <Stat label="KS" value={NUM(m.ks, 3)} />
              <Stat label="AUC" value={NUM(m.auc, 3)} />
              <Stat label="平均分" value={NUM(m.avgScore, 1)} />
              <Stat label="P99" value={NUM(m.p99Ms, 0) + 'ms'} />
              <Stat label="超时率" value={NUM(m.timeoutPct, 2) + '%'} />
            </div>
            <div className="sub" style={{ fontSize: 11.5 }}>调用 {formatNum(m.calls)} 次 · <Pill text={m.status === 'NORMAL' ? '正常' : 'WARN'} /></div>
          </div>
        ))}
      </div>
      <div className="card">
        <div className="card-title">
          <span>评分分布与分箱坏账 · 申请评分卡A</span>
          <span className="sub">低分段占比上升 = 客群下沉；坏账率须随分数单调递减</span>
        </div>
        <Chart option={option} height={250} />
      </div>
    </div>
  );
}

function Stat({ label, value, color }) {
  return (
    <div>
      <div style={{ fontSize: 11.5, color: 'var(--ink-3)' }}>{label}</div>
      <div className="mono" style={{ fontSize: 14, fontWeight: 600, color: color || 'var(--ink-1)' }}>{value}</div>
    </div>
  );
}

/* ------------------------------------------------------------------ L5 规则 */

function RuleTable({ rows }) {
  return (
    <div className="card">
      <table className="table">
        <thead>
          <tr><th>规则</th><th>类别</th><th>命中次数</th><th>命中率</th><th>上期</th><th>环比</th><th>信号</th></tr>
        </thead>
        <tbody>
          {(rows || []).map((r) => {
            const delta = Number(r.hitPct) - Number(r.hitPctPrev);
            return (
              <tr key={r.ruleCode}>
                <td>
                  <div className="mono" style={{ fontSize: 12.5 }}>{r.ruleCode}</div>
                  <div className="sub">{r.ruleName}</div>
                </td>
                <td className="mono" style={{ fontSize: 11.5 }}>{r.category}</td>
                <td className="mono">{formatNum(r.hitCnt)}</td>
                <td className="mono" style={{ fontWeight: 600 }}>{NUM(r.hitPct, 2)}%</td>
                <td className="mono" style={{ color: 'var(--ink-3)' }}>{NUM(r.hitPctPrev, 2)}%</td>
                <td className="mono" style={{ color: Math.abs(delta) >= 0.5 ? 'var(--reject)' : 'var(--ink-3)' }}>
                  {delta === 0 ? '—' : (delta > 0 ? '+' : '') + NUM(delta, 2) + 'pp'}
                </td>
                <td>
                  {r.flag === 'DEAD' ? <Pill text="DEAD" title="零命中死规则" />
                    : r.flag === 'HOT' ? <Pill text="HOT" title="命中率突增" />
                      : <span className="sub">—</span>}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

/* ------------------------------------------------------------------ L6 业务 */

function TrendSection({ trend, channels }) {
  const option = useMemo(() => ({
    tooltip: { trigger: 'axis' },
    legend: { data: ['通过率', '复核率', '拒绝率'], top: 0, textStyle: { fontSize: 11.5, color: 'var(--ink-2)' } },
    grid: { left: 44, right: 24, top: 30, bottom: 28 },
    xAxis: { type: 'category', data: (trend || []).map((t) => ts(t.ts)), axisLabel: { fontSize: 11, color: 'var(--ink-3)', interval: 2 } },
    yAxis: { type: 'value', name: '%', max: 100, axisLabel: { fontSize: 11, color: 'var(--ink-3)' } },
    series: [
      { name: '通过率', type: 'line', smooth: true, symbol: 'none', data: (trend || []).map((t) => Number(t.passPct)), lineStyle: { width: 2, color: 'var(--pass)' }, areaStyle: { color: 'rgba(26,127,90,.08)' } },
      { name: '复核率', type: 'line', smooth: true, symbol: 'none', data: (trend || []).map((t) => Number(t.reviewPct)), lineStyle: { width: 1.6, color: '#D9A03C' } },
      { name: '拒绝率', type: 'line', smooth: true, symbol: 'none', data: (trend || []).map((t) => Number(t.rejectPct)), lineStyle: { width: 1.6, color: 'var(--reject)' } }
    ]
  }), [trend]);

  const chanOption = useMemo(() => ({
    tooltip: { trigger: 'axis' },
    legend: { data: ['通过率', '拒绝率', 'FPD30'], top: 0, textStyle: { fontSize: 11.5, color: 'var(--ink-2)' } },
    grid: { left: 44, right: 44, top: 30, bottom: 28 },
    xAxis: { type: 'category', data: (channels || []).map((c) => c.channel), axisLabel: { fontSize: 11, color: 'var(--ink-3)' } },
    yAxis: [
      { type: 'value', name: '%', axisLabel: { fontSize: 11, color: 'var(--ink-3)' } },
      { type: 'value', name: 'FPD%', axisLabel: { fontSize: 11, color: 'var(--ink-3)' } }
    ],
    series: [
      { name: '通过率', type: 'bar', data: (channels || []).map((c) => Number(c.passPct)), itemStyle: { color: 'var(--pass)', borderRadius: [2, 2, 0, 0] } },
      { name: '拒绝率', type: 'bar', data: (channels || []).map((c) => Number(c.rejectPct)), itemStyle: { color: 'var(--reject)', borderRadius: [2, 2, 0, 0] } },
      { name: 'FPD30', type: 'line', yAxisIndex: 1, data: (channels || []).map((c) => Number(c.fpd30Pct)), lineStyle: { width: 1.8, color: '#C2410C' }, itemStyle: { color: '#C2410C' }, symbolSize: 5 }
    ]
  }), [channels]);

  return (
    <div className="grid grid-2">
      <div className="card">
        <div className="card-title"><span>结论分布趋势</span><span className="sub">逐小时 · 凌晨客群质量偏差会自然拉低通过率</span></div>
        <Chart option={option} height={250} />
      </div>
      <div className="card">
        <div className="card-title"><span>渠道风险表现</span><span className="sub">通过率与 FPD30 交叉看，避免「放量即放坏」</span></div>
        <Chart option={chanOption} height={250} />
      </div>
      <div className="card" style={{ gridColumn: '1 / -1' }}>
        <table className="table">
          <thead>
            <tr><th>渠道</th><th>进件量</th><th>通过率</th><th>拒绝率</th><th>FPD30</th><th>核准金额</th></tr>
          </thead>
          <tbody>
            {(channels || []).map((c) => (
              <tr key={c.channelCode}>
                <td><div>{c.channel}</div><div className="sub mono" style={{ fontSize: 11.5 }}>{c.channelCode}</div></td>
                <td className="mono">{formatNum(c.reqCnt)}</td>
                <td className="mono" style={{ color: 'var(--pass)', fontWeight: 600 }}>{NUM(c.passPct, 2)}%</td>
                <td className="mono" style={{ color: 'var(--reject)' }}>{NUM(c.rejectPct, 2)}%</td>
                <td className="mono" style={{ color: Number(c.fpd30Pct) > 2 ? 'var(--reject)' : undefined }}>{NUM(c.fpd30Pct, 2)}%</td>
                <td className="mono">{formatNum(c.amountWan)} 万元</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ L7 贷后 */

function OutcomeSection({ outcomes, kpi }) {
  const months = useMemo(() => [...new Set((outcomes || []).map((o) => o.vintageMonth))].sort(), [outcomes]);
  const option = useMemo(() => {
    const palette = ['#C2410C', '#E8740F', '#D9A03C', '#9A6B1F', '#7A6A57', '#BE3A2B'];
    return {
      tooltip: { trigger: 'axis', valueFormatter: (v) => v + '%' },
      legend: { data: months, top: 0, textStyle: { fontSize: 11.5, color: 'var(--ink-2)' } },
      grid: { left: 44, right: 24, top: 32, bottom: 28 },
      xAxis: { type: 'category', data: ['MOB1', 'MOB2', 'MOB3', 'MOB4', 'MOB5', 'MOB6'], axisLabel: { fontSize: 11, color: 'var(--ink-3)' } },
      yAxis: { type: 'value', name: '累计坏账%', axisLabel: { fontSize: 11, color: 'var(--ink-3)' } },
      series: months.map((m, i) => ({
        name: m, type: 'line', smooth: true, symbolSize: 4,
        data: (outcomes || []).filter((o) => o.vintageMonth === m).map((o) => Number(o.cumBadPct)),
        lineStyle: { width: 1.8, color: palette[i % palette.length] },
        itemStyle: { color: palette[i % palette.length] }
      }))
    };
  }, [months, outcomes]);

  const fpd = kpi?.filter((k) => ['fpd30'].includes(k.key)) || [];
  return (
    <div className="grid grid-2">
      <div className="card">
        <div className="card-title">
          <span>Vintage 累计坏账曲线</span>
          <span className="sub">按放款月份追踪，同 MOB 横向比看资产质量趋势</span>
        </div>
        <Chart option={option} height={260} />
      </div>
      <div className="card">
        <div className="card-title"><span>逾期指标</span><span className="sub">首期逾期是最早的风险信号</span></div>
        <div className="grid grid-2" style={{ gap: 8 }}>
          {(fpd.length ? fpd : []).map((k) => (
            <MetricCard key={k.key} label={k.label} value={k.value + (k.unit || '')} note={k.comparison} />
          ))}
          {(outcomes || []).filter((o) => o.mob === 6).map((o) => (
            <MetricCard key={o.vintageMonth} label={`${o.vintageMonth} 批次 MOB6 坏账`}
              value={NUM(o.cumBadPct, 2) + '%'} note={`DPD30+ ${NUM(o.dpd30Pct, 2)}%`} />
          ))}
        </div>
      </div>
    </div>
  );
}
