import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client.js';
import { StatusTag, Modal, foldLongValues, LongValueCell } from '../components/Common.jsx';
import FeatureCreate from './FeatureCreate.jsx';

const TYPES = ['全部', '基础特征', '衍生特征', '组合特征'];

const STATUS_MAP = {
  VERIFIED: { text: '验证通过', cls: 'pass' },
  WARNING: { text: '1条警告', cls: 'review' },
  ERROR: { text: '2处错误', cls: 'reject' },
  UNVERIFIED: { text: '未验证', cls: 'unconfig' }
};

const DIST_META = [
  { key: 'verified', label: '验证通过', color: 'var(--pass)' },
  { key: 'warning', label: '有警告', color: 'var(--review)' },
  { key: 'error', label: '有错误', color: 'var(--reject)' },
  { key: 'unverified', label: '未验证', color: 'var(--unconfig)' }
];

const num = (v) => (v == null ? 0 : Number(v));

export default function Features() {
  const navigate = useNavigate();
  const [features, setFeatures] = useState([]);
  const [stats, setStats] = useState(null);
  const [filter, setFilter] = useState('全部');
  const [keyword, setKeyword] = useState('');
  const [checked, setChecked] = useState([]);
  const [showCreate, setShowCreate] = useState(false);
  const [actMsg, setActMsg] = useState('');
  const [publishingId, setPublishingId] = useState(null);
  // 验证：对任意特征（含已发布上线）按已保存口径真实试跑一次，不落库、不改线上配置
  const [verifyFeature, setVerifyFeature] = useState(null);
  const [verifyViewMeta, setVerifyViewMeta] = useState(null);
  const [verifyInputs, setVerifyInputs] = useState([]);
  const [verifyWarnings, setVerifyWarnings] = useState([]);
  const [verifyValues, setVerifyValues] = useState({});
  const [verifyResult, setVerifyResult] = useState(null);
  const [verifying, setVerifying] = useState(false);
  const [verifyLoading, setVerifyLoading] = useState(false);
  const [verifyErr, setVerifyErr] = useState('');
  // 表达式回显默认折叠超长引用值（JSON 型出参会被整段内联，淹没表达式本身）
  const [showRawExpr, setShowRawExpr] = useState(false);
  const [offlineTarget, setOfflineTarget] = useState(null);
  const [offlining, setOfflining] = useState(false);
  const [offlineErr, setOfflineErr] = useState('');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);

  const reload = () => {
    api.features.list().then(setFeatures).catch(console.error);
    api.features.stats().then(setStats).catch(() => setStats(null));
  };

  useEffect(reload, []);

  const counts = {
    '全部': features.length,
    '基础特征': features.filter(f => f.featureType === 'BASE').length,
    '衍生特征': features.filter(f => f.featureType === 'DERIVED').length,
    '组合特征': features.filter(f => f.featureType === 'COMPOSITE').length
  };

  const filtered = features.filter(f => {
    if (filter === '基础特征' && f.featureType !== 'BASE') return false;
    if (filter === '衍生特征' && f.featureType !== 'DERIVED') return false;
    if (filter === '组合特征' && f.featureType !== 'COMPOSITE') return false;
    if (keyword) {
      const k = keyword.toLowerCase();
      return String(f.code).toLowerCase().includes(k) || String(f.name || '').toLowerCase().includes(k);
    }
    return true;
  });

  const totalPages = Math.max(1, Math.ceil(filtered.length / pageSize));
  const safePage = Math.min(page, totalPages);
  const paged = filtered.slice((safePage - 1) * pageSize, safePage * pageSize);

  const toggle = (id) => setChecked(c => c.includes(id) ? c.filter(x => x !== id) : [...c, id]);

  // 表头全选：只勾选当前页可见的特征，避免误选到看不见的行
  const pageIds = paged.map(f => f.id);
  const allChecked = pageIds.length > 0 && pageIds.every(id => checked.includes(id));
  const someChecked = pageIds.some(id => checked.includes(id));
  const toggleAll = () => setChecked(allChecked ? [] : pageIds);

  const batchVerify = async () => {
    if (!checked.length) return;
    setActMsg('');
    try {
      await api.features.batchVerify(checked);
      setChecked([]);
      reload();
      setActMsg(`已批量验证 ${checked.length} 个特征`);
    } catch (e) { setActMsg('批量验证失败：' + e.message); }
  };

  const handlePublish = async (id) => {
    setPublishingId(id);
    try {
      await api.features.publish(id);
      reload();
    } catch (e) { console.error(e); }
    finally { setPublishingId(null); }
  };

  const isBase = (f) => f?.featureType !== 'DERIVED';

  const closeVerify = () => {
    if (verifying) return;
    setVerifyFeature(null); setVerifyViewMeta(null); setVerifyInputs([]); setVerifyWarnings([]);
    setVerifyValues({}); setVerifyResult(null); setVerifyErr(''); setShowRawExpr(false);
  };

  // 打开验证：按已保存绑定解析出入参（含来源标注与建议测试值），预填后可执行
  const openVerify = async (f) => {
    setVerifyFeature(f);
    setVerifyViewMeta(null); setVerifyInputs([]); setVerifyWarnings([]); setVerifyValues({});
    setVerifyResult(null); setVerifyErr(''); setShowRawExpr(false);
    setVerifyLoading(true);
    try {
      const res = await api.features.resolvedInputs(f.id);
      const inputs = res?.inputs || [];
      setVerifyViewMeta(res?.view || null);
      setVerifyInputs(inputs);
      setVerifyWarnings(res?.warnings || []);
      const next = {};
      inputs.forEach(iv => { next[iv.name] = iv.sampleValue ?? iv.defaultValue ?? ''; });
      setVerifyValues(next);
    } catch (e) {
      setVerifyErr(e.message || '特征入参解析失败');
    } finally { setVerifyLoading(false); }
  };

  // 执行验证：基础特征真实跑绑定视图取数，衍生特征解析 @@ 引用并实测取值
  const runVerify = async () => {
    if (!verifyFeature) return;
    const missing = verifyInputs.filter(iv => iv.required && String(verifyValues[iv.name] ?? '').trim() === '');
    if (missing.length) {
      setVerifyResult({ success: false, error: `必填入参未提供测试值：${missing.map(m => m.name).join('、')}` });
      return;
    }
    setVerifying(true); setVerifyResult(null);
    try {
      const res = await api.features.trial(verifyFeature.id, verifyValues);
      setVerifyResult(res || { success: false, error: '验证未返回结果' });
      // 只回写「验证状态」这一项结论字段，不动口径；失败静默，不影响本次验证结果展示
      api.features.update(verifyFeature.id, { verifyStatus: res?.success ? 'VERIFIED' : 'ERROR' })
        .then(() => reload()).catch(() => {});
    } catch (e) {
      setVerifyResult({ success: false, error: e.message });
    } finally { setVerifying(false); }
  };

  // 下线 / 删除统一走 POST /features/{id}/offline（后端为逻辑下线）
  const confirmOffline = async () => {
    if (!offlineTarget) return;
    setOfflining(true); setOfflineErr('');
    try {
      await api.features.offline(offlineTarget.id);
      setOfflineTarget(null);
      reload();
    } catch (e) {
      setOfflineErr(e.message);
    } finally {
      setOfflining(false);
    }
  };

  const isOnline = (f) => f?.status === 'PUBLISHED';

  if (showCreate) return <FeatureCreate onBack={() => { setShowCreate(false); reload(); }} />;

  const dist = stats?.verifyDistribution || {};
  const distTotal = DIST_META.reduce((s, m) => s + num(dist[m.key]), 0) || 1;
  // 基础特征：绑定视图的原始验证返回（含出参定位、实际请求、响应预览）
  const vv = verifyResult?.viewValidation || null;
  const verifyExec = !!verifyResult && !verifyResult.simulated;

  return (
    <div>
      <div className="page-header">
        <h1>特征工作台</h1>
        <div className="sub">配置态 / 特征 · 共 {features.length} 个特征 · 入参出参全量登记</div>
      </div>

      {/* 过滤标签 */}
      <div className="filter-tabs">
        {TYPES.map(t => (
          <button key={t} className={`filter-tab ${filter === t ? 'active' : ''}`} onClick={() => { setFilter(t); setPage(1); }}>
            {t} <span className="count">{counts[t] || 0}</span>
          </button>
        ))}
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
          <input className="input" style={{ width: 200 }} placeholder="搜索特征名 / 出入参" value={keyword} onChange={e => setKeyword(e.target.value)} />
          <button className="btn primary" onClick={() => setShowCreate(true)}>+ 新建特征</button>
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 320px', gap: 16 }}>
        {/* 特征列表 */}
        <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
          <div className="table-wrap">
          <table className="table" style={{ minWidth: 980 }}>
            <thead>
              <tr>
                <th style={{ width: 44 }}>
                  <input
                    type="checkbox"
                    checked={allChecked}
                    ref={(el) => { if (el) el.indeterminate = !allChecked && someChecked; }}
                    onChange={toggleAll}
                    title={allChecked ? '取消全选本页' : '全选本页'}
                    aria-label="全选本页"
                  />
                </th>
                <th>特征名称 / 口径</th>
                <th>类型</th>
                <th>入参</th>
                <th>出参</th>
                <th>验证状态</th>
                <th>最近更新</th>
                <th style={{ width: 76 }}>状态</th>
                <th style={{ width: 250 }} className="col-actions">操作</th>
              </tr>
            </thead>
            <tbody>
              {paged.map(f => {
                return (
                  <tr key={f.id}>
                    <td><input type="checkbox" checked={checked.includes(f.id)} onChange={() => toggle(f.id)} /></td>
                    <td>
                      <div className="code-cell" style={{ color: '#241C15', fontWeight: 500 }}>{f.code}</div>
                      <div style={{ fontSize: 12.5, color: 'var(--ink-3)', marginTop: 2 }}>{f.businessDesc?.slice(0, 30) || '—'}</div>
                    </td>
                    <td>
                      <span className="tag tag-experiment" style={{ background: 'rgba(232,116,15,.10)', color: 'var(--feature)' }}>
                        {f.featureType === 'BASE' ? '基础' : f.featureType === 'DERIVED' ? '衍生' : '组合'} · {f.computeType || 'SQL'}
                      </span>
                    </td>
                    <td className="mono" style={{ fontSize: 12.5 }}>{f.inputCount || 0}</td>
                    <td className="mono" style={{ fontSize: 12.5 }}>{f.outputCount || 0}</td>
                    <td>
                      <span className={`badge badge-${STATUS_MAP[f.verifyStatus]?.cls || 'unconfig'}`}>
                        {STATUS_MAP[f.verifyStatus]?.text || '未验证'}
                      </span>
                    </td>
                    <td style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>{f.updatedAt?.slice(5, 16) || '—'}</td>
                    <td style={{ whiteSpace: 'nowrap' }}><StatusTag text={isOnline(f) ? '已上线' : '草稿'} /></td>
                    <td className="col-actions">
                      <div className="table-actions">
                        {!isOnline(f) && (
                          <>
                            <button className="btn sm primary" disabled={publishingId === f.id} onClick={() => handlePublish(f.id)}>
                              {publishingId === f.id ? '发布中…' : '发布'}
                            </button>
                            <button className="btn sm" title="编辑该特征" onClick={() => navigate(`/features/${f.id}/edit`)}>编辑</button>
                          </>
                        )}
                        {/* 已发布上线的特征不可编辑，但允许按线上口径真实试跑验证 */}
                        <button
                          className="btn sm"
                          title={isOnline(f) ? '已发布上线，仍可按线上口径真实试跑验证（不落库）' : '按当前口径真实试跑一次（不落库）'}
                          onClick={() => openVerify(f)}
                        >验证</button>
                        {/* 在线特征给「下线」入口，下线后列表不再展示 */}
                        <button
                          className={`btn sm ${isOnline(f) ? '' : 'danger'}`}
                          title={isOnline(f) ? '下线该在线特征' : '删除（逻辑下线）'}
                          onClick={() => { setOfflineErr(''); setOfflineTarget(f); }}
                        >
                          {isOnline(f) ? '下线' : '删除'}
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          </div>
          {!paged.length && (
            <div className="empty">
              <div className="empty-title">没有匹配的特征</div>
              <div className="empty-desc">换个关键词，或把类型筛选切回「全部」。</div>
              <div className="empty-act">
                <button className="btn" onClick={() => { setKeyword(''); setFilter('全部'); setPage(1); }}>清空筛选条件</button>
              </div>
            </div>
          )}
          <div className="table-foot">
            <span style={{ fontSize: 12.5, color: 'var(--ink-3)' }}>
              共 {filtered.length} 条 · 已选 {checked.length} 项{actMsg ? ` · ${actMsg}` : ''}
            </span>
            <div className="pager">
              <select
                className="select pager-size"
                value={pageSize}
                onChange={e => { setPageSize(Number(e.target.value)); setPage(1); }}
                aria-label="每页条数"
              >
                {[10, 20, 50].map(s => <option key={s} value={s}>{s} 条/页</option>)}
              </select>
              <button className="btn sm" style={{ minWidth: 34 }} disabled={safePage <= 1} onClick={() => setPage(p => p - 1)} aria-label="上一页">‹</button>
              <span className="mono pager-info">{safePage} / {totalPages}</span>
              <button className="btn sm" style={{ minWidth: 34 }} disabled={safePage >= totalPages} onClick={() => setPage(p => p + 1)} aria-label="下一页">›</button>
              <button className="btn primary" onClick={batchVerify} disabled={!checked.length}>
                批量验证{checked.length ? `（${checked.length}）` : ''}
              </button>
            </div>
          </div>
        </div>

        {/* 右侧面板：全部来自 GET /features/stats */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div className="card">
            <div className="card-title">
              <span>出入参登记</span>
              <span className="sub">覆盖率 {num(stats?.ioCoverage).toFixed(1)}%</span>
            </div>
            <div style={{ display: 'flex', gap: 12, marginBottom: 10 }}>
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: 22, fontWeight: 700, fontFamily: 'var(--font-mono)' }}>
                  {stats?.registeredOutputs ?? '—'}<span style={{ fontSize: 14, color: 'var(--ink-3)' }}> / {stats?.totalFeatures ?? '—'}</span>
                </div>
                <div style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>已登记出参 / 特征总数</div>
              </div>
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: 22, fontWeight: 700, fontFamily: 'var(--font-mono)' }}>{stats?.inputRefTotal ?? '—'}</div>
                <div style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>入参引用总数</div>
              </div>
            </div>
            <div className="progress"><div className="progress-bar" style={{ width: `${num(stats?.ioCoverage)}%` }} /></div>
            <div style={{ fontSize: 12.5, color: 'var(--ink-3)', marginTop: 6 }}>
              未验证 <span style={{ color: 'var(--reject)' }}>{stats?.unverifiedCount ?? 0} 个</span>
            </div>
          </div>

          <div className="card">
            <div className="card-title"><span>验证状态分布</span><span className="sub">GET /features/stats</span></div>
            {DIST_META.map(m => {
              const v = num(dist[m.key]);
              const pct = Math.round(v * 100 / distTotal);
              return (
                <div key={m.key} style={{ marginBottom: 8 }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12.5, marginBottom: 3 }}>
                    <span style={{ color: '#5C4A38' }}>{m.label}</span>
                    <span className="mono" style={{ color: m.color, fontWeight: 600 }}>{v}</span>
                  </div>
                  <div className="progress" style={{ height: 4 }}><div className="progress-bar" style={{ width: `${pct}%`, background: m.color }} /></div>
                </div>
              );
            })}
          </div>

          <div className="card">
            <div className="card-title"><span>入参引用热度 TOP 5</span><span className="sub">血缘统计</span></div>
            {(stats?.topInputRefs || []).map((item, i) => (
              <div key={item.name} className="row" style={{ padding: '8px 0' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <span className="mono" style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>{i + 1}</span>
                  <span className="mono" style={{ fontSize: 12.5 }}>{item.name}</span>
                </div>
                <span className="mono" style={{ fontSize: 12.5, color: 'var(--feature)', fontWeight: 600 }}>{item.count}</span>
              </div>
            ))}
            {!stats?.topInputRefs?.length && <div className="empty">暂无血缘统计</div>}
          </div>
        </div>
      </div>

      <Modal
        open={!!offlineTarget}
        title={isOnline(offlineTarget) ? '下线特征' : '删除特征'}
        sub="POST /features/{id}/offline · 逻辑下线，列表中将不再展示该特征"
        onClose={() => !offlining && setOfflineTarget(null)}
        width={480}
        footer={(
          <>
            <button className="btn" disabled={offlining} onClick={() => setOfflineTarget(null)}>取消</button>
            <button className="btn danger" disabled={offlining} onClick={confirmOffline}>{offlining ? '处理中…' : isOnline(offlineTarget) ? '确认下线' : '确认删除'}</button>
          </>
        )}
      >
        <div style={{ fontSize: 14, color: '#241C15', lineHeight: 1.7 }}>
          确认{isOnline(offlineTarget) ? '下线' : '删除'}特征 <b className="mono" style={{ color: 'var(--feature)' }}>{offlineTarget?.code}</b>（{offlineTarget?.name}）？
        </div>
        <div style={{ fontSize: 13.5, color: 'var(--ink-2)', marginTop: 10, lineHeight: 1.7, background: '#FFFCF8', border: '1px solid var(--stroke-weak)', borderRadius: 8, padding: '10px 12px' }}>
          {isOnline(offlineTarget)
            ? '下线后线上决策不再读取该特征；仍引用它的策略 / 交叉决策树在取数时将按其降级链取值（缺失策略 / 默认值）。'
            : '删除后该特征立即逻辑下线；仍引用它的策略 / 交叉决策树在取数时将按其降级链取值。该操作可由后端恢复。'}
        </div>
        {offlineErr && <div style={{ color: 'var(--reject)', fontSize: 13.5, marginTop: 10 }}>操作失败：{offlineErr}</div>}
      </Modal>

      {/* 验证弹窗：已发布上线的特征同样可试跑（只跑不落库、不改线上口径） */}
      <Modal
        open={!!verifyFeature}
        title="特征验证 · 真实试跑"
        sub={`按已保存口径执行一次取数（单次、不重试、不落库）· ${isBase(verifyFeature) ? '基础特征 · 视图真实执行' : '衍生特征 · 引用取值核对'}`}
        onClose={closeVerify}
        width={720}
        footer={(
          <>
            <button className="btn" disabled={verifying} onClick={closeVerify}>关闭</button>
            <button className="btn primary" disabled={verifying || verifyLoading || !!verifyErr} onClick={runVerify}>
              {verifying ? '执行中…' : verifyResult ? '重新执行' : '执行验证'}
            </button>
          </>
        )}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12, flexWrap: 'wrap' }}>
          <span className="mono" style={{ fontSize: 13.5, fontWeight: 600 }}>{verifyFeature?.code}</span>
          <span style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>{verifyFeature?.name || '—'}</span>
          <span className="tag" style={{ fontSize: 12, background: 'rgba(232,116,15,.10)', color: 'var(--feature)', border: 'none' }}>
            {isBase(verifyFeature) ? '基础' : '衍生'} · {verifyFeature?.computeType || 'SQL'}
          </span>
          {isOnline(verifyFeature) && <span className="tag tag-published" style={{ fontSize: 12 }}>已上线</span>}
          {verifyViewMeta?.code && (
            <span className="tag" style={{ fontSize: 12, background: 'rgba(217,132,32,.10)', color: 'var(--view)', border: 'none' }}>
              {verifyViewMeta.code}{verifyViewMeta.sourceType ? ` · ${verifyViewMeta.sourceType}` : ''}{verifyViewMeta.readMode ? ` · ${verifyViewMeta.readMode}` : ''}
            </span>
          )}
        </div>

        {verifyErr && (
          <div style={{ background: 'rgba(190,58,43,.08)', border: '1px solid rgba(190,58,43,.25)', borderRadius: 8, padding: '10px 12px', fontSize: 13.5, color: 'var(--reject)' }}>
            特征口径加载失败：{verifyErr}
          </div>
        )}
        {verifyLoading && !verifyErr && <div className="empty">特征口径加载中…</div>}

        {!verifyLoading && !verifyErr && (
          <>
            <div className="info-bar" style={{ fontSize: 12.5, alignItems: 'flex-start' }}>
              <span>
                {isOnline(verifyFeature) ? '该特征已发布上线，线上口径已定版。' : ''}
                本验证为只读试跑：不修改配置、不落库，执行结论只更新列表里的「验证状态」。
              </span>
            </div>

            {verifyWarnings.length > 0 && (
              <div style={{ background: 'rgba(217,132,32,.10)', border: '1px solid rgba(217,132,32,.35)', borderRadius: 8, padding: '8px 10px', fontSize: 12.5, color: 'var(--review)', marginTop: 10, lineHeight: 1.7 }}>
                {verifyWarnings.map((w, i) => <div key={i}>· {w}</div>)}
              </div>
            )}

            <div style={{ fontSize: 13.5, fontWeight: 600, color: '#5C4F41', margin: '14px 0 8px' }}>测试入参</div>
            {!verifyInputs.length ? (
              <div className="empty" style={{ padding: '12px 0' }}>该特征未登记入参，可直接验证</div>
            ) : (
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px 16px' }}>
                {verifyInputs.map(iv => (
                  <div className="field" key={iv.name} style={{ marginBottom: 0 }}>
                    <label style={{ fontSize: 12.5 }}>
                      <span className="mono">{iv.name}</span>
                      <span className="tag" style={{ marginLeft: 6, fontSize: 12 }}>{iv.type || 'STRING'}</span>
                      {iv.required && <span style={{ color: 'var(--reject)', marginLeft: 4 }}>*</span>}
                    </label>
                    <input
                      className="input mono"
                      style={{ fontSize: 13.5 }}
                      value={verifyValues[iv.name] ?? ''}
                      onChange={e => {
                        setVerifyValues(prev => ({ ...prev, [iv.name]: e.target.value }));
                        // 改过入参后清空上次结果，避免误读
                        if (verifyResult) setVerifyResult(null);
                      }}
                      placeholder="请输入测试值"
                    />
                    {iv.sourceLabel && (
                      <div className="hint" style={{ marginTop: 3 }}>
                        {iv.sourceLabel}{iv.sampleValue ? '（已带出默认值）' : ''}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}

            <div style={{ fontSize: 12.5, color: 'var(--ink-2)', marginTop: 10, lineHeight: 1.7 }}>
              {isBase(verifyFeature)
                ? '将用上面这组入参真实执行绑定视图的取数脚本（MySQL / HTTP / CSV / PMML 走各自适配器），只核对特征选定的出参。'
                : '将先解析全部 @@ 引用，再用同一组测试值跑每个被引用基础特征所在视图做只读取数核对；表达式本身由引擎在运行时执行。'}
            </div>

            <div className="card-title" style={{ marginTop: 14 }}>
              <span>验证结果</span>
              {verifyResult?.mode && <span className="sub">{verifyResult.mode === 'VIEW_EXEC' ? '视图真实执行' : '引用取值核对'}</span>}
            </div>
            {!verifyResult && <div className="empty">点击「执行验证」运行</div>}
            {verifyResult && (
              <>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10, flexWrap: 'wrap' }}>
                  <span className={`badge badge-${verifyResult.success ? 'pass' : 'reject'}`}>
                    {verifyResult.success ? '✓ 验证通过' : '✗ 验证未通过'}
                  </span>
                  {verifyResult.elapsedMs != null && <span className="mono" style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>{verifyResult.elapsedMs} ms</span>}
                  {vv?.httpStatus != null && <span className="mono" style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>HTTP {vv.httpStatus}</span>}
                  {verifyResult.viewCode && <span className="mono" style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>{verifyResult.viewCode}.{verifyResult.viewField}</span>}
                </div>

                {verifyResult.error && (
                  <div style={{ background: 'rgba(190,58,43,.08)', border: '1px solid rgba(190,58,43,.25)', borderRadius: 8, padding: '10px 12px', fontSize: 13.5, color: 'var(--reject)', lineHeight: 1.7, whiteSpace: 'pre-wrap', wordBreak: 'break-all', marginBottom: 10 }}>
                    {verifyResult.error}
                  </div>
                )}

                {verifyExec && (
                  <div style={{ overflowX: 'auto' }}>
                    <table className="table" style={{ tableLayout: 'fixed', minWidth: 560 }}>
                      <thead>
                        <tr>
                          <th style={{ width: '30%' }}>出参名</th>
                          <th style={{ width: '18%' }}>类型</th>
                          <th>返回值</th>
                          <th style={{ width: 90 }}>来源</th>
                        </tr>
                      </thead>
                      <tbody>
                        {(vv?.results || []).map((r, i) => (
                          <tr key={i}>
                            <td><span className="mono" style={{ fontSize: 13.5 }}>{r.fieldName}</span></td>
                            <td><span className="tag" style={{ fontSize: 12 }}>{r.fieldType || '-'}</span></td>
                            <td className="mono" style={{ fontSize: 13.5, wordBreak: 'break-all' }}>
                              <LongValueCell value={r.value} />
                              {r.jsonPath && <div style={{ fontSize: 12, color: 'var(--ink-3)', marginTop: 2 }}>{r.jsonPath}</div>}
                            </td>
                            <td>{r.defaultValueUsed
                              ? <span className="tag" style={{ fontSize: 12, background: 'rgba(217,132,32,.12)', color: 'var(--view)', border: 'none' }}>默认值</span>
                              : <span style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>实际取数</span>}</td>
                          </tr>
                        ))}
                        {!(vv?.results || []).length && Object.entries(verifyResult.outputs || {}).map(([k, v]) => (
                          <tr key={k}>
                            <td><span className="mono" style={{ fontSize: 13.5 }}>{k}</span></td>
                            <td><span className="tag" style={{ fontSize: 12 }}>{verifyFeature?.dataType || '-'}</span></td>
                            <td className="mono" style={{ fontSize: 13.5, wordBreak: 'break-all' }}>
                              <LongValueCell value={v} />
                            </td>
                            <td><span style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>实际取数</span></td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}

                {verifyResult.simulated && (
                  <>
                    {(verifyResult.refs || []).length > 0 && (
                      <>
                        <div style={{ fontSize: 12.5, fontWeight: 600, color: '#5C4F41', margin: '0 0 6px' }}>引用基础特征实测值</div>
                        <div style={{ overflowX: 'auto' }}>
                          <table className="table" style={{ minWidth: 520 }}>
                            <thead><tr><th>引用</th><th>实测值</th><th style={{ width: 150 }}>来源</th></tr></thead>
                            <tbody>
                              {(verifyResult.refs || []).map(r => (
                                <tr key={r.code}>
                                  <td className="mono" style={{ fontSize: 13.5 }}>@@{r.code}</td>
                                  <td className="mono" style={{ fontSize: 13.5, wordBreak: 'break-all' }}>
                                    <LongValueCell value={verifyResult.refValues?.[r.code]} />
                                  </td>
                                  <td className="mono" style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>{r.viewCode}.{r.viewField}</td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </>
                    )}
                    {verifyResult.exprPreview && (
                      <>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', margin: '12px 0 6px', gap: 8 }}>
                          <span style={{ fontSize: 12.5, fontWeight: 600, color: '#5C4F41' }}>
                            表达式回显（@@ 已替换为实测值）
                            {!showRawExpr && '· 超长引用值已折叠'}
                          </span>
                          <button className="btn sm" onClick={() => setShowRawExpr(v => !v)}>
                            {showRawExpr ? '折叠长引用值' : '显示原始全文'}
                          </button>
                        </div>
                        <pre className="code-block" style={{ margin: 0, maxHeight: 160, overflow: 'auto', fontSize: 12.5 }}>{showRawExpr ? verifyResult.exprPreview : foldLongValues(verifyResult.exprPreview, Object.values(verifyResult.refValues || {}))}</pre>
                      </>
                    )}
                    <div className="info-bar" style={{ fontSize: 12.5, marginTop: 10, alignItems: 'flex-start' }}>
                      表达式本身由引擎在运行时执行，此处仅核对 @@ 引用的取值是否可得。
                    </div>
                  </>
                )}

                {verifyExec && vv?.resolvedScript && (
                  <>
                    <div style={{ fontSize: 12.5, fontWeight: 600, color: '#5C4F41', margin: '12px 0 6px' }}>
                      {verifyViewMeta?.sourceType === 'HTTP' ? '实际发出的请求（凭据已掩码）' : '参数绑定后语句'}
                    </div>
                    <pre className="code-block" style={{ margin: 0, maxHeight: 160, overflow: 'auto', fontSize: 12.5 }}>{vv.resolvedScript}</pre>
                  </>
                )}
                {verifyExec && vv?.responsePreview && (
                  <>
                    <div style={{ fontSize: 12.5, fontWeight: 600, color: '#5C4F41', margin: '12px 0 6px' }}>响应体预览</div>
                    <pre className="code-block" style={{ margin: 0, maxHeight: 160, overflow: 'auto', fontSize: 12.5 }}>{vv.responsePreview}</pre>
                  </>
                )}

                {(verifyResult.warnings || []).length > 0 && (
                  <div style={{ background: 'rgba(217,132,32,.10)', border: '1px solid rgba(217,132,32,.35)', borderRadius: 8, padding: '8px 10px', fontSize: 12.5, color: 'var(--review)', marginTop: 10, lineHeight: 1.7 }}>
                    {(verifyResult.warnings || []).map((w, i) => <div key={i}>· {w}</div>)}
                  </div>
                )}
              </>
            )}
          </>
        )}
      </Modal>
    </div>
  );
}
