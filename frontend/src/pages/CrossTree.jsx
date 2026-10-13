import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { api } from '../api/client.js';
import { ConclusionBadge } from '../components/Common.jsx';

const STRATEGY_ID = 1;

const CONCLUSION_PRESET = [
  { label: '通过', conclusion: 'PASS', score: 0 },
  { label: '人工审核', conclusion: 'REVIEW', score: 60 },
  { label: '拒绝', conclusion: 'REJECT', score: 85 }
];

const parseBins = (binsJson) => {
  if (!binsJson) return [];
  try {
    const v = JSON.parse(binsJson);
    return Array.isArray(v) ? v : [];
  } catch { return []; }
};

const keyOf = (row, col) => `${row}||${col}`;

export default function CrossTree() {
  const [treeId, setTreeId] = useState(null);
  const [trees, setTrees] = useState([]);
  const [data, setData] = useState(null);
  const [validation, setValidation] = useState(null);
  const [diffs, setDiffs] = useState([]);
  const [slice, setSlice] = useState(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState('');
  const [saving, setSaving] = useState(false);

  // 框选：anchor 为起点，focus 为终点，两者围成矩形
  const [anchor, setAnchor] = useState(null);
  const [focus, setFocus] = useState(null);
  const [draft, setDraft] = useState({ conclusion: 'REVIEW', score: 60, remark: 'R_MANUAL' });

  useEffect(() => {
    api.crossTrees.list(STRATEGY_ID)
      .then(list => {
        setTrees(list || []);
        setTreeId(list?.[0]?.id ?? 1);
      })
      .catch(() => { setTrees([]); setTreeId(1); });
  }, []);

  const reload = useCallback((id, sl) => {
    if (!id) return;
    setLoading(true); setErr('');
    return api.crossTrees.detail(id, sl || undefined)
      .then(d => {
        setData(d);
        if (!sl) setSlice(d?.sliceName || null);
        return Promise.all([
          api.crossTrees.validate(id).then(setValidation).catch(() => setValidation(null)),
          api.crossTrees.compare(id).then(setDiffs).catch(() => setDiffs([]))
        ]);
      })
      .catch(e => { setErr(e.message || '加载失败'); setData(null); })
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    if (treeId == null) return;
    setAnchor(null); setFocus(null);
    reload(treeId, slice);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [treeId, slice]);

  const dims = data?.dimensions || [];
  const cells = data?.cells || [];

  // 切片 = D1 的分箱值；行 / 列由当前切片的格子推导
  const slices = useMemo(() => (dims[0] ? parseBins(dims[0].binsJson) : []), [dims]);
  const rows = useMemo(() => [...new Set(cells.map(c => c.rowKey))], [cells]);
  const cols = useMemo(() => [...new Set(cells.map(c => c.colKey))], [cells]);
  const cellMap = useMemo(() => {
    const m = {};
    cells.forEach(c => { m[keyOf(c.rowKey, c.colKey)] = c; });
    return m;
  }, [cells]);

  const rowIdx = (r) => rows.indexOf(r);
  const colIdx = (c) => cols.indexOf(c);

  const selectedKeys = useMemo(() => {
    if (!anchor || !focus) return anchor ? [keyOf(anchor.row, anchor.col)] : [];
    const r1 = Math.min(rowIdx(anchor.row), rowIdx(focus.row));
    const r2 = Math.max(rowIdx(anchor.row), rowIdx(focus.row));
    const c1 = Math.min(colIdx(anchor.col), colIdx(focus.col));
    const c2 = Math.max(colIdx(anchor.col), colIdx(focus.col));
    const out = [];
    for (let r = r1; r <= r2; r++) for (let c = c1; c <= c2; c++) out.push(keyOf(rows[r], cols[c]));
    return out;
  }, [anchor, focus, rows, cols]);

  const isSelected = (row, col) => selectedKeys.includes(keyOf(row, col));

  const onCellClick = (row, col, shiftKey) => {
    if (shiftKey && anchor) setFocus({ row, col });
    else { setAnchor({ row, col }); setFocus({ row, col }); }
  };

  const applyFill = async () => {
    if (!selectedKeys.length || !data) return;
    setSaving(true); setErr('');
    try {
      // 重排当前切片全量格子后整体提交（后端按切片覆盖）
      const next = cells.map(c => (selectedKeys.includes(keyOf(c.rowKey, c.colKey))
        ? { ...c, conclusion: draft.conclusion, score: draft.score, remark: draft.remark, sliceName: data.sliceName }
        : { ...c, sliceName: data.sliceName }));
      await api.crossTrees.updateCells(treeId, next);
      await reload(treeId, data.sliceName);
    } catch (e) { setErr(e.message || '保存失败'); }
    finally { setSaving(false); }
  };

  const submitApproval = async () => {
    setSaving(true); setErr('');
    try { await api.crossTrees.submitApproval(treeId); alert('审批已提交'); }
    catch (e) { setErr(e.message || '提交失败'); }
    finally { setSaving(false); }
  };

  const cellCls = (c) => !c ? 'unconfig'
    : c.conclusion === 'PASS' ? 'pass'
      : c.conclusion === 'REVIEW' ? 'review'
        : c.conclusion === 'REJECT' ? 'reject' : 'unconfig';

  const coverage = validation?.coverage ?? 0;

  return (
    <div>
      <div className="page-header">
        <h1>交叉决策树 · 矩阵编辑器</h1>
        <div className="sub">配置态 / 策略编排 / loan_apply_v3 / n_006_CROSS_DECISION_TREE</div>
      </div>

      {err && <div className="warn-bar" style={{ marginBottom: 12, justifyContent: 'flex-start' }}><b style={{ color: 'var(--reject)' }}>失败</b> {err}</div>}

      <div className="card" style={{ padding: '10px 16px', marginBottom: 12, display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <b className="mono">n_006</b>
        <span className="tag tag-experiment" style={{ background: 'rgba(232,116,15,.10)', color: 'var(--feature)', border: 'none' }}>模式 A · 维度矩阵</span>
        <span style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>
          {dims.length} 维 · {validation ? `${validation.configuredCells}/${validation.totalCells}` : '—'} 格 · 编译产物 DecisionTable(MULTI_LEVEL_INDEX)
        </span>
        {trees.length > 1 && (
          <select className="select" style={{ width: 180 }} value={treeId ?? ''} onChange={e => setTreeId(Number(e.target.value))}>
            {trees.map(t => <option key={t.id} value={t.id}>{t.name} · {t.version}</option>)}
          </select>
        )}
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 6 }}>
          <button className="btn sm">导出 CSV</button>
          <button className="btn sm primary" disabled={!selectedKeys.length || saving} onClick={applyFill}>
            {saving ? '保存中…' : `批量填充所选 ${selectedKeys.length} 格`}
          </button>
        </div>
      </div>

      {/* 维度切片 */}
      <div style={{ marginBottom: 12, display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        <span style={{ fontSize: 13.5, fontWeight: 600 }}>维度切片 {dims[0]?.dimNo} {dims[0]?.dimName}</span>
        {slices.length ? slices.map(s => (
          <button key={s} className={`filter-tab ${slice === s ? 'active' : ''}`} style={{ padding: '4px 10px', fontSize: 12.5 }} onClick={() => setSlice(s)}>{s}</button>
        )) : <span style={{ fontSize: 12.5, color: 'var(--ink-3)' }}>当前树未配置切片维度</span>}
        <span style={{ fontSize: 12.5, color: 'var(--ink-3)', marginLeft: 8 }}>切片不影响落盘，仅切换查看视图</span>
      </div>

      {loading && <div className="empty">加载中…</div>}
      {!loading && !data && <div className="empty">未获取到交叉决策树数据</div>}

      {!loading && data && (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 320px', gap: 16 }}>
          <div className="card" style={{ overflow: 'auto' }}>
            <div style={{ marginBottom: 8, fontSize: 12.5, color: 'var(--ink-2)' }}>
              交叉矩阵 · 切片 {data.sliceName} · 行 = 行维度 · 列 = 列维度 · 单元格 = 结论（Shift + 点击 = 框选）
            </div>
            <table className="table" style={{ minWidth: 640 }}>
              <thead>
                <tr>
                  <th style={{ background: '#FFFCF8' }}>行 / 列</th>
                  {cols.map(c => <th key={c}>{c}</th>)}
                </tr>
              </thead>
              <tbody>
                {rows.map(row => (
                  <tr key={row}>
                    <td style={{ fontWeight: 600, fontSize: 12.5, background: '#FFFCF8' }}>{row}</td>
                    {cols.map(col => {
                      const cell = cellMap[keyOf(row, col)];
                      return (
                        <td key={col}
                          onClick={(e) => onCellClick(row, col, e.shiftKey)}
                          style={{ cursor: 'pointer' }}>
                          <div className={`matrix-cell ${cellCls(cell)} ${isSelected(row, col) ? 'selected' : ''}`}>
                            <ConclusionBadge text={cell?.conclusion || 'UNCONFIGURED'} />
                            {cell?.score != null && <div className="mono" style={{ fontSize: 12, color: '#5C4A38' }}>score {cell.score}</div>}
                            {cell?.remark && <div className="mono" style={{ fontSize: 11, color: 'var(--ink-2)' }}>{cell.remark}</div>}
                          </div>
                        </td>
                      );
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
            {!rows.length && <div className="empty">当前切片下没有格子数据</div>}

            <div style={{ marginTop: 16, padding: '10px 12px', background: '#FFFCF8', borderRadius: 8, fontSize: 13.5 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 8 }}>
                <span>覆盖度 <b style={{ color: 'var(--decision)' }}>{coverage}%</b></span>
                <div className="progress" style={{ flex: 1, maxWidth: 200 }}><div className="progress-bar" style={{ width: `${coverage}%` }} /></div>
              </div>
              <div style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>
                已显式配置 {validation?.configuredCells ?? 0}/{validation?.totalCells ?? 0} 格 · 当前框选 {selectedKeys.length} 格
              </div>
            </div>

            {/* 与线上版本对比：GET /cross-trees/{id}/compare */}
            <div style={{ marginTop: 16 }}>
              <div style={{ fontSize: 13.5, fontWeight: 600, marginBottom: 8 }}>与线上版本对比</div>
              {diffs.length ? diffs.slice(0, 10).map((d, i) => (
                <div key={i} style={{ fontSize: 12.5, color: '#5C4A38', marginBottom: 4 }}>
                  <span style={{ color: d.type === 'add' ? 'var(--pass)' : 'var(--review)', fontWeight: 600 }}>
                    {d.type === 'add' ? '+' : '~'}
                  </span> {d.cell} · {d.oldValue} → {d.newValue}
                </div>
              )) : <div className="empty">暂无差异</div>}
            </div>
          </div>

          {/* 右侧面板 */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            <div className="card">
              <div className="card-title"><span>批量填充</span><span className="sub">已选 {selectedKeys.length} 格</span></div>
              <div style={{ display: 'flex', gap: 6, marginBottom: 12, flexWrap: 'wrap' }}>
                {CONCLUSION_PRESET.map(p => (
                  <button key={p.conclusion} className="btn sm"
                    onClick={() => setDraft({ conclusion: p.conclusion, score: p.score, remark: p.conclusion === 'PASS' ? 'R_PASS' : p.conclusion === 'REVIEW' ? 'R_MANUAL' : 'R_OVERDUE_HIGH' })}>
                    {p.label}
                  </button>
                ))}
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                <div className="field" style={{ marginBottom: 0 }}>
                  <label>decisionCode</label>
                  <input className="input" value={draft.conclusion} onChange={e => setDraft({ ...draft, conclusion: e.target.value })} />
                </div>
                <div className="field" style={{ marginBottom: 0 }}>
                  <label>score</label>
                  <input className="input" type="number" value={draft.score} onChange={e => setDraft({ ...draft, score: Number(e.target.value) })} />
                </div>
                <div className="field" style={{ marginBottom: 0 }}>
                  <label>reasonCode</label>
                  <input className="input" value={draft.remark} onChange={e => setDraft({ ...draft, remark: e.target.value })} />
                </div>
                <div className="field" style={{ marginBottom: 0 }}>
                  <label>defaultCell</label>
                  <input className="input" value={data.tree?.defaultConclusion || '—'} readOnly />
                </div>
              </div>
              <button className="btn primary" style={{ width: '100%', marginTop: 10 }} disabled={!selectedKeys.length || saving} onClick={applyFill}>
                应用到所选 {selectedKeys.length} 格
              </button>
            </div>

            <div className="card">
              <div className="card-title"><span>维度定义</span><span className="sub">{dims.length} 维</span></div>
              {dims.map(d => (
                <div key={d.dimNo} style={{ marginBottom: 10 }}>
                  <div style={{ display: 'flex', gap: 8, fontSize: 12.5 }}>
                    <span className="mono" style={{ color: 'var(--decision)', fontWeight: 600 }}>{d.dimNo}</span>
                    <span>{d.dimName} · {d.valueCount} 箱</span>
                  </div>
                  <div className="mono" style={{ fontSize: 12, color: 'var(--ink-2)', marginLeft: 24 }}>{d.expr}</div>
                  <div className="mono" style={{ fontSize: 12, color: 'var(--ink-3)', marginLeft: 24 }}>
                    {parseBins(d.binsJson).join(' / ') || '—'}
                  </div>
                </div>
              ))}
              <div style={{ fontSize: 12.5, color: 'var(--ink-2)', marginTop: 8 }}>注：箱区间为左闭右开，缺失值走 MISSING / OTHER 箱</div>
            </div>

            <div className="card">
              <div className="card-title">
                <span>保存校验</span>
                <span className="sub" style={{ color: validation?.checks?.some(c => c.warn) ? 'var(--review)' : 'var(--pass)' }}>
                  {validation?.checks?.filter(c => c.warn).length || 0} 警告
                </span>
              </div>
              {(validation?.checks || []).map(item => (
                <div key={item.item} style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '5px 0', fontSize: 12.5 }}>
                  <span style={{ color: item.pass && !item.warn ? 'var(--pass)' : 'var(--review)' }}>{item.pass && !item.warn ? '✓' : '◐'}</span>
                  <span style={{ color: item.pass && !item.warn ? '#5C4A38' : '#8B7340' }}>{item.item}</span>
                  {item.warn && <span style={{ color: 'var(--review)', marginLeft: 'auto' }}>警告</span>}
                </div>
              ))}
              {!validation && <div className="empty">未获取到校验结果</div>}
            </div>
          </div>
        </div>
      )}

      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 16 }}>
        <span style={{ fontSize: 13.5, color: 'var(--ink-2)' }}>
          编辑产物随 Bundle 冻结 · 树状态 {data?.tree?.status || '—'} · 默认结论 {data?.tree?.defaultConclusion || '—'}
        </span>
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn" disabled={saving} onClick={() => reload(treeId, data?.sliceName)}>重新校验</button>
          <button className="btn primary" disabled={saving || !data} onClick={submitApproval}>提交审批</button>
        </div>
      </div>
    </div>
  );
}
