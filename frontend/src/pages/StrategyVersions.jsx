import React, { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/client.js';
import { StatusTag, Modal, EmptyState } from '../components/Common.jsx';
import StrategyTrafficModal from '../components/StrategyTrafficModal.jsx';

/* 版本管理：某策略下的全部版本（列表）+ 流量份额 + 行级操作（编排 / 切流 / 发布 / 下线） */
export default function StrategyVersions() {
  const { strategyId } = useParams();
  const navigate = useNavigate();

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
        </div>
      </div>

      {err && (
        <div className="warn-bar" style={{ marginBottom: 16 }}>
          <span><b style={{ color: 'var(--reject)' }}>加载失败</b> {err}</span>
          <button className="btn sm" onClick={reload}>重试</button>
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
            <span className="sub" style={{ fontSize: 12.5, color: 'var(--ink-3)' }}>一行一个版本 · 含切流份额与行级操作</span>
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
                        <button className="btn sm" onClick={() => navigate(`/strategy/${strategyId}/editor`)}>编排</button>
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
              desc="先到编排页新建版本，再回到这里做流量分配与发布。"
              action={<button className="btn primary" onClick={() => navigate(`/strategy/${strategyId}/editor`)}>去编排</button>}
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
