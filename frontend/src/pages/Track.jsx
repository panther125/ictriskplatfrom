import React, { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../api/client.js';
import { ConclusionBadge } from '../components/Common.jsx';

const RANGE_OPTIONS = [
  { value: '1h', label: '近 1 小时' },
  { value: '6h', label: '近 6 小时' },
  { value: '24h', label: '近 24 小时' }
];

export default function Track() {
  // 试算中心「在决策日志中查看」带 ?traceId=xxx 跳过来：直接预填检索框，进入即可看到那条链路
  const [searchParams] = useSearchParams();
  const [range, setRange] = useState('1h');
  const [stats, setStats] = useState(null);
  const [records, setRecords] = useState([]);
  const [selectedId, setSelectedId] = useState(null);
  const [detail, setDetail] = useState(null);
  const [sampling, setSampling] = useState(null);
  const [degradeSources, setDegradeSources] = useState([]);
  const [keyword, setKeyword] = useState(() => searchParams.get('traceId') || '');
  const [conclusion, setConclusion] = useState('');
  const [err, setErr] = useState('');
  const [replaying, setReplaying] = useState(false);

  // 统计条 + 降级来源：GET /track/stats、GET /track/degrade-sources
  useEffect(() => {
    api.track.stats(range).then(setStats).catch(() => setStats(null));
    api.track.degradeSources(range).then(setDegradeSources).catch(() => setDegradeSources([]));
  }, [range]);

  // 采样与留存策略：GET /track/sampling-config
  useEffect(() => {
    api.track.samplingConfig().then(setSampling).catch(() => setSampling(null));
  }, []);

  // 列表：有筛选条件走 search，否则走 recent
  const loadList = () => {
    const q = {};
    if (keyword.trim()) q.traceId = keyword.trim();
    if (conclusion) q.conclusion = conclusion;
    const req = Object.keys(q).length ? api.track.search(q) : api.track.recent();
    req.then(list => {
      setRecords(list || []);
      setSelectedId(prev => (list || []).some(r => r.id === prev) ? prev : (list?.[0]?.id ?? null));
    }).catch(e => setErr(e.message || '加载决策日志失败'));
  };

  useEffect(loadList, [keyword, conclusion]);

  // 详情：GET /track/{id}
  useEffect(() => {
    if (selectedId == null) { setDetail(null); return; }
    api.track.detail(selectedId).then(setDetail).catch(e => { setDetail(null); setErr(e.message || '加载 Trace 详情失败'); });
  }, [selectedId]);

  const replay = async () => {
    if (selectedId == null) return;
    setReplaying(true); setErr('');
    try {
      const r = await api.track.replay(selectedId);
      const cmp = r?.compared || {};
      alert(`回放完成\n原结论 ${cmp.conclusionBefore} → 现结论 ${cmp.conclusionAfter}\n结论${cmp.sameConclusion ? '一致' : '不一致'} · 耗时 ${cmp.elapsedBeforeMs}ms → ${cmp.elapsedAfterMs}ms（${cmp.deltaPct}%）`);
      loadList();
    } catch (e) { setErr(e.message || '回放失败'); }
    finally { setReplaying(false); }
  };

  const sel = records.find(r => r.id === selectedId);
  const steps = detail?.steps || [];
  const vars = detail?.variables || {};
  const timing = detail?.timingBreakdown || {};

  return (
    <div>
      <div className="page-header">
        <div>
          <h1>决策追踪 · Trace</h1>
          <div className="sub">运行态 / 决策日志与回放 / 单笔 Trace 详情</div>
        </div>
        <div className="actions" style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>
          共 {records.length} 条日志 · 点击任意一行查看完整链路
        </div>
      </div>

      {err && <div className="warn-bar" style={{ marginBottom: 12, justifyContent: 'flex-start' }}><b style={{ color: 'var(--reject)' }}>失败</b> {err}</div>}

      {/* 统计条：GET /track/stats */}
      <div style={{ display: 'flex', gap: 16, marginBottom: 16, flexWrap: 'wrap' }}>
        {[
          { label: '全部', value: stats?.total ?? '—', color: '#5C4A38' },
          { label: '降级', value: stats?.degraded ?? '—', color: 'var(--degrade)' },
          { label: '慢请求', value: stats?.slowRequests ?? '—', color: 'var(--review)' },
          { label: '异常', value: stats?.exceptions ?? '—', color: 'var(--reject)' }
        ].map(s => (
          <div key={s.label} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>{s.label}</span>
            <b className="mono" style={{ fontSize: 17, color: s.color }}>{s.value}</b>
          </div>
        ))}
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 8, alignItems: 'center' }}>
          <input
            className="input" style={{ width: 180 }} placeholder="按 traceId 搜索"
            value={keyword} onChange={e => setKeyword(e.target.value)}
          />
          <select className="select" style={{ width: 110 }} value={conclusion} onChange={e => setConclusion(e.target.value)}>
            <option value="">全部结论</option>
            <option value="REJECT">仅拒绝</option>
            <option value="REVIEW">仅人工</option>
            <option value="PASS">仅通过</option>
          </select>
          <select className="select" style={{ width: 110 }} value={range} onChange={e => setRange(e.target.value)}>
            {RANGE_OPTIONS.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}
          </select>
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1.6fr', gap: 16 }}>
        {/* 决策日志列表 */}
        <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
          <div style={{ padding: '12px 16px 8px' }}>
            <b>决策日志</b>
            <span className="sub" style={{ fontSize: 12.5, color: 'var(--ink-3)', marginLeft: 8 }}>
              {records.length} 条
            </span>
          </div>
          <table className="table">
            <thead><tr><th>traceId</th><th>策略</th><th>结论</th><th>耗时</th></tr></thead>
            <tbody>
              {records.map(r => (
                <tr
                  key={r.id}
                  onClick={() => setSelectedId(r.id)}
                  className={'clickable' + (selectedId === r.id ? ' is-selected' : '')}
                >
                  <td className="code-cell" style={{ color: 'var(--decision)' }}>{r.traceId}</td>
                  <td className="mono" style={{ fontSize: 12.5 }}>{r.strategyName || r.strategyId}</td>
                  <td><ConclusionBadge text={r.conclusion} /></td>
                  <td className="mono" style={{ fontSize: 12.5 }}>{r.totalElapsedMs}ms</td>
                </tr>
              ))}
            </tbody>
          </table>
          {!records.length && <div className="empty">暂无决策日志</div>}
        </div>

        {/* Trace 详情 */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div className="card">
            {detail ? (
              <>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                  <div>
                    <div className="mono" style={{ fontSize: 13.5, color: 'var(--ink-2)' }}>traceId</div>
                    <div className="mono" style={{ fontSize: 14, fontWeight: 500 }}>{detail.record?.traceId}</div>
                  </div>
                  <ConclusionBadge text={detail.record?.conclusion} />
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12, marginTop: 12, fontSize: 12.5 }}>
                  <div><div style={{ color: 'var(--ink-2)' }}>策略</div><div className="mono">{detail.record?.strategyName || detail.record?.strategyId}</div></div>
                  <div><div style={{ color: 'var(--ink-2)' }}>版本</div><div className="mono">{detail.record?.version}</div></div>
                  <div><div style={{ color: 'var(--ink-2)' }}>决策时间</div><div className="mono">{String(detail.record?.createdAt || '').replace('T', ' ').slice(0, 19)}</div></div>
                  <div><div style={{ color: 'var(--ink-2)' }}>总耗时</div><div className="mono">{detail.record?.totalElapsedMs}ms</div></div>
                  <div><div style={{ color: 'var(--ink-2)' }}>引擎耗时</div><div className="mono">{detail.record?.engineElapsedMs}ms</div></div>
                  <div><div style={{ color: 'var(--ink-2)' }}>命中规则</div><div className="mono">{detail.record?.hitRule || '—'}</div></div>
                </div>
                <div style={{ marginTop: 8, fontSize: 12.5, color: '#5C4A38' }}>
                  环境 {detail.record?.env} · 来源 {detail.record?.source}
                  {detail.record?.businessKey ? ` · 业务主键 ${detail.record.businessKey}` : ''}
                  {detail.record?.channel ? ` · 渠道 ${detail.record.channel}` : ''}
                </div>
                {detail.degraded && <div style={{ marginTop: 6 }}><span className="badge badge-reject">降级结论</span></div>}
              </>
            ) : <div className="empty">选择左侧记录查看 Trace 详情</div>}
          </div>

          <div className="card">
            <div className="card-title"><span>Decision Trace · 逐节点</span><span className="sub">{steps.length} span</span></div>
            {steps.length ? (
              <div className="timeline">
                {steps.map((s, i) => (
                  <div key={i} className={`timeline-item ${s.category === '取数' ? 'warn' : 'success'}`}>
                    <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                      <div>
                        <span className="mono" style={{ fontSize: 12.5, fontWeight: 500 }}>{s.nodeId} {s.nodeType}</span>
                        <span style={{ fontSize: 12.5, color: '#5C4A38', marginLeft: 8 }}>{s.stepTitle}</span>
                      </div>
                      <span className="mono" style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>{s.elapsedMs}ms</span>
                    </div>
                  </div>
                ))}
              </div>
            ) : <div className="empty">暂无 span 数据</div>}
            <div style={{ fontSize: 12.5, color: 'var(--ink-2)', marginTop: 8 }}>
              命中路径 {(detail?.hitPath || []).join(' → ') || '—'}
            </div>
          </div>

          <div className="card">
            <div className="card-title"><span>命中规则 & 变量快照</span></div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
              <div>
                <div style={{ marginBottom: 10 }}>
                  <span style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>命中规则</span>
                  <div className="mono" style={{ fontSize: 13.5, color: 'var(--reject)', marginTop: 2 }}>
                    {detail?.record?.hitRule || '—'}
                  </div>
                  <div className="mono" style={{ fontSize: 12.5, marginTop: 2 }}>原因码 {detail?.record?.reasonCode || '—'}</div>
                </div>
                <div>
                  <div style={{ fontSize: 12.5, color: 'var(--ink-2)', marginBottom: 4 }}>变量快照</div>
                  {Object.entries(vars).map(([ns, m]) => (
                    <div key={ns} style={{ marginBottom: 6 }}>
                      <div style={{ fontSize: 12, color: 'var(--ink-3)' }}>{ns}</div>
                      {Object.entries(m || {}).map(([k, v]) => (
                        <div key={k} className="mono" style={{ fontSize: 12.5 }}>
                          {k} = <b style={{ color: 'var(--decision)' }}>{v}</b>
                        </div>
                      ))}
                    </div>
                  ))}
                  {!Object.keys(vars).length && <div className="empty">—</div>}
                </div>
              </div>
              <div>
                <div style={{ fontSize: 12.5, color: 'var(--ink-2)', marginBottom: 4 }}>耗时分解</div>
                {Object.entries(timing).map(([cat, v]) => (
                  <div key={cat} className="row" style={{ padding: '6px 0' }}>
                    <span className="mono" style={{ fontSize: 12.5 }}>{cat}</span>
                    <span className="mono" style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>{v?.ms}ms · {v?.pct}%</span>
                  </div>
                ))}
                {!Object.keys(timing).length && <div className="empty">—</div>}
              </div>
            </div>
          </div>

          <div className="card" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <div style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>
              当前 traceId <span className="mono">{sel?.traceId || detail?.record?.traceId || '—'}</span> · 采样原因：{detail?.record?.samplingReason || '按策略配置'}
            </div>
            <div style={{ display: 'flex', gap: 8 }}>
              <button className="btn sm" disabled={replaying || selectedId == null} onClick={replay}>
                {replaying ? '回放中…' : '一键回放'}
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* 采样与留存策略 + 降级来源 */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16, marginTop: 16 }}>
        <div className="card">
          <div className="card-title"><span>采样与留存策略</span><span className="sub">GET /track/sampling-config</span></div>
          {[
            ['比例采样', sampling ? `${sampling.ratioSampling} · 按 traceId 哈希一致采样` : '—'],
            ['错误必采', sampling ? (sampling.errorAutoCapture ? '开启 · 降级 / 超时 / 异常' : '关闭') : '—'],
            ['慢请求阈值', sampling ? `${sampling.slowThresholdMs}ms · 超阈值强制采样` : '—'],
            ['留存 / 存储', sampling?.retention || '—']
          ].map(([k, v]) => (
            <div key={k} style={{ display: 'flex', justifyContent: 'space-between', padding: '6px 0', fontSize: 12.5, borderBottom: '1px solid var(--stroke-weak)' }}>
              <span style={{ color: 'var(--ink-2)' }}>{k}</span>
              <span>{v}</span>
            </div>
          ))}
        </div>
        <div className="card">
          <div className="card-title">
            <span>降级来源 Top</span>
            <span className="sub">{RANGE_OPTIONS.find(o => o.value === range)?.label} · {degradeSources.reduce((s, d) => s + (d.count || 0), 0)} 次</span>
          </div>
          {degradeSources.length ? degradeSources.map(d => (
            <div key={d.name} className="row" style={{ padding: '8px 0' }}>
              <span className="mono" style={{ fontSize: 12.5 }}>{d.name}</span>
              <span className="mono" style={{ fontSize: 12.5, color: 'var(--reject)', fontWeight: 600 }}>{d.count} 次</span>
            </div>
          )) : <div className="empty">暂无降级记录</div>}
        </div>
      </div>
    </div>
  );
}
