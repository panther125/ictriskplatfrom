import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client.js';
import { EmptyState, Modal, FormField } from '../components/Common.jsx';
import StrategyTrafficModal from '../components/StrategyTrafficModal.jsx';

const FILTERS = ['全部', '草稿', '已发布'];

/** 策略编码口径（与后端 StrategyService.CODE_PATTERN 一致）：小写字母开头，仅含小写字母 / 数字 / 下划线 */
const CODE_RE = /^[a-z][a-z0-9_]{2,127}$/;

const EMPTY_FORM = {
  name: '',
  code: '',
  category: '决策中心',
  owner: '',
  description: '',
  inheritFromStrategyId: ''
};

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

  /* 新增策略弹窗：策略本体 + 可选的初始画布继承源 */
  const [createOpen, setCreateOpen] = useState(false);
  const [form, setForm] = useState(EMPTY_FORM);
  const [touched, setTouched] = useState(false);
  const [creating, setCreating] = useState(false);
  const [createErr, setCreateErr] = useState('');

  const setF = (key, value) => setForm(f => ({ ...f, [key]: value }));

  /** 业务域候选项：取现有策略里出现过的分类，避免自由发挥出五花八门的写法 */
  const categoryOptions = useMemo(
    () => Array.from(new Set(list.map(s => s.category).filter(Boolean))),
    [list]
  );

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

  /* ---------- 新增策略 ---------- */

  const codeErr = useMemo(() => {
    const c = form.code.trim();
    if (!c) return '策略编码不能为空';
    if (!CODE_RE.test(c)) return '需以小写字母开头，仅含小写字母、数字与下划线（如 loan_apply_v3）';
    if (list.some(s => String(s.code || '').toLowerCase() === c)) return `编码 ${c} 已被占用`;
    return '';
  }, [form.code, list]);

  const nameErr = form.name.trim() ? '' : '策略名称不能为空';

  const openCreate = () => {
    setForm(EMPTY_FORM);
    setTouched(false);
    setCreateErr('');
    setCreateOpen(true);
  };

  const doCreate = async () => {
    setTouched(true);
    if (nameErr || codeErr) return;
    setCreating(true);
    setCreateErr('');
    const inheritFrom = list.find(s => String(s.id) === String(form.inheritFromStrategyId));
    try {
      const created = await api.strategies.create({
        name: form.name.trim(),
        code: form.code.trim(),
        category: form.category.trim() || undefined,
        owner: form.owner.trim() || undefined,
        description: form.description.trim() || undefined,
        inheritFromStrategyId: inheritFrom ? inheritFrom.id : undefined
      });
      setCreateOpen(false);
      // 落地版本管理页：新策略自带 v1 草稿，用户先确认版本结构再进编排
      navigate(`/strategy/${created.id}/versions`, {
        state: { created: { name: created.name, code: created.code, inheritedFrom: inheritFrom || null } }
      });
    } catch (e) {
      setCreateErr(e.message || '新增策略失败');
    } finally {
      setCreating(false);
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
          <button
            className="btn primary"
            onClick={openCreate}
            title="新建策略：同时初始化 v1 草稿版本，可继承已有策略的画布"
          >+ 新增策略</button>
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
          <table className="table" style={{ minWidth: 980 }}>
            <thead>
              <tr>
                <th>策略名称 / 编码</th>
                <th>分类</th>
                <th>责任人</th>
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
                  <td style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>{s.owner || '—'}</td>
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
        {!loading && !list.length && (
          <EmptyState
            title="还没有任何策略"
            desc="新建一个策略开始：平台会同时初始化它的 v1 草稿版本，可直接进入画布编排。"
            action={<button className="btn primary" onClick={openCreate}>+ 新增策略</button>}
          />
        )}
        {!loading && list.length > 0 && !filtered.length && (
          <EmptyState
            title="没有匹配的策略"
            desc="换个关键词，或把状态筛选切回「全部」。"
            action={<button className="btn" onClick={() => { setKeyword(''); setFilter('全部'); }}>清空筛选条件</button>}
          />
        )}
        {loading && <div className="empty">加载中…</div>}
      </div>

      {/* ---------- 新增策略弹窗 ---------- */}
      <Modal
        open={createOpen}
        title="新增策略"
        sub="POST /api/strategies"
        onClose={() => !creating && setCreateOpen(false)}
        width={600}
        footer={(
          <>
            <button className="btn" disabled={creating} onClick={() => setCreateOpen(false)}>取消</button>
            <button className="btn primary" disabled={creating} onClick={doCreate}>
              {creating ? '创建中…' : '创建策略'}
            </button>
          </>
        )}
      >
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
          <FormField label="策略名称" required error={touched ? nameErr : ''}>
            <input
              className="input"
              value={form.name}
              onChange={e => setF('name', e.target.value)}
              placeholder="如：逾期滚动率准入策略"
              autoFocus
            />
          </FormField>
          <FormField
            label="策略编码"
            required
            error={touched ? codeErr : ''}
            hint={!touched || !codeErr ? '发布 / 试算 / 留痕都按它定位，创建后不可修改' : ''}
          >
            <input
              className="input mono"
              value={form.code}
              onChange={e => setF('code', e.target.value.toLowerCase().replace(/[^a-z0-9_]/g, ''))}
              placeholder="如：loan_apply_v3"
              spellCheck={false}
            />
          </FormField>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
          <FormField label="业务域">
            <input
              className="input"
              list="strategy-category-options"
              value={form.category}
              onChange={e => setF('category', e.target.value)}
              placeholder="决策中心"
            />
            <datalist id="strategy-category-options">
              {categoryOptions.map(c => <option key={c} value={c} />)}
            </datalist>
          </FormField>
          <FormField label="责任人">
            <input
              className="input"
              value={form.owner}
              onChange={e => setF('owner', e.target.value)}
              placeholder="如：王敏"
            />
          </FormField>
        </div>

        <FormField label="策略说明">
          <textarea
            className="input"
            rows={2}
            style={{ resize: 'vertical', fontFamily: 'inherit' }}
            value={form.description}
            onChange={e => setF('description', e.target.value)}
            placeholder="一句话说明这个策略管什么（可选）"
          />
        </FormField>

        <FormField
          label="初始画布"
          hint="新建策略一定带一个 v1 草稿版本；选「继承」可完整复制已有策略最新编排的节点、连线、坐标与规则配置。"
        >
          <select
            className="select"
            value={form.inheritFromStrategyId}
            onChange={e => setF('inheritFromStrategyId', e.target.value)}
          >
            <option value="">空画布（仅种入 开始 / 结束 骨架）</option>
            {list.map(s => (
              <option key={s.id} value={s.id}>
                继承 {s.name}（{s.code}）· {s.versionCount ?? 0} 个版本
              </option>
            ))}
          </select>
        </FormField>

        {createErr && (
          <div className="warn-bar" style={{ marginTop: 12, justifyContent: 'flex-start' }}>
            <span><b style={{ color: 'var(--reject)' }}>创建失败</b> {createErr}</span>
          </div>
        )}

        <div className="warn-bar" style={{ marginTop: 12, justifyContent: 'flex-start' }}>
          <span>
            新策略以<b>草稿</b>建立，编码创建后不可修改；确认后进入该策略的版本管理页，v1 草稿可直接编排、发布。
          </span>
        </div>
      </Modal>

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
