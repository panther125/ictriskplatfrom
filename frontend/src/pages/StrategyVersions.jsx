import React, { useEffect, useState } from 'react';
import { useNavigate, useParams, useLocation } from 'react-router-dom';
import { api } from '../api/client.js';
import { StatusTag, Modal, EmptyState } from '../components/Common.jsx';
import StrategyTrafficModal from '../components/StrategyTrafficModal.jsx';

/* 版本管理：某策略下的全部版本（列表）+ 流量份额 + 行级操作（编排 / 切流 / 发布 / 下线） */
export default function StrategyVersions() {
  const { strategyId } = useParams();
  const navigate = useNavigate();
  const location = useLocation();

  /* 从「新增策略」跳转而来时带创建上下文：提示新策略已建 + 一键进 v1 编排 */
  const created = location.state?.created || null;
  const [createdTipOpen, setCreatedTipOpen] = useState(!!created);

  const [meta, setMeta] = useState(null);
  const [versions, setVersions] = useState([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState('');

  // 切流弹窗（与策略管理页共用；从版本行进入时预高亮该行）
  const [splitOpen, setSplitOpen] = useState(false);
  const [splitFocusId, setSplitFocusId] = useState(null);
  // 发布确认
  const [publishTarget, setPublishTarget] = useState(null);
  const [publishing, setPublishing] = useState(false);
  const [actingId, setActingId] = useState(null);

  /* 新增版本弹窗：新版本继承上一个画布的节点与连线配置 */
  const [createOpen, setCreateOpen] = useState(false);
  const [baseVersionId, setBaseVersionId] = useState('');
  const [basePreview, setBasePreview] = useState(null);
  const [newNote, setNewNote] = useState('');
  const [creating, setCreating] = useState(false);

  /** 进入查看：已定版版本只读打开画布（可缩放、可看配置，不能改） */
  const openViewer = (v) => navigate(`/strategy/${strategyId}/editor?versionId=${v.id}&mode=view`);
  /** 进入编排：草稿/已下线版本打开编排画布；已下线且无画布时后端会派生新草稿并提示 */
  const openEditor = (v) => navigate(`/strategy/${strategyId}/editor?versionId=${v.id}`);

  const refreshAll = async () => {
    const [list, vers] = await Promise.all([
      api.strategies.list(),
      api.strategies.versions(strategyId)
    ]);
    setMeta((list || []).find(s => String(s.id) === String(strategyId)) || null);
    setVersions(vers || []);
    setErr('');
  };

  const reload = async () => {
    try {
      await refreshAll();
    } catch (e) {
      setErr(e.message || '版本列表加载失败');
    }
  };

  useEffect(() => {
    let alive = true;
    refreshAll()
      .catch(e => { if (alive) setErr(e.message || '版本列表加载失败'); })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [strategyId]);

  const doPublish = async () => {
    if (!publishTarget) return;
    setPublishing(true);
    try {
      await api.strategies.publishVersion(publishTarget.id);
      setPublishTarget(null);
      await reload();
    } catch (e) {
      setErr(e.message || '发布失败');
    } finally {
      setPublishing(false);
    }
  };

  const offlineVersion = async (versionId) => {
    setActingId(versionId);
    try {
      await api.strategies.offlineVersion(versionId);
      await reload();
    } catch (e) {
      setErr(e.message || '操作失败');
    } finally {
      setActingId(null);
    }
  };

  /* 默认继承最新版本（版本列表按 id 升序，末位即最新） */
  const latestVersion = versions.length ? versions[versions.length - 1] : null;
  /* 落地提示里「进入编排」的目标：新策略的 v1 草稿 */
  const draftVersion = versions.find(v => v.status === 'DRAFT') || null;

  const openCreate = () => {
    setBaseVersionId(latestVersion ? String(latestVersion.id) : '');
    setBasePreview(null);
    setNewNote('');
    setCreateOpen(true);
  };

  /* 打开弹窗或切换继承源时，先真实读一遍继承源的画布，让「继承什么」可见 */
  useEffect(() => {
    if (!createOpen || !baseVersionId) { setBasePreview(null); return undefined; }
    let alive = true;
    setBasePreview({ loading: true });
    api.strategies.graph(baseVersionId)
      .then((g) => {
        if (!alive) return;
        setBasePreview({
          loading: false,
          versionNo: g.versionNo,
          canvasSource: g.canvasSource,
          canvasNote: g.canvasNote,
          nodeCount: (g.nodes || []).length,
          edgeCount: (g.edges || []).length
        });
      })
      .catch((e) => { if (alive) setBasePreview({ loading: false, error: e.message }); });
    return () => { alive = false; };
  }, [createOpen, baseVersionId]);

  const doCreateVersion = async () => {
    setCreating(true);
    try {
      const base = versions.find((v) => String(v.id) === String(baseVersionId));
      const g = await api.strategies.createVersion(strategyId, {
        baseVersionNo: base ? base.versionNo : undefined,
        description: newNote.trim() || undefined
      });
      setCreateOpen(false);
      // 直接进入新版本的编排页（新版本已继承画布，可立即修改）
      // 继承来源随 URL 带过去：跳转后是重新加载，只有 URL 能把这个上下文带上
      const src = base ? `&inherited=${encodeURIComponent(base.versionNo)}` : '';
      navigate(`/strategy/${strategyId}/editor?versionId=${g.versionId}${src}`);
    } catch (e) {
      setErr(e.message || '新增版本失败');
    } finally {
      setCreating(false);
    }
  };

  const pubLine = (v) => [
    v.publishedAt ? String(v.publishedAt).replace('T', ' ').slice(0, 16) : null,
    v.publishedBy
  ].filter(Boolean).join(' · ');

  return (
    <div>
      <div className="page-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
          <button className="btn sm ghost" onClick={() => navigate('/strategy')}>← 策略管理</button>
          <div>
            <h1>版本管理</h1>
            <div className="sub">决策中心 / 策略管理 / 版本管理{meta ? ` · ${meta.name}（${meta.code}）` : ''}</div>
          </div>
        </div>
        <div className="actions">
          <button className="btn" onClick={() => { setLoading(true); reload().finally(() => setLoading(false)); }} disabled={loading}>
            {loading ? '刷新中…' : '刷新'}
          </button>
          <button
            className="btn primary"
            onClick={openCreate}
            title="新建一个草稿版本，画布配置从上一个版本完整继承"
          >+ 新增版本</button>
        </div>
      </div>

      {err && (
        <div className="warn-bar" style={{ marginBottom: 16 }}>
          <span><b style={{ color: 'var(--reject)' }}>加载失败</b> {err}</span>
          <button className="btn sm" onClick={reload}>重试</button>
        </div>
      )}

      {/* 新增策略的落地提示：策略与 v1 草稿是一起产生的，这里把「下一步做什么」说清楚 */}
      {created && createdTipOpen && (
        <div className="ok-bar" style={{ marginBottom: 16, justifyContent: 'space-between', alignItems: 'center' }}>
          <span>
            策略 <b>{created.name}</b>（<span className="mono">{created.code}</span>）已创建，v1 草稿版本已就绪
            {created.inheritedFrom
              ? `，画布已继承「${created.inheritedFrom.name}」最新编排的节点与连线`
              : '（空画布，仅种入 开始 / 结束）'}。
          </span>
          <div style={{ display: 'flex', gap: 8, flex: 'none' }}>
            {draftVersion && (
              <button className="btn sm primary" onClick={() => openEditor(draftVersion)}>进入 {draftVersion.versionNo} 编排</button>
            )}
            <button className="btn sm" onClick={() => setCreatedTipOpen(false)}>知道了</button>
          </div>
        </div>
      )}

      {!loading && !meta && !err && (
        <div className="card">
          <EmptyState
            title="未找到该策略"
            desc={`策略 #${strategyId} 可能已被删除。`}
            action={<button className="btn" onClick={() => navigate('/strategy')}>返回策略管理</button>}
          />
        </div>
      )}

      {meta && (
        <div className="card" style={{ marginBottom: 16 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <b style={{ fontSize: 15 }}>{meta.name}</b>
              <span className="code-cell">{meta.code}</span>
            </div>
            <StatusTag text={meta.statusText} />
          </div>
          <div className="hint" style={{ marginTop: 8 }}>{meta.description || '—'}</div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 8, marginTop: 12, fontSize: 12.5 }}>
            {[
              ['业务域', meta.category || '—'],
              ['责任人', meta.owner || '—'],
              ['版本数', `${meta.versionCount ?? 0} 个`],
              ['线上版本', (meta.onlineVersions || []).length ? meta.onlineVersions.map(v => v.versionNo).join('、') : '未上线']
            ].map(([k, v]) => (
              <div key={k}>
                <div style={{ color: 'var(--ink-3)' }}>{k}</div>
                <div className="mono">{v}</div>
              </div>
            ))}
          </div>
        </div>
      )}

      {meta && (!err || versions.length > 0) && (
        <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
          <div className="card-head">
            <b>版本列表 · {versions.length} 个版本</b>
            <span className="sub" style={{ fontSize: 12.5, color: 'var(--ink-3)' }}>
              一行一个版本 · 已上线版本给「查看」（画布只读定版），草稿给「编排」；修改请用「新增版本」继承后发布
            </span>
          </div>
          <div className="table-wrap">
            <table className="table" style={{ minWidth: 960 }}>
              <thead>
                <tr>
                  <th style={{ width: 120 }}>版本</th>
                  <th style={{ width: 90 }}>状态</th>
                  <th style={{ width: 150 }}>流量份额</th>
                  <th>切流方式</th>
                  <th>运行状态</th>
                  <th style={{ width: 240 }}>操作</th>
                </tr>
              </thead>
              <tbody>
                {versions.map(v => (
                  <tr key={v.id}>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <b className="mono" style={{ fontSize: 14 }}>{v.versionNo}</b>
                        {v.rev != null && <span className="tag tag-draft">rev {v.rev}</span>}
                      </div>
                    </td>
                    <td><StatusTag text={v.statusText} /></td>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                        <div className="progress" style={{ width: 86, flex: 'none' }}>
                          <div className="progress-bar" style={{ width: `${Math.min(100, v.trafficPct ?? 0)}%` }} />
                        </div>
                        <span className="mono" style={{ fontWeight: 600, color: 'var(--decision)' }}>
                          {v.trafficMode === 'WHITELIST' && !v.fallback ? '白名单' : `${v.trafficPct ?? 0}%`}
                        </span>
                      </div>
                    </td>
                    <td>
                      <div>{v.trafficModeText || '百分比切流'}</div>
                      <div style={{ color: 'var(--ink-3)', fontSize: 12 }}>
                        {v.trafficMode === 'WHITELIST'
                          ? (v.fallback ? '兜底版本' : `白名单 ${v.whitelistUsers ?? 0} 人`)
                          : ([v.channel, v.userTag].filter(Boolean).join(' · ') || '不限渠道与客群')}
                      </div>
                    </td>
                    <td>
                      <div>{v.stateLabel || '—'}</div>
                      {pubLine(v) && <div style={{ color: 'var(--ink-3)', fontSize: 12 }}>{pubLine(v)}</div>}
                    </td>
                    <td>
                      <div className="table-actions">
                        {v.status === 'PUBLISHED' ? (
                          /* 已上线版本内容已定版：只给「查看」，画布只读展示（后端同样拦截写入） */
                          <button
                            className="btn sm"
                            title="只读查看该版本的画布内容（已定版，不能修改）"
                            onClick={() => openViewer(v)}
                          >查看</button>
                        ) : (
                          <button
                            className="btn sm"
                            title={v.status === 'OFFLINE'
                              ? '已下线版本：打开编排页调整后可作为新版本重新发布'
                              : '进入编排页编辑该草稿版本'}
                            onClick={() => openEditor(v)}
                          >编排</button>
                        )}
                        <button className="btn sm" onClick={() => { setSplitFocusId(v.id); setSplitOpen(true); }}>切流</button>
                        {v.status === 'DRAFT' && (
                          <button className="btn sm primary" onClick={() => setPublishTarget(v)}>发布</button>
                        )}
                        {v.status === 'PUBLISHED' && (
                          <button className="btn sm danger" disabled={actingId === v.id} onClick={() => offlineVersion(v.id)}>
                            {actingId === v.id ? '下线中…' : '下线'}
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!loading && !versions.length && !err && (
            <EmptyState
              title="该策略还没有版本"
              desc="新增一个版本开始编排——新版本会先种入开始/结束骨架，之后的版本都会继承上一个画布。"
              action={<button className="btn primary" onClick={openCreate}>+ 新增版本</button>}
            />
          )}
        </div>
      )}

      {/* 切流配置弹窗（决策级，与策略管理页共用同一组件） */}
      <StrategyTrafficModal
        open={splitOpen}
        strategy={meta}
        versions={versions}
        focusVersionId={splitFocusId}
        onClose={() => setSplitOpen(false)}
        onSaved={reload}
      />

      {/* 新增版本：新版本继承上一个画布的节点、连线、坐标与规则配置 */}
      <Modal
        open={createOpen}
        title="新增版本"
        sub="POST /api/strategies/{strategyId}/versions"
        onClose={() => !creating && setCreateOpen(false)}
        width={560}
        footer={(
          <>
            <button className="btn" disabled={creating} onClick={() => setCreateOpen(false)}>取消</button>
            <button className="btn primary" disabled={creating || !versions.length} onClick={doCreateVersion}>
              {creating ? '创建中…' : '创建并进入编排'}
            </button>
          </>
        )}
      >
        <div className="field" style={{ marginBottom: 14 }}>
          <label>继承哪个版本的画布配置</label>
          <select className="select" value={baseVersionId} onChange={(e) => setBaseVersionId(e.target.value)}>
            {versions.map((v) => (
              <option key={v.id} value={v.id}>
                {v.versionNo} · {v.statusText}{v.stateLabel ? ` · ${v.stateLabel}` : ''}
              </option>
            ))}
          </select>
          <div className="hint" style={{ marginTop: 6 }}>
            默认继承最新的 {latestVersion ? latestVersion.versionNo : '—'}；新版本会完整复制它的节点、连线、坐标与规则配置。
          </div>
        </div>

        {basePreview?.loading && <div className="hint">正在读取继承源画布…</div>}
        {basePreview?.error && (
          <div className="warn-bar" style={{ justifyContent: 'flex-start' }}>
            <span>继承源画布读取失败：{basePreview.error}。仍可创建，将使用开始/结束空骨架。</span>
          </div>
        )}
        {basePreview && !basePreview.loading && !basePreview.error && (
          <div className="ok-bar" style={{ alignItems: 'flex-start', flexDirection: 'column', gap: 4 }}>
            <div>
              将继承 <b className="mono">{basePreview.versionNo}</b> 的画布：
              <b> {basePreview.nodeCount} 个节点</b> · <b>{basePreview.edgeCount} 条连线</b>
            </div>
            {basePreview.canvasSource === 'FALLBACK' && (
              <div style={{ fontSize: 12.5 }}>
                {basePreview.canvasNote || `该版本未留存画布快照，将继承该策略最新的编排内容。`}
              </div>
            )}
            {basePreview.canvasSource === 'EMPTY' && (
              <div style={{ fontSize: 12.5 }}>该版本没有画布内容，将使用开始/结束空骨架。</div>
            )}
          </div>
        )}

        <div className="field" style={{ marginTop: 14, marginBottom: 0 }}>
          <label>版本说明（可选）</label>
          <input
            className="input"
            value={newNote}
            onChange={(e) => setNewNote(e.target.value)}
            placeholder="如：调高逾期阈值后再发布"
          />
        </div>

        <div className="warn-bar" style={{ marginTop: 14, justifyContent: 'flex-start' }}>
          <span>
            新版本以<b>草稿</b>创建，版本号自动生成；原版本不受影响。确认后直接进入编排页，改完回本页点「发布」。
          </span>
        </div>
      </Modal>

      {/* 发布确认弹窗（全量切换会清除现有切流分配） */}
      <Modal
        open={!!publishTarget}
        title="发布版本"
        sub="POST /api/strategies/versions/{versionId}/publish"
        onClose={() => !publishing && setPublishTarget(null)}
        width={520}
        footer={(
          <>
            <button className="btn" disabled={publishing} onClick={() => setPublishTarget(null)}>取消</button>
            <button className="btn primary" disabled={publishing} onClick={doPublish}>{publishing ? '发布中…' : '确认发布'}</button>
          </>
        )}
      >
        <div style={{ lineHeight: 1.9 }}>
          确认发布版本 <b className="mono" style={{ color: 'var(--decision)' }}>{publishTarget?.versionNo}</b>（全量切换）？
        </div>
        <div className="warn-bar" style={{ marginTop: 12, justifyContent: 'flex-start' }}>
          <span>发布后将全量切至该版本：该策略其它在线版本会被下线，现有切流分配会被清除；如需多版本并行，请在发布后使用「切流」重新分配。</span>
        </div>
      </Modal>
    </div>
  );
}
