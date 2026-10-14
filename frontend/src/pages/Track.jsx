import React, { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '../api/client.js';
import { ConclusionBadge } from '../components/Common.jsx';
import TrackCanvas, { analyzeRoute } from '../components/TrackCanvas.jsx';

const RANGE_OPTIONS = [
  { value: '1h', label: '近 1 小时' },
  { value: '6h', label: '近 6 小时' },
  { value: '24h', label: '近 24 小时' }
];

const ROUTE_CHIP = {
  display: 'inline-flex', alignItems: 'center', gap: 4, padding: '2px 8px', borderRadius: 999,
  border: '1px solid #F0CDA9', background: '#FFF6EC', fontFamily: 'var(--font-mono)', fontSize: 11.5
};
const FEAT_CHIP = {
  display: 'inline-flex', alignItems: 'center', gap: 4, padding: '2px 8px', borderRadius: 999,
  border: '1px solid var(--stroke-weak)', background: '#F6F1E9', fontFamily: 'var(--font-mono)',
  fontSize: 11.5, color: 'var(--ink-2)'
};

export default function Track() {
  // 试算中心「在决策日志中查看」带 ?traceId=xxx 跳过来：直接预填检索框，进入即可看到那条链路
  const [searchParams] = useSearchParams();
  const [range, setRange] = useState('1h');
  const [stats, setStats] = useState(null);
  const [records, setRecords] = useState([]);
  const [selectedId, setSelectedId] = useState(null);
  const [detail, setDetail] = useState(null);
  const [canvas, setCanvas] = useState(null);
  const [canvasErr, setCanvasErr] = useState('');
  const [keyword, setKeyword] = useState(() => searchParams.get('traceId') || '');
  const [conclusion, setConclusion] = useState('');
  const [err, setErr] = useState('');
  const [replaying, setReplaying] = useState(false);

  // 统计条：GET /track/stats
  useEffect(() => {
    api.track.stats(range).then(setStats).catch(() => setStats(null));
  }, [range]);

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

  // 对应策略画布：GET /track/{id}/canvas（只读；按该 trace 的策略 + 版本解析节点与连线）
  useEffect(() => {
    if (selectedId == null) { setCanvas(null); setCanvasErr(''); return () => {}; }
    let alive = true;
    setCanvas(null); setCanvasErr('');
    api.track.canvas(selectedId)
      .then(g => { if (alive) setCanvas(g); })
      .catch(e => { if (alive) setCanvasErr(e.message || '加载策略画布失败'); });
    return () => { alive = false; };
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

  const steps = detail?.steps || [];
  const route = analyzeRoute(steps);
  const canvasKeys = new Set((canvas?.nodes || []).map(n => n.nodeKey));
  const offCanvas = canvas?.nodes?.length
    ? [...route.execIds].filter(id => !canvasKeys.has(id))
    : [];

  return (
    <div>
      <div className="page-header">
        <div>
          <h1>决策追踪 · Trace</h1>
          <div className="sub">运行态 / 决策日志与回放 / 单笔 Trace 详情</div>
        </div>
        <div className="actions" style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>
          共 {records.length} 条日志 · 点击任意一行查看完整链路与画布
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

          {/* 策略画布：该 trace 实际运行的那张画布，走过的节点与连线高亮 */}
          <div className="card">
            <div className="card-title">
              <span>策略画布 · 运行路线高亮</span>
              <span className="sub">
                {canvas
                  ? `${canvas.strategyName || `策略 #${canvas.strategyId}`} · 画布 ${canvas.versionNo || '—'} · ${(canvas.nodes || []).length} 节点 / ${(canvas.edges || []).length} 连线`
                  : 'GET /track/{id}/canvas'}
              </span>
            </div>
            {canvasErr && (
              <div className="warn-bar" style={{ marginBottom: 10, justifyContent: 'flex-start' }}>画布加载失败：{canvasErr}</div>
            )}
            {canvas?.source === 'FALLBACK' && canvas.note && (
              <div className="warn-bar" style={{ marginBottom: 10, justifyContent: 'flex-start' }}>{canvas.note}</div>
            )}
            {canvas && (canvas.nodes || []).length ? (
              <>
                <TrackCanvas graph={canvas} steps={steps} />
                <div style={{ marginTop: 8, display: 'flex', gap: 14, flexWrap: 'wrap', fontSize: 12, color: 'var(--ink-2)' }}>
                  <span><b style={{ color: 'var(--decision)' }}>橙框 + 毫秒徽标</b> = 本笔实际执行</span>
                  <span>灰化节点 = 未走到</span>
                  <span><b style={{ color: 'var(--decision)' }}>橙色连线</b> = 实际走向（虚线为 else 出口）</span>
                </div>
                <div style={{ marginTop: 10, display: 'flex', flexDirection: 'column', gap: 6 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap', fontSize: 12.5 }}>
                    <span style={{ color: 'var(--ink-2)', flex: 'none' }}>运行路线</span>
                    {route.nodeSteps.length ? route.nodeSteps.map((s, i) => (
                      <React.Fragment key={`${s.nodeId}-${i}`}>
                        {i > 0 && <span style={{ color: 'var(--ink-3)' }}>→</span>}
                        <span style={ROUTE_CHIP}>
                          {s.nodeId}
                          <span style={{ color: 'var(--ink-3)' }}>{s.elapsedMs}ms</span>
                        </span>
                      </React.Fragment>
                    )) : <span style={{ color: 'var(--ink-3)' }}>暂无执行节点记录</span>}
                  </div>
                  {route.featSteps.length > 0 && (
                    <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap', fontSize: 12.5 }}>
                      <span style={{ color: 'var(--ink-2)', flex: 'none' }}>特征执行</span>
                      {route.featSteps.map((s, i) => (
                        <span key={i} style={FEAT_CHIP}>
                          {String(s.stepTitle || '').replace(/^特征·/, '')} · {s.elapsedMs}ms
                        </span>
                      ))}
                    </div>
                  )}
                  {offCanvas.length > 0 && (
                    <div style={{ fontSize: 12, color: 'var(--degrade)' }}>
                      另有 {offCanvas.length} 个执行节点不在当前画布上（{offCanvas.join('、')}），可能是历史版本口径。
                    </div>
                  )}
                </div>
              </>
            ) : (
              <div className="empty" style={{ padding: '28px 12px' }}>
                {canvasErr ? '画布暂不可用'
                  : selectedId == null ? '选择左侧记录查看对应策略画布'
                    : canvas ? '该策略暂无可展示的画布内容' : '正在加载画布…'}
              </div>
            )}
          </div>

          <div className="card" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <div style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>
              当前 traceId <span className="mono">{detail?.record?.traceId || '—'}</span> · 采样原因：{detail?.record?.samplingReason || '按策略配置'}
            </div>
            <div style={{ display: 'flex', gap: 8 }}>
              <button className="btn sm" disabled={replaying || selectedId == null} onClick={replay}>
                {replaying ? '回放中…' : '一键回放'}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
