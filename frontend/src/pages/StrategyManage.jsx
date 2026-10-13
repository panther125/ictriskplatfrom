import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client.js';
import { EmptyState } from '../components/Common.jsx';
import StrategyTrafficModal from '../components/StrategyTrafficModal.jsx';

const FILTERS = ['全部', '草稿', '已发布'];

/* 列表「流量」列：多版本份额拼接；白名单模式展示白名单总人数与兜底版本 */
const onlineTrafficText = (s) => {
  const vs = s.onlineVersions || [];
  if (!vs.length) return '—';
  if (vs.some(v => v.trafficMode === 'WHITELIST')) {
    const fallback = vs.find(v => v.fallback);
    const wl = vs.filter(v => !v.fallback).reduce((a, v) => a + (v.whitelistUsers || 0), 0);
    return [`白名单 ${wl} 人`, fallback ? `兜底 ${fallback.versionNo}` : null].filter(Boolean).join(' / ');
  }
  return vs.map(v => `${v.trafficPct ?? 0}%`).join(' / ');
};

export default function StrategyManage() {
  const navigate = useNavigate();
  const [list, setList] = useState([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState('');
  const [filter, setFilter] = useState('全部');
  const [keyword, setKeyword] = useState('');

  // 切流配置弹窗（决策级；从策略行进入时不预高亮任何版本）
  const [splitTarget, setSplitTarget] = useState(null);
  const [splitLoadingId, setSplitLoadingId] = useState(null);

  const loadList = async () => {
    setLoading(true);
    try {
      const data = await api.strategies.list();
      setList(data || []);
      setErr('');
    } catch (e) {
      setErr(e.message || '策略列表加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadList(); }, []);

  const counts = useMemo(() => ({
    '全部': list.length,
    '草稿': list.filter(s => s.status === 'DRAFT').length,
    '已发布': list.filter(s => s.status === 'PUBLISHED').length
  }), [list]);

  const kw = keyword.trim().toLowerCase();
  const filtered = list.filter(s => {
    if (filter !== '全部' && s.statusText !== filter) return false;
    if (!kw) return true;
    return [s.code, s.name, s.owner, s.category].filter(Boolean).some(x => String(x).toLowerCase().includes(kw));
  });

  const openVersions = (s) => navigate(`/strategy/${s.id}/versions`);

  const openSplitForStrategy = async (s) => {
    setSplitLoadingId(s.id);
    try {
      const vers = await api.strategies.versions(s.id);
      setSplitTarget({ strategy: s, versions: vers || [], focusVersionId: null });
    } catch (e) {
      setErr(e.message || '版本列表加载失败，无法打开切流配置');
    } finally {
      setSplitLoadingId(null);
    }
  };

  return (
    <div>
      <div className="page-header">
        <div>
          <h1>策略管理</h1>
          <div className="sub">决策中心 / 策略管理 · 策略只有「草稿 / 已发布」两种状态，版本有「草稿 / 已发布 / 已下线」三种</div>
        </div>
        <div className="actions">
          <button className="btn" onClick={loadList} disabled={loading}>{loading ? '刷新中…' : '刷新'}</button>
        </div>
      </div>

      {err && (
        <div className="warn-bar" style={{ marginBottom: 16 }}>
          <span><b style={{ color: 'var(--reject)' }}>加载失败</b> {err}</span>
          <button className="btn sm" onClick={loadList}>重试</button>
        </div>
      )}

      <div className="filter-tabs">
        {FILTERS.map(f => (
          <button key={f} className={`filter-tab ${filter === f ? 'active' : ''}`} onClick={() => setFilter(f)}>
            {f} <span className="count">{counts[f] || 0}</span>
          </button>
        ))}
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 8, alignItems: 'center' }}>
          <div style={{ position: 'relative' }}>
            <input
              className="input"
              style={{ width: 220, paddingRight: keyword ? 34 : undefined }}
              placeholder="搜索策略名 / 编码 / 责任人"
              value={keyword}
              onChange={e => setKeyword(e.target.value)}
            />
            {keyword && (
              <button
                onClick={() => setKeyword('')}
                title="清空搜索" aria-label="清空搜索"
                style={{
                  position: 'absolute', right: 4, top: '50%', transform: 'translateY(-50%)',
                  width: 26, height: 26, border: 'none', background: 'transparent', borderRadius: 6,
                  cursor: 'pointer', color: 'var(--ink-3)', fontSize: 16, lineHeight: 1
                }}
              >×</button>
            )}
          </div>
        </div>
      </div>

      {/* ---------- 策略列表（全宽） ---------- */}
      <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
        <div className="card-head">
          <b>策略列表 · {filtered.length} 个</b>
          <span className="sub" style={{ fontSize: 12.5, color: 'var(--ink-3)' }}>点击任意一行进入版本管理</span>
        </div>
        <div className="table-wrap">
          <table className="table" style={{ minWidth: 880 }}>
            <thead>
              <tr>
                <th>策略名称 / 编码</th>
                <th>分类</th>
                <th>版本</th>
                <th>线上版本</th>
                <th>流量</th>
                <th style={{ width: 170 }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map(s => (
                <tr
                  key={s.id}
                  className="clickable"
                  onClick={() => openVersions(s)}
                >
                  <td>
                    <div style={{ fontWeight: 500 }}>{s.name}</div>
                    <div className="code-cell" style={{ marginTop: 2 }}>{s.code}</div>
                  </td>
                  <td style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>{s.category || '—'}</td>
                  <td className="mono">{s.versionCount ?? 0} 个</td>
                  <td className="mono">{(s.onlineVersions || []).map(v => v.versionNo).join('、') || <span style={{ color: 'var(--ink-3)' }}>未上线</span>}</td>
                  <td className="mono">{onlineTrafficText(s)}</td>
                  <td onClick={e => e.stopPropagation()}>
                    <div className="table-actions">
                      <button className="btn sm" onClick={() => openVersions(s)}>版本</button>
                      {s.status === 'PUBLISHED' && (
                        <button className="btn sm" disabled={splitLoadingId === s.id} onClick={() => openSplitForStrategy(s)}>
                          {splitLoadingId === s.id ? '加载中…' : '切流'}
                        </button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {!filtered.length && !loading && (
          <EmptyState
            title="没有匹配的策略"
            desc="换个关键词，或把状态筛选切回「全部」。"
            action={<button className="btn" onClick={() => { setKeyword(''); setFilter('全部'); }}>清空筛选条件</button>}
          />
        )}
        {loading && <div className="empty">加载中…</div>}
      </div>

      {/* ---------- 切流配置弹窗（决策级：一次给多个版本分配流量份额） ---------- */}
      <StrategyTrafficModal
        open={!!splitTarget}
        strategy={splitTarget?.strategy}
        versions={splitTarget?.versions}
        focusVersionId={splitTarget?.focusVersionId || null}
        onClose={() => setSplitTarget(null)}
        onSaved={loadList}
      />
    </div>
  );
}
