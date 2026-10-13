import React, { useEffect, useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { api } from '../api/client.js';
import { StatusTag, Modal } from '../components/Common.jsx';

const TYPES = ['全部', 'MySQL', 'HTTP', 'CSV', 'PMML'];

export default function Views() {
  const navigate = useNavigate();
  const location = useLocation();
  const [views, setViews] = useState([]);
  const [selected, setSelected] = useState(null);
  const [detail, setDetail] = useState(null);
  const [lineage, setLineage] = useState(null);
  const [degrade, setDegrade] = useState(null);
  const [health, setHealth] = useState([]);
  const [filter, setFilter] = useState('全部');
  const [keyword, setKeyword] = useState('');

  const [deleteTarget, setDeleteTarget] = useState(null);
  const [deleting, setDeleting] = useState(false);
  const [deleteErr, setDeleteErr] = useState('');
  const [publishingId, setPublishingId] = useState(null);
  // 点击「已发布上线」视图的编辑按钮时给出明确原因，而不是静默无反应
  const [editBlocked, setEditBlocked] = useState(null);
  // 验证：对任意视图（含已发布上线）按已保存配置真实试跑一次取数，不落库
  const [verifyView, setVerifyView] = useState(null);
  const [verifyDetail, setVerifyDetail] = useState(null);
  const [verifyErr, setVerifyErr] = useState('');
  const [verifyInputs, setVerifyInputs] = useState({});
  const [verifyResult, setVerifyResult] = useState(null);
  const [verifying, setVerifying] = useState(false);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(10);

  const loadList = () => api.views.list().then(setViews).catch(console.error);

  useEffect(() => {
    api.views.list().then(list => {
      setViews(list);
      // 从新建/编辑页跳转回来时，自动选中刚操作的视图
      const focusId = location.state?.createdViewId || location.state?.updatedViewId;
      if (focusId) {
        const target = list.find(v => v.id === focusId);
        if (target) setSelected(target);
        navigate(location.pathname, { replace: true, state: null });
      }
    }).catch(console.error);
    // 数据源健康度：GET /views/datasource-health
    api.views.datasourceHealth('1h').then(setHealth).catch(() => setHealth([]));
  }, []);

  useEffect(() => {
    if (!selected) { setDetail(null); setLineage(null); setDegrade(null); return; }
    api.views.detail(selected.id).then(setDetail).catch(console.error);
    api.views.lineage(selected.id).then(setLineage).catch(() => setLineage(null));
    api.views.degradeConfig(selected.id).then(setDegrade).catch(() => setDegrade(null));
  }, [selected]);

  // 下线 / 删除统一走 POST /views/{id}/offline（后端为逻辑下线）
  const confirmOffline = async () => {
    if (!deleteTarget) return;
    setDeleting(true); setDeleteErr('');
    try {
      await api.views.offline(deleteTarget.id);
      if (selected?.id === deleteTarget.id) { setSelected(null); setDetail(null); }
      setDeleteTarget(null);
      await loadList();
    } catch (e) {
      setDeleteErr(e.message);
    } finally {
      setDeleting(false);
    }
  };

  const handlePublish = async (id) => {
    setPublishingId(id);
    try {
      await api.views.publish(id);
      await loadList();
      if (selected?.id === id) {
        api.views.detail(id).then(setDetail).catch(console.error);
      }
    } catch (e) { console.error(e); }
    finally { setPublishingId(null); }
  };

  const openVerify = async (v) => {
    setVerifyView(v);
    setVerifyDetail(null);
    setVerifyErr('');
    setVerifyResult(null);
    setVerifyInputs({});
    try {
      const d = await api.views.detail(v.id);
      setVerifyDetail(d);
      const next = {};
      (d?.inputs || []).forEach(iv => { next[iv.varCode] = iv.defaultValue ?? ''; });
      setVerifyInputs(next);
    } catch (e) {
      setVerifyErr(e.message || '视图详情加载失败');
    }
  };

  const runVerify = async () => {
    if (!verifyDetail) return;
    const inputs = verifyDetail.inputs || [];
    const missing = inputs.find(iv => (iv.required == null || iv.required)
      && String(verifyInputs[iv.varCode] ?? '').trim() === '');
    if (missing) { setVerifyResult({ success: false, error: `必填入参 ${missing.varCode} 未提供测试值` }); return; }
    setVerifying(true);
    setVerifyResult(null);
    try {
      const res = await api.views.validate({
        viewId: verifyView.id,
        sourceType: verifyDetail.view.sourceType,
        readMode: verifyDetail.view.readMode,
        scriptContent: verifyDetail.view.scriptContent,
        inputs: inputs.map(iv => ({
          varCode: iv.varCode,
          varType: iv.varType,
          required: iv.required == null || iv.required,
          value: String(verifyInputs[iv.varCode] ?? '') === '' ? null : verifyInputs[iv.varCode]
        })),
        outputs: (verifyDetail.fields || []).map((f, i) => ({
          fieldName: f.fieldName,
          fieldType: f.fieldType,
          defaultValue: f.defaultValue == null || f.defaultValue === '' ? null : f.defaultValue,
          sort: i
        }))
      });
      setVerifyResult({
        success: res?.success ?? true,
        elapsedMs: res?.elapsedMs,
        httpStatus: res?.httpStatus,
        results: res?.results || [],
        resolvedScript: res?.resolvedScript || '',
        responsePreview: res?.responsePreview || '',
        warnings: res?.warnings || [],
        error: res?.success === false ? (res.error || '验证未通过') : ''
      });
    } catch (e) {
      setVerifyResult({ success: false, error: e.message });
    } finally { setVerifying(false); }
  };

  const counts = TYPES.reduce((acc, t) => {
    acc[t] = t === '全部' ? views.length : views.filter(v => v.sourceType === t).length;
    return acc;
  }, {});

  const kw = keyword.trim().toLowerCase();
  const filtered = views.filter(v => {
    if (filter !== '全部' && v.sourceType !== filter) return false;
    if (!kw) return true;
    return [v.code, v.name, v.owner, v.businessDomain, v.sourceType, v.status]
      .filter(Boolean).some(x => String(x).toLowerCase().includes(kw));
  });
  const totalPages = Math.max(1, Math.ceil(filtered.length / pageSize));
  const safePage = Math.min(page, totalPages);
  const paged = filtered.slice((safePage - 1) * pageSize, safePage * pageSize);
  const sel = views.find(v => v.id === selected?.id);
  const fields = detail?.fields || [];
  const isOnline = (v) => v?.status === 'PUBLISHED';

  return (
    <div>
      <div className="page-header">
        <h1>视图管理</h1>
        <div className="sub">配置态 / 视图 · 视图管理 · 全部 {views.length} 个视图</div>
      </div>

      <div className="filter-tabs">
        {TYPES.map(t => (
          <button key={t} className={`filter-tab ${filter === t ? 'active' : ''}`} onClick={() => { setFilter(t); setPage(1); }}>
            {t} <span className="count">{counts[t] || 0}</span>
          </button>
        ))}
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 8, alignItems: 'center' }}>
          <div style={{ position: 'relative' }}>
            <input
              className="input"
              style={{ width: 220, paddingRight: keyword ? 34 : undefined }}
              placeholder="搜索视图名 / 编码 / 责任人"
              value={keyword}
              onChange={e => { setKeyword(e.target.value); setPage(1); }}
            />
            {keyword && (
              <button
                onClick={() => { setKeyword(''); setPage(1); }}
                title="清空搜索"
                aria-label="清空搜索"
                style={{
                  position: 'absolute', right: 4, top: '50%', transform: 'translateY(-50%)',
                  width: 26, height: 26, border: 'none', background: 'transparent', borderRadius: 6,
                  cursor: 'pointer', color: 'var(--ink-3)', fontSize: 16, lineHeight: 1
                }}
              >×</button>
            )}
          </div>
          <button className="btn primary" onClick={() => navigate('/views/new')}>+ 新建视图</button>
        </div>
      </div>

      {editBlocked && (
        <div className="warn-bar" style={{ marginBottom: 12, justifyContent: 'space-between', alignItems: 'center' }}>
          <span>
            <b style={{ color: 'var(--reject)' }}>不可编辑</b>
            　视图 <span className="mono">{editBlocked.code}</span> 已发布上线，线上口径已定版。
            如需核对取数，可在本列表点「验证」按线上配置真实试跑；如需修改，请先点「下线」把视图转为草稿，编辑后再重新发布。
          </span>
          <button className="btn sm" onClick={() => setEditBlocked(null)}>知道了</button>
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: sel ? '1fr 340px' : '1fr', gap: 16 }}>
        <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
          <div className="card-head">
            <b>视图列表 · {filtered.length} 个</b>
            <span className="sub" style={{ fontSize: 12.5, color: 'var(--ink-3)' }}>点击任意一行查看字段与血缘</span>
          </div>
          <div className="table-wrap">
          <table className="table" style={{ minWidth: 900 }}>
            <thead>
              <tr><th>视图名称 / 口径</th><th>类型</th><th>字段</th><th>被引用</th><th>接成功率</th><th>状态</th><th style={{ width: 250 }} className="col-actions">操作</th></tr>
            </thead>
            <tbody>
              {paged.map(v => (
                <tr
                  key={v.id}
                  onClick={() => setSelected(v)}
                  className={'clickable' + (sel?.id === v.id ? ' is-selected' : '')}
                >
                  <td>
                    <div className="code-cell" style={{ color: '#241C15' }}>{v.code}</div>
                    <div style={{ fontSize: 12.5, color: 'var(--ink-3)', marginTop: 2 }}>{v.name}· 主理 {v.owner}</div>
                  </td>
                  <td><span className="tag" style={{ background: 'rgba(217,132,32,.10)', color: 'var(--view)', border: 'none' }}>{v.sourceType} · {v.readMode}</span></td>
                  <td className="mono" style={{ fontSize: 12.5 }}>{v.fieldCount} 字段</td>
                  <td className="mono" style={{ fontSize: 12.5 }}>引用 {v.refCount} 处</td>
                  <td className="mono" style={{ fontSize: 12.5, color: (v.successRate || 0) < 95 ? 'var(--reject)' : 'var(--pass)' }}>{(v.successRate || 99).toFixed(1)}%</td>
                  <td><StatusTag text={isOnline(v) ? '在线' : v.status === 'CIRCUIT_BREAKER' ? '降级中' : '草稿'} /></td>
                  <td onClick={(e) => e.stopPropagation()} className="col-actions">
                    <div className="table-actions">
                      {!isOnline(v) && (
                        <button className="btn sm primary" disabled={publishingId === v.id} onClick={() => handlePublish(v.id)}>
                          {publishingId === v.id ? '发布中…' : '发布'}
                        </button>
                      )}
                      <button
                        className="btn sm"
                        title={isOnline(v) ? '已发布上线，口径已定版不可编辑' : '编辑该视图'}
                        onClick={() => {
                          // 发布态视图的线上口径不可再改动，需先下线；这里给出明确引导
                          if (isOnline(v)) { setEditBlocked(v); return; }
                          navigate(`/views/${v.id}/edit`);
                        }}
                      >编辑</button>
                      {/* 已发布上线的视图不可编辑，但允许按已保存配置真实试跑验证 */}
                      <button
                        className="btn sm"
                        title="按已保存配置真实试跑一次取数（不落库）"
                        onClick={() => openVerify(v)}
                      >验证</button>
                      {/* 在线视图给「下线」入口，下线后才允许删除，避免永远删不掉 */}
                      <button
                        className={`btn sm ${isOnline(v) ? '' : 'danger'}`}
                        title={isOnline(v) ? '下线该在线视图' : '删除（逻辑下线）'}
                        onClick={() => { setDeleteErr(''); setDeleteTarget(v); }}
                      >
                        {isOnline(v) ? '下线' : '删除'}
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!paged.length && (
            <div className="empty">
              <div className="empty-title">没有匹配的视图</div>
              <div className="empty-desc">换个关键词，或把类型筛选切回「全部」。</div>
              <div className="empty-act">
                <button className="btn" onClick={() => { setKeyword(''); setFilter('全部'); setPage(1); }}>清空筛选条件</button>
              </div>
            </div>
          )}
          </div>
          <div className="table-foot">
            <span style={{ fontSize: 12.5, color: 'var(--ink-3)' }}>共 {filtered.length} 条 · 第 {(safePage - 1) * pageSize + 1}–{Math.min(safePage * pageSize, filtered.length)} 条</span>
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
            </div>
          </div>
        </div>

        {sel && detail && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            <div className="card">
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <b className="mono" style={{ fontSize: 15 }}>{sel.code}</b>
                <StatusTag text={isOnline(sel) ? '在线' : sel.status === 'CIRCUIT_BREAKER' ? '降级中' : '草稿'} />
              </div>
              <div style={{ fontSize: 12.5, color: 'var(--ink-2)', marginTop: 4 }}>{sel.name}</div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, marginTop: 12, fontSize: 12.5 }}>
                {[
                  ['视图类型', `${sel.sourceType} · ${sel.readMode || '-'} · ${sel.version || 'v1'}`],
                  ['业务域', sel.businessDomain || '—'],
                  ['责任人', sel.owner || '王敏·风控策略组'],
                  ['成功率', `${Number(sel.successRate || 0).toFixed(1)}%`],
                  ['平均耗时', sel.latencyMs != null ? `${sel.latencyMs}ms` : '—'],
                  ['PII 字段', `${sel.piiCount ?? 0} 个`]
                ].map(([k, v]) => (
                  <div key={k} style={{ padding: '4px 0' }}>
                    <div style={{ color: 'var(--ink-2)' }}>{k}</div>
                    <div className="mono" style={{ fontSize: 12.5 }}>{v}</div>
                  </div>
                ))}
              </div>
            </div>

            <div className="card">
              <div className="card-title"><span>字段清单</span><span className="sub">{fields.length} 个字段</span></div>
              {fields.length ? fields.slice(0, 6).map(f => (
                <div key={f.id} className="row" style={{ padding: '8px 0' }}>
                  <div>
                    <span className="mono" style={{ fontSize: 12.5, fontWeight: 500 }}>{f.fieldName}</span>
                    <span className="tag" style={{ marginLeft: 8, fontSize: 12 }}>{f.fieldType}</span>
                    {f.piiLevel && f.piiLevel !== 'NONE' && (
                      <span className="tag" style={{ marginLeft: 4, fontSize: 12, color: 'var(--reject)' }}>PII {f.piiLevel}</span>
                    )}
                  </div>
                  <span style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>{f.description || '—'}</span>
                </div>
              )) : <div className="empty">该视图尚未登记字段</div>}
              <div style={{ fontSize: 12.5, color: 'var(--ink-2)', marginTop: 8 }}>
                {fields.length > 6 ? `仅显示前 6 / ${fields.length} 个字段 · ` : ''}字段脱敏与敏感等级在字段详情中声明
              </div>
            </div>

            <div className="card">
              <div className="card-title">
                <span>血缘影响面</span>
                <span className="sub" style={{ color: 'var(--reject)' }}>被 {lineage?.totalRefs ?? sel.refCount ?? 0} 处引用</span>
              </div>
              <div style={{ display: 'flex', gap: 12, marginBottom: 10, fontSize: 12.5 }}>
                <span>基础特征 <b style={{ color: 'var(--view)' }}>{lineage?.baseFeatures ?? 0} 个</b></span>
                <span>衍生特征 <b style={{ color: 'var(--feature)' }}>{lineage?.derivedFeatures ?? 0} 个</b></span>
                <span>决策策略 <b style={{ color: 'var(--decision)' }}>{lineage?.strategies ?? 0} 个</b></span>
              </div>
              <div className="code-block" style={{ fontSize: 12 }}>{lineage?.tree || '暂无血缘数据'}</div>
            </div>

            <div className="card">
              <div className="card-title"><span>降级与缓存</span><span className="sub">GET /views/{'{id}'}/degrade-config</span></div>
              {[
                ['降级模式', degrade?.degradeMode || '—'],
                ['降级后', degrade?.onFallback || '—'],
                ['缓存', degrade ? `L1 ${degrade.cacheL1} · L2 ${degrade.cacheL2}` : '—'],
                ['熔断', degrade?.circuitBreaker
                  ? `${degrade.circuitBreaker.enabled ? '开启' : '关闭'} · 错误率 ${degrade.circuitBreaker.errorRate ?? 0}%`
                  : '—']
              ].map(([k, v]) => (
                <div key={k} style={{ display: 'flex', justifyContent: 'space-between', padding: '6px 0', fontSize: 12.5, borderBottom: '1px solid var(--stroke-weak)' }}>
                  <span style={{ color: 'var(--ink-2)' }}>{k}</span>
                  <span className="mono">{v}</span>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* 数据源健康度：GET /views/datasource-health */}
      <div className="card" style={{ marginTop: 16 }}>
        <div className="card-title"><span>数据源健康度</span><span className="sub">近 1 小时 · {health.length} 个数据源</span></div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 12 }}>
          {health.length ? health.map(ds => (
            <div key={ds.name} style={{ padding: 12, background: '#FFFCF8', borderRadius: 8, border: '1px solid var(--stroke-weak)' }}>
              <div style={{ fontSize: 13.5, fontWeight: 500, marginBottom: 6 }}>{ds.name}</div>
              <div style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>池使用率 {ds.poolUsage} · 平均 {ds.avgLatencyMs}ms</div>
              <div style={{ marginTop: 6 }}><StatusTag text={ds.status} /></div>
            </div>
          )) : <div className="empty">暂无健康度数据</div>}
        </div>
      </div>

      <Modal
        open={!!deleteTarget}
        title={isOnline(deleteTarget) ? '下线视图' : '删除视图'}
        sub="POST /views/{id}/offline · 逻辑下线，列表中将不再展示该视图"
        onClose={() => !deleting && setDeleteTarget(null)}
        width={480}
        footer={(
          <>
            <button className="btn" disabled={deleting} onClick={() => setDeleteTarget(null)}>取消</button>
            <button className="btn danger" disabled={deleting} onClick={confirmOffline}>{deleting ? '处理中…' : isOnline(deleteTarget) ? '确认下线' : '确认删除'}</button>
          </>
        )}
      >
        <div style={{ fontSize: 14, color: '#241C15', lineHeight: 1.7 }}>
          确认{isOnline(deleteTarget) ? '下线' : '删除'}视图 <b className="mono" style={{ color: 'var(--decision)' }}>{deleteTarget?.code}</b>（{deleteTarget?.name}）？
        </div>
        <div style={{ fontSize: 13.5, color: 'var(--ink-2)', marginTop: 10, lineHeight: 1.7, background: '#FFFCF8', border: '1px solid var(--stroke-weak)', borderRadius: 8, padding: '10px 12px' }}>
          {isOnline(deleteTarget)
            ? '下线后线上流量不再读取该视图；仍引用它的特征/策略在取数时将走视图配置的降级链（默认值或缓存）。'
            : '删除后该视图立即逻辑下线；仍引用它的特征/策略在取数时将走视图配置的降级链（默认值或缓存）。该操作可由后端恢复。'}
        </div>
        {deleteErr && <div style={{ color: 'var(--reject)', fontSize: 13.5, marginTop: 10 }}>操作失败：{deleteErr}</div>}
      </Modal>

      {/* 验证弹窗：已发布上线的视图同样可试跑（只跑不落库） */}
      <Modal
        open={!!verifyView}
        title="视图验证 · 真实试跑"
        sub={`按已保存配置执行一次取数（单次、不重试、不落库）· ${verifyDetail?.view?.sourceType || verifyView?.sourceType || ''} · ${verifyDetail?.view?.readMode || verifyView?.readMode || ''}`}
        onClose={() => !verifying && setVerifyView(null)}
        width={680}
        footer={(
          <>
            <button className="btn" disabled={verifying} onClick={() => setVerifyView(null)}>关闭</button>
            <button className="btn primary" disabled={verifying || !verifyDetail} onClick={runVerify}>
              {verifying ? '执行中…' : verifyResult ? '重新执行' : '执行验证'}
            </button>
          </>
        )}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
          <span className="mono" style={{ fontSize: 13.5, fontWeight: 600 }}>{verifyView?.code}</span>
          <span style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>{verifyView?.name}</span>
          {isOnline(verifyView) && <span className="tag tag-published" style={{ fontSize: 12 }}>已上线</span>}
        </div>

        {verifyErr && (
          <div style={{ background: 'rgba(190,58,43,.08)', border: '1px solid rgba(190,58,43,.25)', borderRadius: 8, padding: '10px 12px', fontSize: 13.5, color: 'var(--reject)' }}>
            视图配置加载失败：{verifyErr}
          </div>
        )}
        {!verifyDetail && !verifyErr && <div className="empty">视图配置加载中…</div>}

        {verifyDetail && (
          <>
            <div style={{ fontSize: 13.5, fontWeight: 600, color: '#5C4F41', marginBottom: 8 }}>
              测试入参{verifyDetail.view?.sourceType === 'CSV' ? '（作为过滤条件）' : ''}
            </div>
            {(verifyDetail.inputs || []).length === 0 ? (
              <div className="empty" style={{ padding: '12px 0' }}>
                {verifyDetail.view?.sourceType === 'CSV'
                  ? '未配置过滤入参：将直接读取文件首行数据'
                  : verifyDetail.view?.sourceType === 'PMML'
                    ? '未识别到模型入参（$变量）：仅解析核对出参'
                    : '该视图未登记入参，可直接验证'}
              </div>
            ) : (
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px 16px', marginBottom: 4 }}>
                {(verifyDetail.inputs || []).map(iv => (
                  <div className="field" key={iv.varCode} style={{ marginBottom: 0 }}>
                    <label style={{ fontSize: 12.5 }}>
                      <span className="mono">{iv.varCode}</span>
                      <span className="tag" style={{ marginLeft: 6, fontSize: 12 }}>{iv.varType || 'STRING'}</span>
                      {(iv.required == null || iv.required) && <span style={{ color: 'var(--reject)', marginLeft: 4 }}>*</span>}
                    </label>
                    <input
                      className="input mono"
                      style={{ fontSize: 13.5 }}
                      value={verifyInputs[iv.varCode] ?? ''}
                      onChange={e => {
                        setVerifyInputs(prev => ({ ...prev, [iv.varCode]: e.target.value }));
                        // 改过入参后清空上次结果，避免误读
                        if (verifyResult) setVerifyResult(null);
                      }}
                      placeholder="请输入测试值"
                    />
                  </div>
                ))}
              </div>
            )}
            {verifyDetail.view?.sourceType === 'CSV' && (verifyDetail.inputs || []).length > 0 && (
              <div className="info-bar" style={{ fontSize: 12.5, marginTop: 4 }}>
                仅返回与全部入参值一致的数据行；出参取首个匹配行，未匹配时按空值 / 默认值处理。
              </div>
            )}

            <div className="card-title" style={{ marginTop: 14 }}><span>验证结果</span></div>
            {!verifyResult && <div className="empty">点击「执行验证」运行</div>}
            {verifyResult && verifyResult.success && (
              <>
                <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
                  <span className="badge badge-pass">✓ 验证通过</span>
                  {verifyResult.elapsedMs != null && <span className="mono" style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>{verifyResult.elapsedMs} ms</span>}
                  {verifyResult.httpStatus != null && <span className="mono" style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>HTTP {verifyResult.httpStatus}</span>}
                </div>
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
                      {(verifyResult.results || []).map((r, i) => (
                        <tr key={i}>
                          <td><span className="mono" style={{ fontSize: 13.5 }}>{r.fieldName}</span></td>
                          <td><span className="tag" style={{ fontSize: 12 }}>{r.fieldType || '-'}</span></td>
                          <td className="mono" style={{ fontSize: 13.5, wordBreak: 'break-all' }}>
                            {r.value === null || r.value === undefined || r.value === '' ? <span style={{ color: '#B6A997' }}>NULL</span> : String(r.value)}
                            {r.jsonPath && <div style={{ fontSize: 12, color: 'var(--ink-3)', marginTop: 2 }}>{r.jsonPath}</div>}
                          </td>
                          <td>{r.defaultValueUsed ? <span className="tag" style={{ fontSize: 12, background: 'rgba(217,132,32,.12)', color: 'var(--view)', border: 'none' }}>默认值</span> : <span style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>实际取数</span>}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {(verifyResult.warnings || []).length > 0 && (
                  <div style={{ background: 'rgba(217,132,32,.10)', border: '1px solid rgba(217,132,32,.35)', borderRadius: 8, padding: '8px 10px', fontSize: 12.5, color: 'var(--review)', marginTop: 10, lineHeight: 1.7 }}>
                    {(verifyResult.warnings || []).map((w, i) => <div key={i}>· {w}</div>)}
                  </div>
                )}
                {verifyResult.resolvedScript && (
                  <>
                    <div style={{ fontSize: 12.5, fontWeight: 600, color: '#5C4F41', margin: '12px 0 6px' }}>
                      {verifyDetail.view?.sourceType === 'HTTP' ? '实际发出的请求（凭据已掩码）' : '参数绑定后语句'}
                    </div>
                    <pre className="code-block" style={{ margin: 0, maxHeight: 160, overflow: 'auto', fontSize: 12.5 }}>{verifyResult.resolvedScript}</pre>
                  </>
                )}
                {verifyResult.responsePreview && (
                  <>
                    <div style={{ fontSize: 12.5, fontWeight: 600, color: '#5C4F41', margin: '12px 0 6px' }}>响应体预览</div>
                    <pre className="code-block" style={{ margin: 0, maxHeight: 160, overflow: 'auto', fontSize: 12.5 }}>{verifyResult.responsePreview}</pre>
                  </>
                )}
              </>
            )}
            {verifyResult && !verifyResult.success && (
              <div style={{ display: 'grid', gap: 10 }}>
                <div style={{ background: 'rgba(190,58,43,.08)', border: '1px solid rgba(190,58,43,.25)', borderRadius: 8, padding: '10px 12px', fontSize: 13.5, color: 'var(--reject)', lineHeight: 1.7, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                  ✗ 验证未通过{'\n'}{verifyResult.error}
                </div>
                {verifyResult.resolvedScript && (
                  <>
                    <div style={{ fontSize: 12.5, fontWeight: 600, color: '#5C4F41' }}>实际发出的请求</div>
                    <pre className="code-block" style={{ margin: 0, maxHeight: 140, overflow: 'auto', fontSize: 12.5 }}>{verifyResult.resolvedScript}</pre>
                  </>
                )}
                {verifyResult.responsePreview && (
                  <>
                    <div style={{ fontSize: 12.5, fontWeight: 600, color: '#5C4F41' }}>响应体预览</div>
                    <pre className="code-block" style={{ margin: 0, maxHeight: 140, overflow: 'auto', fontSize: 12.5 }}>{verifyResult.responsePreview}</pre>
                  </>
                )}
              </div>
            )}
          </>
        )}
      </Modal>
    </div>
  );
}
