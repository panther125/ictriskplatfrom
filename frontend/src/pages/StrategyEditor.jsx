import React, { useEffect, useRef, useState } from 'react';
import { useParams, useSearchParams, useNavigate } from 'react-router-dom';
import { api } from '../api/client.js';
import { PageHeader, StatusTag } from '../components/Common.jsx';

const NODE_W = 176;
const NODE_H = 64;

/* 缩略图尺寸 */
const MM_W = 168;
const MM_H = 108;

const TYPE_LABEL = {
  START: '开始', BRANCH: '分支', CASE: '决策', CROSS: '交叉决策', NORMAL: '普通决策', END: '结束'
};
/* 六类节点原先挤在同一簇橙里，几乎分不开。按「结构 / 路由 / 三类规则」重新拉开明度与色相：
   结构件（开始·结束）用无彩墨褐往后退，路由件最深，三类规则沿橙→琥珀→沙金递减。 */
const TYPE_COLOR = {
  START: '#7A6A57', BRANCH: '#C2410C', CASE: '#E8740F', CROSS: '#9A6B1F', NORMAL: '#D9A03C', END: '#7A6A57'
};

/* 节点类型字形：13px 下靠轮廓而非颜色区分语义 */
function NodeGlyph({ type }) {
  const p = { fill: 'none', stroke: 'currentColor', strokeWidth: 1.5, strokeLinecap: 'round', strokeLinejoin: 'round' };
  return (
    <svg viewBox="0 0 12 12" width="13" height="13" aria-hidden="true" focusable="false">
      {type === 'START' && (
        <>
          <circle cx="6" cy="6" r="4.3" {...p} />
          <circle cx="6" cy="6" r="1.8" fill="currentColor" stroke="none" />
        </>
      )}
      {type === 'END' && (
        <>
          <rect x="1.8" y="1.8" width="8.4" height="8.4" rx="1.6" {...p} />
          <rect x="4.6" y="4.6" width="2.8" height="2.8" fill="currentColor" stroke="none" />
        </>
      )}
      {type === 'BRANCH' && <path d="M6 10.6 V6.4 M6 6.4 L2.4 2.6 M6 6.4 L9.6 2.6" {...p} />}
      {type === 'CASE' && <path d="M1.6 1.6h8.8v8.8H1.6z M1.6 4.5h8.8 M1.6 7.5h8.8" {...p} />}
      {type === 'CROSS' && <path d="M1.6 1.6h8.8v8.8H1.6z M1.6 4.5h8.8 M1.6 7.5h8.8 M4.5 1.6v8.8 M7.4 1.6v8.8" {...p} />}
      {type === 'NORMAL' && (
        <>
          <path d="M4.2 3.2h6.2 M4.2 6h6.2 M4.2 8.8h6.2" {...p} />
          <circle cx="2.1" cy="3.2" r=".85" fill="currentColor" stroke="none" />
          <circle cx="2.1" cy="6" r=".85" fill="currentColor" stroke="none" />
          <circle cx="2.1" cy="8.8" r=".85" fill="currentColor" stroke="none" />
        </>
      )}
    </svg>
  );
}

/* 节点面板：分支 / 决策(case-when) / 交叉决策 / 普通决策 + 开始 / 结束 */
const PALETTE = [
  {
    group: '基础',
    items: [
      { type: 'START', name: '开始', desc: '流程入口 · 全局唯一' },
      { type: 'END', name: '结束', desc: '流程出口 · 全局唯一' }
    ]
  },
  {
    group: '决策节点',
    items: [
      { type: 'BRANCH', name: '分支', desc: 'if / elseif / else 分流，其余节点无 else' },
      { type: 'CASE', name: '决策', desc: 'Excel 行表：按条件列逐行匹配' },
      { type: 'CROSS', name: '交叉决策', desc: '同时命中首行与首列才执行' },
      { type: 'NORMAL', name: '普通决策', desc: '直接填写规则，命中即执行' }
    ]
  }
];

const PARAM_TYPES = ['STRING', 'INTEGER', 'LONG', 'DECIMAL', 'BOOLEAN', 'DATETIME'];

/* ---------------- 纯函数工具 ---------------- */

const snap4 = (v) => Math.round(v / 4) * 4;
const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

function nextKey(nodes) {
  let max = 0;
  nodes.forEach((n) => {
    const m = /^n_(\d+)$/.exec(n.nodeKey || '');
    if (m) max = Math.max(max, Number(m[1]));
  });
  return `n_${String(max + 1).padStart(3, '0')}`;
}

function defaultTitle(type) {
  return { START: '开始', END: '结束', BRANCH: '分支', CASE: '决策', CROSS: '交叉决策', NORMAL: '普通决策' }[type] || type;
}

function defaultConfig(type) {
  switch (type) {
    case 'START':
      return { inputs: [], outputs: [] };
    case 'BRANCH':
      return { conditions: [{ expr: '', label: '条件 1' }], elseLabel: '其余' };
    case 'CASE':
      return { rows: [{ cond: '', outputs: '', note: '' }] };
    case 'CROSS':
      return { rowVar: '', colVar: '', rowValues: ['行值1', '行值2'], colValues: ['列值1', '列值2'], cells: [['', ''], ['', '']] };
    case 'NORMAL':
      return { rules: [{ cond: '', outputs: '' }] };
    default:
      return {};
  }
}

/* 旧版结论枚举 → o. 赋值文本（仅迁移一次，加载即展开为可见文本） */
function legacyRowOutputs(r) {
  const lines = [];
  if (r.conclusion) lines.push(`o.finalDecision = '${r.conclusion}'`);
  if (r.reason) lines.push(`o.reasonCode = '${r.reason}'`);
  return lines.join('\n');
}

/* 后端返回的 config 可能缺字段，入库前统一补齐为编辑器契约结构 */
function normalizeNode(n) {
  const cfg = n.config || {};
  const base = { nodeKey: n.nodeKey, nodeType: n.nodeType, title: n.title || '', x: n.x || 0, y: n.y || 0 };
  if (n.nodeType === 'START') {
    return {
      ...base, config: {
        inputs: (cfg.inputs || []).map((v) => ({ name: v.name || '', type: v.type || 'STRING', required: !!v.required, defaultValue: v.defaultValue ?? '', desc: v.desc || '' })),
        outputs: (cfg.outputs || []).map((v) => ({ name: v.name || '', type: v.type || 'STRING', desc: v.desc || '' }))
      }
    };
  }
  if (n.nodeType === 'BRANCH') {
    return { ...base, config: { conditions: (cfg.conditions || []).map((c) => ({ expr: c.expr || '', label: c.label || '' })), elseLabel: cfg.elseLabel ?? '其余' } };
  }
  if (n.nodeType === 'CASE') {
    return { ...base, config: { rows: (cfg.rows || []).map((r) => ({ cond: r.cond || '', outputs: r.outputs ?? legacyRowOutputs(r), note: r.note || '' })) } };
  }
  if (n.nodeType === 'CROSS') {
    const rowValues = cfg.rowValues && cfg.rowValues.length ? [...cfg.rowValues] : ['行值1', '行值2'];
    const colValues = cfg.colValues && cfg.colValues.length ? [...cfg.colValues] : ['列值1', '列值2'];
    /* 旧版单元格是结论枚举（如 REJECT），加载时同样展开为 o. 赋值文本 */
    const cellText = (v) => {
      if (!v) return '';
      return /^[A-Z][A-Z0-9_]{1,31}$/.test(v) ? `o.finalDecision = '${v}'` : v;
    };
    const cells = rowValues.map((_, ri) => colValues.map((_, ci) => cellText(cfg.cells && cfg.cells[ri] && cfg.cells[ri][ci])));
    return { ...base, config: { rowVar: cfg.rowVar || '', colVar: cfg.colVar || '', rowValues, colValues, cells } };
  }
  if (n.nodeType === 'NORMAL') {
    return { ...base, config: { rules: (cfg.rules || []).map((r) => ({ cond: r.cond || '', outputs: r.outputs ?? legacyRowOutputs(r) })) } };
  }
  return { ...base, config: {} };
}

function branchPorts(node) {
  return (node.config.conditions || []).map((_, i) => `c${i}`).concat(['else']);
}

function outPortPos(node, port) {
  if (node.nodeType === 'BRANCH') {
    const ports = branchPorts(node);
    const idx = Math.max(0, ports.indexOf(port));
    return { x: node.x + (NODE_W * (idx + 1)) / (ports.length + 1), y: node.y + NODE_H };
  }
  return { x: node.x + NODE_W / 2, y: node.y + NODE_H };
}

function inPortPos(node) {
  return { x: node.x + NODE_W / 2, y: node.y };
}

function edgeLabelOf(edge, nodes) {
  const from = nodes.find((n) => n.nodeKey === edge.fromNode);
  if (!from || from.nodeType !== 'BRANCH') return edge.label || null;
  if (edge.fromPort === 'else') return from.config.elseLabel || '其余';
  const idx = Number(String(edge.fromPort || '').replace('c', ''));
  const c = from.config.conditions[idx];
  return c ? c.label || c.expr || `条件 ${idx + 1}` : null;
}

/* 表达式引用扫描：c.入参 / f.特征 / o.出参 */
const REF_RE = /(?:^|[^A-Za-z0-9_.])([cfo])\.([A-Za-z_][A-Za-z0-9_]*)/g;
function refsIn(text) {
  const out = new Set();
  let m;
  REF_RE.lastIndex = 0;
  while ((m = REF_RE.exec(text || ''))) out.add(`${m[1]}.${m[2]}`);
  return out;
}

/* 该节点所有表达式文本（用于引用校验与出参统计） */
function nodeTexts(node) {
  const c = node.config || {};
  switch (node.nodeType) {
    case 'BRANCH': return (c.conditions || []).map((x) => x.expr || '');
    case 'CASE': return (c.rows || []).flatMap((r) => [r.cond || '', r.outputs || '']);
    case 'CROSS': return [c.rowVar || '', c.colVar || '', ...(c.cells || []).flat()];
    case 'NORMAL': return (c.rules || []).flatMap((r) => [r.cond || '', r.outputs || '']);
    default: return [];
  }
}

function startNodeOf(nodes) {
  return nodes.find((n) => n.nodeType === 'START') || null;
}

/* 在最近聚焦的输入框光标处插入 token（兼容 React 受控组件：native setter + input 事件） */
function insertInto(el, text) {
  if (!el) return;
  const proto = el.tagName === 'TEXTAREA' ? window.HTMLTextAreaElement.prototype : window.HTMLInputElement.prototype;
  const setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
  const s = el.selectionStart ?? el.value.length;
  const e = el.selectionEnd ?? el.value.length;
  setter.call(el, el.value.slice(0, s) + text + el.value.slice(e));
  el.selectionStart = el.selectionEnd = s + text.length;
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.focus();
}

function nodeSub(node) {
  const c = node.config || {};
  switch (node.nodeType) {
    case 'START': return `${(c.inputs || []).length} 入参 · ${(c.outputs || []).length} 出参`;
    case 'END': return '输出契约 · 来自开始节点';
    case 'BRANCH': return `if/else · ${c.conditions.length} 个条件`;
    case 'CASE': return `${c.rows.length} 行规则 · 首行优先`;
    case 'CROSS': return `${c.rowValues.length} × ${c.colValues.length} 矩阵`;
    case 'NORMAL': return `${c.rules.length} 条规则`;
    default: return '';
  }
}

/* 规则摘要刻度：一条规则一个刻度，实心 = 已填写。
   画布的核心价值是「看懂整条策略」，所以把节点完成度直接画在卡片上，
   不用逐个点开属性面板才知道哪个节点还是空的。 */
function nodeTicks(node) {
  const c = node.config || {};
  const filled = (x) => !!(x && String(x).trim());
  switch (node.nodeType) {
    case 'BRANCH':
      /* else 出口始终存在且必定走到，恒为实心 */
      return (c.conditions || []).map((x) => filled(x.expr)).concat([true]);
    case 'CASE':
      return (c.rows || []).map((r) => filled(r.cond) || filled(r.outputs));
    case 'NORMAL':
      return (c.rules || []).map((r) => filled(r.cond) || filled(r.outputs));
    case 'CROSS':
      return (c.rowValues || []).map((_, ri) => ((c.cells || [])[ri] || []).some(filled));
    default:
      return [];
  }
}

/* 节点体检：漏配的规则行 + 未定义的 c./o./f. 引用。
   注意「普通决策」的条件留空是合法的兜底写法，只有连输出赋值一起空才算漏配。 */
function nodeIssues(node, nodes, features) {
  const out = [];
  const c = node.config || {};
  const has = (x) => !!(x && String(x).trim());
  const empty = [];
  if (node.nodeType === 'BRANCH') {
    (c.conditions || []).forEach((x, i) => { if (!has(x.expr)) empty.push(`条件 ${i + 1}`); });
  }
  if (node.nodeType === 'CASE') {
    (c.rows || []).forEach((r, i) => { if (!has(r.cond) && !has(r.outputs)) empty.push(`第 ${i + 1} 行`); });
  }
  if (node.nodeType === 'NORMAL') {
    (c.rules || []).forEach((r, i) => { if (!has(r.cond) && !has(r.outputs)) empty.push(`规则 ${i + 1}`); });
  }
  if (empty.length) out.push(`${empty.join('、')} 未填写`);

  const start = startNodeOf(nodes);
  const inNames = new Set(start ? (start.config.inputs || []).map((v) => v.name) : []);
  const outNames = new Set(start ? (start.config.outputs || []).map((v) => v.name) : []);
  const refs = new Set();
  nodeTexts(node).forEach((t) => refsIn(t).forEach((r) => refs.add(r)));
  const bad = [...refs].filter((r) => (
    (r.startsWith('c.') && !inNames.has(r.slice(2)))
    || (r.startsWith('o.') && !outNames.has(r.slice(2)))
    || (features && r.startsWith('f.') && !features.some((f) => f.code === r.slice(2)))
  ));
  if (bad.length) out.push(`引用未定义：${bad.join('、')}`);
  return out;
}

/* 图的包围盒：缩略图与「适应画布」共用 */
function graphBounds(nodes) {
  if (!nodes.length) return { x0: 0, y0: 0, x1: 900, y1: 620 };
  let x0 = Infinity; let y0 = Infinity; let x1 = -Infinity; let y1 = -Infinity;
  nodes.forEach((n) => {
    x0 = Math.min(x0, n.x); y0 = Math.min(y0, n.y);
    x1 = Math.max(x1, n.x + NODE_W); y1 = Math.max(y1, n.y + NODE_H);
  });
  const pad = 60;
  return { x0: x0 - pad, y0: y0 - pad, x1: x1 + pad, y1: y1 + pad };
}

/* 自动布局：Kahn 拓扑定层级，同层水平铺开 */
function autoLayout(nodes, edges) {
  const succ = {};
  const indeg = {};
  nodes.forEach((n) => { succ[n.nodeKey] = []; indeg[n.nodeKey] = 0; });
  edges.forEach((e) => {
    if (succ[e.fromNode] && indeg[e.toNode] !== undefined) {
      succ[e.fromNode].push(e.toNode);
      indeg[e.toNode] += 1;
    }
  });
  const rank = {};
  const queue = nodes.filter((n) => indeg[n.nodeKey] === 0).map((n) => n.nodeKey);
  queue.forEach((k) => { rank[k] = 0; });
  const q = [...queue];
  while (q.length) {
    const k = q.shift();
    succ[k].forEach((t) => {
      rank[t] = Math.max(rank[t] ?? 0, (rank[k] || 0) + 1);
      indeg[t] -= 1;
      if (indeg[t] === 0) q.push(t);
    });
  }
  nodes.forEach((n) => { if (rank[n.nodeKey] === undefined) rank[n.nodeKey] = 0; });
  const groups = {};
  nodes.forEach((n) => { (groups[rank[n.nodeKey]] = groups[rank[n.nodeKey]] || []).push(n); });
  const out = [];
  Object.keys(groups).map(Number).sort((a, b) => a - b).forEach((r) => {
    const g = groups[r];
    g.forEach((n, i) => {
      const x = 520 - NODE_W / 2 + (i - (g.length - 1) / 2) * 230;
      out.push({ ...n, x: snap4(x), y: 60 + r * 116 });
    });
  });
  return out;
}

function fmtTime(t) {
  if (!t) return '—';
  const d = new Date(t);
  const p = (n) => String(n).padStart(2, '0');
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

/* ---------------- 主组件 ---------------- */

/* 只读模式的统一提示：任何编辑尝试都给出同一句话，并指明出路 */
const READ_ONLY_HINT = '只读查看：该版本内容已定版，画布不能修改。如需调整，请回版本管理页用「新增版本」继承后再编排。';

export default function StrategyEditor() {
  const { strategyId } = useParams();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  /* ?versionId=x 打开指定版本；?mode=view 只读查看（已定版版本，画布可缩放不可修改） */
  const versionId = searchParams.get('versionId');
  const readOnly = searchParams.get('mode') === 'view';
  /* 新增版本跳转过来时带的继承源（跳转是重新加载，DTO 里的 derivedFromVersionNo 拿不到，只能靠 URL 传递） */
  const inheritedFrom = searchParams.get('inherited');
  const [meta, setMeta] = useState(null);
  const [nodes, setNodes] = useState([]);
  const [edges, setEdges] = useState([]);
  const [view, setView] = useState({ zoom: 1, pan: { x: 40, y: 20 } });
  const [sel, setSel] = useState(null);
  const [ghost, setGhost] = useState(null);
  const [gestKind, setGestKind] = useState(null);
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState(null);
  const [features, setFeatures] = useState(null);
  /* 画布可视区尺寸：缩略图定位与「适应画布」都依赖它 */
  const [vpSize, setVpSize] = useState({ w: 900, h: 600 });
  /* 连线拖拽过程中悬停到的节点，用于目标高亮反馈 */
  const [hoverKey, setHoverKey] = useState(null);

  const viewportRef = useRef(null);
  const gestRef = useRef(null);
  const nodesRef = useRef([]);
  const edgesRef = useRef([]);
  const viewRef = useRef(view);
  const selRef = useRef(null);
  const lastFieldRef = useRef(null);
  const mmBoxRef = useRef(null);
  const mmDragRef = useRef(false);
  const vpSizeRef = useRef(vpSize);

  useEffect(() => { nodesRef.current = nodes; }, [nodes]);
  useEffect(() => { edgesRef.current = edges; }, [edges]);
  useEffect(() => { viewRef.current = view; }, [view]);
  useEffect(() => { selRef.current = sel; }, [sel]);
  useEffect(() => { vpSizeRef.current = vpSize; }, [vpSize]);

  /* 选中与 ref 同步赋值：键盘删除发生在同一事件帧内时也能读到最新选中 */
  const select = (s) => {
    selRef.current = s;
    setSel(s);
  };

  /* 记录最近交互的输入框，供变量 chips 插入（focus 在部分环境不可靠，鼠标/键盘事件兜底） */
  const recordField = (e) => {
    const t = e.target;
    if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA')) lastFieldRef.current = t;
  };

  const applyGraph = (g) => {
    setMeta({
      decisionId: g.decisionId, strategyCode: g.strategyCode, strategyName: g.strategyName,
      versionNo: g.versionNo, status: g.status, updatedAt: g.updatedAt,
      canvasSource: g.canvasSource, canvasNote: g.canvasNote,
      derivedFromVersionNo: g.derivedFromVersionNo
    });
    setNodes((g.nodes || []).map(normalizeNode));
    setEdges((g.edges || []).map((e) => ({ fromNode: e.fromNode, fromPort: e.fromPort || null, toNode: e.toNode, label: e.label || null })));
    select(null);
    setDirty(false);
    const inherited = g.derivedFromVersionNo || inheritedFrom;
    if (inherited) {
      setNotice({ kind: 'ok', text: `已基于 ${inherited} 新建草稿 ${g.versionNo}，画布配置已继承，可直接修改后保存。` });
    }
  };

  useEffect(() => {
    let alive = true;
    /* 只读查看走版本口径（不带副作用）；编排走草稿口径（可派生新草稿） */
    if (readOnly && !versionId) {
      setNotice({ kind: 'err', text: '查看画布缺少版本参数，请从版本管理页的「查看」进入。' });
      return () => { alive = false; };
    }
    const req = readOnly
      ? api.strategies.graph(versionId)
      : api.decisions.byStrategy(strategyId, versionId);
    req
      .then((g) => { if (alive) applyGraph(g); })
      .catch((err) => { if (alive) setNotice({ kind: 'err', text: `加载失败：${err.message}` }); });
    return () => { alive = false; };
  }, [strategyId, versionId, readOnly]);

  /* 已上线特征注册表：供 f. 变量 chips 与引用校验使用（取不到则跳过 f. 校验） */
  useEffect(() => {
    let alive = true;
    api.features.list()
      .then((list) => { if (alive) setFeatures((list || []).filter((f) => f.status === 'PUBLISHED').map((f) => ({ code: f.code, name: f.name }))); })
      .catch(() => { if (alive) setFeatures([]); });
    return () => { alive = false; };
  }, []);

  /* 跟踪画布可视区尺寸（挂载于 meta 就绪之后，故依赖 meta） */
  useEffect(() => {
    const el = viewportRef.current;
    if (!el) return undefined;
    const sync = () => setVpSize({ w: el.clientWidth, h: el.clientHeight });
    sync();
    if (typeof ResizeObserver === 'undefined') return undefined;
    const ro = new ResizeObserver(sync);
    ro.observe(el);
    return () => ro.disconnect();
  }, [meta]);

  /* 画布坐标换算（只依赖 ref，供全局手势回调使用） */
  const toCanvas = (clientX, clientY) => {
    const rect = viewportRef.current.getBoundingClientRect();
    const v = viewRef.current;
    return { x: (clientX - rect.left - v.pan.x) / v.zoom, y: (clientY - rect.top - v.pan.y) / v.zoom };
  };

  const addNodeAt = (type, clientX, clientY) => {
    if ((type === 'START' || type === 'END') && nodesRef.current.some((n) => n.nodeType === type)) {
      setNotice({ kind: 'err', text: type === 'START' ? '画布中只允许一个开始节点' : '画布中只允许一个结束节点' });
      return;
    }
    const p = toCanvas(clientX, clientY);
    const node = {
      nodeKey: nextKey(nodesRef.current), nodeType: type, title: defaultTitle(type),
      x: snap4(p.x - NODE_W / 2), y: snap4(p.y - NODE_H / 2), config: defaultConfig(type)
    };
    setNodes((ns) => [...ns, node]);
    select({ kind: 'node', key: node.nodeKey });
    setDirty(true);
    setNotice(null);
  };

  const tryConnect = (from, fromPort, target) => {
    if (target === from) { setNotice({ kind: 'err', text: '不能把节点连到自身' }); return; }
    const t = nodesRef.current.find((n) => n.nodeKey === target);
    if (!t) return;
    if (t.nodeType === 'START') { setNotice({ kind: 'err', text: '开始节点没有入口，不能作为连线目标' }); return; }
    setEdges((es) => [
      ...es.filter((x) => !(x.fromNode === from && (x.fromPort || null) === (fromPort || null))),
      { fromNode: from, fromPort: fromPort || null, toNode: target }
    ]);
    setDirty(true);
    setNotice(null);
  };

  const delNodeByKey = (key) => {
    setNodes((ns) => ns.filter((n) => n.nodeKey !== key));
    setEdges((es) => es.filter((e) => e.fromNode !== key && e.toNode !== key));
    select(null);
    setDirty(true);
  };

  const delEdge = (e0) => {
    setEdges((es) => es.filter((e) => !(e.fromNode === e0.fromNode && (e.fromPort || null) === (e0.fromPort || null))));
    select(null);
    setDirty(true);
  };

  const patchNode = (key, patch) => {
    setNodes((ns) => ns.map((n) => (n.nodeKey === key ? { ...n, ...patch } : n)));
    setDirty(true);
  };

  /* ---------------- 全局手势：拖拽 / 连线 / 平移 ---------------- */
  useEffect(() => {
    const onMove = (e) => {
      const g = gestRef.current;
      if (!g) return;
      if (g.kind === 'palette') {
        const r = viewportRef.current.getBoundingClientRect();
        const inside = e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom;
        setGhost({
          kind: 'palette', type: g.type, cx: e.clientX, cy: e.clientY,
          lx: e.clientX - r.left, ly: e.clientY - r.top, inside
        });
      } else if (g.kind === 'node') {
        if (!g.moved && Math.abs(e.clientX - g.sx) + Math.abs(e.clientY - g.sy) > 3) g.moved = true;
        if (g.moved) {
          const z = viewRef.current.zoom;
          const nx = snap4(g.ox + (e.clientX - g.sx) / z);
          const ny = snap4(g.oy + (e.clientY - g.sy) / z);
          setNodes((ns) => ns.map((n) => (n.nodeKey === g.key ? { ...n, x: nx, y: ny } : n)));
        }
      } else if (g.kind === 'connect') {
        const p = toCanvas(e.clientX, e.clientY);
        setGhost({ kind: 'connect', from: g.from, fromPort: g.fromPort, x: p.x, y: p.y });
        /* 实时反馈落点是否可连：开始节点无入口、自身不可连 */
        const el = document.elementFromPoint(e.clientX, e.clientY);
        const box = el && el.closest && el.closest('[data-node-key]');
        setHoverKey(box ? box.getAttribute('data-node-key') : null);
      } else if (g.kind === 'pan') {
        setView((v) => ({ ...v, pan: { x: g.px + (e.clientX - g.sx), y: g.py + (e.clientY - g.sy) } }));
      }
    };
    const onUp = (e) => {
      const g = gestRef.current;
      if (!g) return;
      gestRef.current = null;
      setGhost(null);
      setGestKind(null);
      setHoverKey(null);
      if (g.kind === 'palette') {
        const rect = viewportRef.current.getBoundingClientRect();
        if (e.clientX >= rect.left && e.clientX <= rect.right && e.clientY >= rect.top && e.clientY <= rect.bottom) {
          addNodeAt(g.type, e.clientX, e.clientY);
        }
      } else if (g.kind === 'node') {
        if (g.moved) setDirty(true);
      } else if (g.kind === 'connect') {
        const el = document.elementFromPoint(e.clientX, e.clientY);
        const box = el && el.closest && el.closest('[data-node-key]');
        const target = box && box.getAttribute('data-node-key');
        if (target) tryConnect(g.from, g.fromPort, target);
      }
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
    return () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
  }, []);

  /* Delete 删除选中（输入控件内不拦截；只读模式一律不删） */
  useEffect(() => {
    const onKey = (e) => {
      if (e.key === 'Escape') { select(null); return; }
      if (readOnly) return;
      if (e.key !== 'Delete' && e.key !== 'Backspace') return;
      const t = e.target;
      const tag = (t && t.tagName) || '';
      if (['INPUT', 'SELECT', 'TEXTAREA'].includes(tag) || (t && t.isContentEditable)) return;
      const s = selRef.current;
      if (!s) return;
      e.preventDefault();
      if (s.kind === 'node') delNodeByKey(s.key);
      if (s.kind === 'edge') delEdge(s);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  /* ---------------- 画面交互 ---------------- */

  const onCanvasMouseDown = (e) => {
    if (e.target.closest('[data-node-key]') || e.target.closest('.hud') || e.target.closest('.minimap')) return;
    select(null);
    gestRef.current = { kind: 'pan', sx: e.clientX, sy: e.clientY, px: viewRef.current.pan.x, py: viewRef.current.pan.y };
    setGestKind('pan');
  };

  const startPaletteDrag = (e, type) => {
    if (readOnly) { setNotice({ kind: 'err', text: READ_ONLY_HINT }); return; }
    if (e.button !== 0) return;
    e.preventDefault();
    const r = viewportRef.current.getBoundingClientRect();
    gestRef.current = { kind: 'palette', type };
    setGhost({
      kind: 'palette', type, cx: e.clientX, cy: e.clientY,
      lx: e.clientX - r.left, ly: e.clientY - r.top,
      inside: e.clientX >= r.left && e.clientX <= r.right && e.clientY >= r.top && e.clientY <= r.bottom
    });
    setGestKind('palette');
  };

  const startNodeDrag = (e, n) => {
    if (e.button !== 0) return;
    e.stopPropagation();
    select({ kind: 'node', key: n.nodeKey });
    if (readOnly) return;
    gestRef.current = { kind: 'node', key: n.nodeKey, sx: e.clientX, sy: e.clientY, ox: n.x, oy: n.y, moved: false };
    setGestKind('node');
  };

  const startConnect = (e, n, port) => {
    if (readOnly) { setNotice({ kind: 'err', text: READ_ONLY_HINT }); return; }
    if (e.button !== 0) return;
    e.stopPropagation();
    e.preventDefault();
    const p = outPortPos(n, port);
    gestRef.current = { kind: 'connect', from: n.nodeKey, fromPort: port };
    setGhost({ kind: 'connect', from: n.nodeKey, fromPort: port, x: p.x, y: p.y });
    setGestKind('connect');
  };

  const zoomBy = (f) => {
    setView((v) => {
      const z2 = clamp(v.zoom * f, 0.5, 1.4);
      if (z2 === v.zoom) return v;
      const rect = viewportRef.current.getBoundingClientRect();
      const cx = rect.width / 2;
      const cy = rect.height / 2;
      const wx = (cx - v.pan.x) / v.zoom;
      const wy = (cy - v.pan.y) / v.zoom;
      return { zoom: z2, pan: { x: cx - wx * z2, y: cy - wy * z2 } };
    });
  };

  const doAutoLayout = () => {
    setNodes(autoLayout(nodesRef.current, edgesRef.current));
    setDirty(true);
  };

  /* 适应画布：缩放到全图可见并居中 */
  const fitView = () => {
    const b = graphBounds(nodesRef.current);
    const { w, h } = vpSizeRef.current;
    const z = clamp(Math.min(w / (b.x1 - b.x0), h / (b.y1 - b.y0)), 0.4, 1.15);
    setView({
      zoom: z,
      pan: {
        x: (w - (b.x1 - b.x0) * z) / 2 - b.x0 * z,
        y: (h - (b.y1 - b.y0) * z) / 2 - b.y0 * z
      }
    });
  };

  /* 缩略图定位：把点击处的图坐标移到可视区中心 */
  const mmPanTo = (clientX, clientY) => {
    const el = mmBoxRef.current;
    if (!el) return;
    const r = el.getBoundingClientRect();
    const b = graphBounds(nodesRef.current);
    const s = Math.min(MM_W / (b.x1 - b.x0), MM_H / (b.y1 - b.y0));
    const ox = (MM_W - (b.x1 - b.x0) * s) / 2;
    const oy = (MM_H - (b.y1 - b.y0) * s) / 2;
    const wx = (clientX - r.left - ox) / s + b.x0;
    const wy = (clientY - r.top - oy) / s + b.y0;
    const { w, h } = vpSizeRef.current;
    const z = viewRef.current.zoom;
    setView((v) => ({ ...v, pan: { x: w / 2 - wx * z, y: h / 2 - wy * z } }));
  };

  const mmDown = (e) => {
    if (e.button !== 0) return;
    e.stopPropagation();
    e.preventDefault();
    mmDragRef.current = true;
    mmPanTo(e.clientX, e.clientY);
  };

  /* 缩略图拖拽平移：独立监听，不干扰画布手势 */
  useEffect(() => {
    const move = (e) => { if (mmDragRef.current) mmPanTo(e.clientX, e.clientY); };
    const up = () => { mmDragRef.current = false; };
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', up);
    return () => {
      window.removeEventListener('mousemove', move);
      window.removeEventListener('mouseup', up);
    };
  }, []);

  const onSave = async () => {
    if (!meta || saving || readOnly) return;
    setSaving(true);
    try {
      const g = await api.decisions.saveGraph(meta.decisionId, {
        nodes: nodesRef.current.map((n) => ({ nodeKey: n.nodeKey, nodeType: n.nodeType, title: n.title, x: n.x, y: n.y, config: n.config })),
        edges: edgesRef.current.map((e) => ({ fromNode: e.fromNode, fromPort: e.fromPort || null, toNode: e.toNode, label: edgeLabelOf(e, nodesRef.current) }))
      });
      applyGraph(g);
      setNotice({ kind: 'ok', text: `已保存到草稿 ${g.versionNo} · ${fmtTime(g.updatedAt)}` });
    } catch (err) {
      setNotice({ kind: 'err', text: `保存失败：${err.message}` });
    } finally {
      setSaving(false);
    }
  };

  if (!meta) {
    return (
      <div>
        <PageHeader title="策略编排" sub="配置态 / 策略编排" />
        {notice
          ? <div className="warn-bar" style={{ background: '#FBEAE8', borderColor: '#EFB3AE', color: '#BE3A2B' }}>⚠ {notice.text}</div>
          : <div className="empty">加载中…</div>}
      </div>
    );
  }

  const selNodeObj = sel && sel.kind === 'node' ? nodes.find((n) => n.nodeKey === sel.key) : null;
  const selEdgeObj = sel && sel.kind === 'edge'
    ? edges.find((e) => e.fromNode === sel.fromNode && (e.fromPort || null) === (sel.fromPort || null) && e.toNode === sel.toNode) || null
    : null;

  /* 缩略图几何：图坐标 → 缩略图坐标 */
  const gb = graphBounds(nodes);
  const mmS = Math.min(MM_W / (gb.x1 - gb.x0), MM_H / (gb.y1 - gb.y0));
  const mmOx = (MM_W - (gb.x1 - gb.x0) * mmS) / 2;
  const mmOy = (MM_H - (gb.y1 - gb.y0) * mmS) / 2;
  const mmVp = {
    x: mmOx + (-view.pan.x / view.zoom - gb.x0) * mmS,
    y: mmOy + (-view.pan.y / view.zoom - gb.y0) * mmS,
    w: (vpSize.w / view.zoom) * mmS,
    h: (vpSize.h / view.zoom) * mmS
  };
  const issueCount = nodes.filter((n) => nodeIssues(n, nodes, features).length > 0).length;

  return (
    <div>
      <PageHeader
        title={readOnly ? '策略编排 · 查看' : '策略编排'}
        sub={`配置态 / 策略管理 / 版本管理 / ${readOnly ? '查看 ' : '编排 '}${meta.strategyCode || `#${strategyId}`}${meta.versionNo ? ` · ${meta.versionNo}` : ''}`}
        actions={
          readOnly ? (
            <>
              <span className="tag tag-published">只读</span>
              <StatusTag text={meta.status === 'DRAFT' ? '草稿' : meta.status} />
              <button className="btn sm" onClick={() => navigate(`/strategy/${strategyId}/versions`)}>← 返回版本管理</button>
            </>
          ) : (
            <>
              {dirty && <span className="tag tag-warn">未保存</span>}
              <StatusTag text={meta.status === 'DRAFT' ? '草稿' : meta.status} />
              <button className="btn primary" onClick={onSave} disabled={saving || !dirty}>
                {saving ? '保存中…' : '保存'}
              </button>
            </>
          )
        }
      />

      <div className="card" style={{ padding: '10px 16px', marginBottom: 12, display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
        <b className="mono">{meta.strategyCode}</b>
        <span style={{ fontSize: 12.5, color: 'var(--ink-3)' }}>{meta.strategyName}</span>
        <span className={readOnly ? 'tag tag-published' : 'tag tag-draft'}>{meta.versionNo || '—'} · {readOnly ? '已定版' : '草稿'}</span>
        <span className="mono" style={{ fontSize: 12.5, color: 'var(--ink-3)' }}>
          {readOnly ? `该版本画布 · ${nodes.length} 节点 / ${edges.length} 连线` : `上次保存 ${fmtTime(meta.updatedAt)}`}
        </span>
      </div>

      {readOnly && (
        <div className="warn-bar" style={{ marginBottom: 12, alignItems: 'flex-start' }}>
          <span>
            <b style={{ color: 'var(--reject)' }}>只读查看</b>{' '}
            版本 <b className="mono">{meta.versionNo || '—'}</b> 已上线，画布内容已定版——可以缩放、平移、选中查看每个节点的配置，但不能修改。
            {meta.canvasSource === 'FALLBACK' && meta.canvasNote && <><br />{meta.canvasNote}</>}
            {meta.canvasSource === 'EMPTY' && meta.canvasNote && <><br />{meta.canvasNote}</>}
            <br />如需调整：回版本管理页点「新增版本」，新版本会继承这份画布配置，改完再发布。
          </span>
          <button className="btn sm" onClick={() => navigate(`/strategy/${strategyId}/versions`)}>去新增版本</button>
        </div>
      )}

      {notice && (
        notice.kind === 'ok'
          ? <div className="ok-bar" style={{ marginBottom: 12 }}>✓ {notice.text}</div>
          : <div className="warn-bar" style={{ marginBottom: 12, background: '#FBEAE8', borderColor: '#EFB3AE', color: '#BE3A2B' }}>⚠ {notice.text}</div>
      )}

      <div style={{ overflowX: 'auto' }}>
      <div style={{ display: 'grid', gridTemplateColumns: '188px 1fr 400px', gap: 14, minWidth: 1100 }}>
        {/* 节点面板 */}
        <div className="card" style={{ padding: 12, alignSelf: 'start' }}>
          {PALETTE.map((g) => (
            <div key={g.group} style={{ marginBottom: 14 }}>
              <div className="p-group">{g.group}</div>
              {g.items.map((item) => (
                <div
                  key={item.type}
                  className="palette-item"
                  title={readOnly ? READ_ONLY_HINT : `拖拽「${item.name}」到画布`}
                  style={readOnly ? { opacity: 0.5, cursor: 'not-allowed' } : undefined}
                  onMouseDown={(e) => startPaletteDrag(e, item.type)}
                >
                  <span className="p-glyph" style={{ color: TYPE_COLOR[item.type] }}><NodeGlyph type={item.type} /></span>
                  <div style={{ minWidth: 0 }}>
                    <div className="p-name">{item.name}</div>
                    <div className="p-sub">{item.desc}</div>
                  </div>
                </div>
              ))}
            </div>
          ))}
          <div style={{ fontSize: 12, color: 'var(--ink-3)', lineHeight: 1.7, marginTop: 8, padding: '0 4px', borderTop: '1px solid var(--stroke-weak)', paddingTop: 10 }}>
            {readOnly
              ? '只读查看：可缩放、平移画布，点选节点查看其规则配置；节点清单不可拖入画布。'
              : '拖拽节点到画布；从节点底部端口拖出连线；拖拽空白处平移画布。'}
            <div style={{ marginTop: 8 }}>
              <b style={{ color: 'var(--ink-2)' }}>表达式约定</b><br />
              <span className="mono" style={{ color: '#B45309' }}>c.</span> 策略入参 · <span className="mono" style={{ color: '#B45309' }}>f.</span> 特征编码<br />
              <span className="mono" style={{ color: '#B45309' }}>o.</span> 出参赋值（如 <span className="mono">o.finalDecision = 'REJECT'</span>）<br />
              入参 / 出参在「开始」节点定义；所有 <span className="mono">o.</span> 赋值最终组装为 JSON map 输出。
            </div>
          </div>
        </div>

        {/* 画布 */}
        <div
          ref={viewportRef}
          className="canvas-area"
          style={{
            height: 'calc(100vh - 300px)', minHeight: 560, overflow: 'hidden',
            cursor: gestKind === 'pan' ? 'grabbing' : 'default',
            backgroundPosition: `${view.pan.x}px ${view.pan.y}px, ${view.pan.x}px ${view.pan.y}px`,
            backgroundSize: `${20 * view.zoom}px ${20 * view.zoom}px, ${100 * view.zoom}px ${100 * view.zoom}px`
          }}
          onMouseDown={onCanvasMouseDown}
        >
          <div style={{ position: 'absolute', left: 0, top: 0, width: 0, height: 0, transform: `translate(${view.pan.x}px, ${view.pan.y}px) scale(${view.zoom})`, transformOrigin: '0 0' }}>
            <svg width={4000} height={4000} style={{ position: 'absolute', left: 0, top: 0, overflow: 'visible', pointerEvents: 'none' }}>
              <defs>
                <marker id="arw" viewBox="0 0 10 10" refX="8.5" refY="5" markerWidth="7" markerHeight="7" orient="auto">
                  <path d="M0,0 L10,5 L0,10 z" fill="#B9A88F" />
                </marker>
                <marker id="arwHot" viewBox="0 0 10 10" refX="8.5" refY="5" markerWidth="7" markerHeight="7" orient="auto">
                  <path d="M0,0 L10,5 L0,10 z" fill="#C2410C" />
                </marker>
              </defs>
              {edges.map((e) => {
                const from = nodes.find((n) => n.nodeKey === e.fromNode);
                const to = nodes.find((n) => n.nodeKey === e.toNode);
                if (!from || !to) return null;
                const s = outPortPos(from, e.fromPort);
                const t = inPortPos(to);
                const d = `M ${s.x} ${s.y} C ${s.x} ${s.y + 46}, ${t.x} ${t.y - 46}, ${t.x} ${t.y}`;
                const isSel = selEdgeObj && selEdgeObj.fromNode === e.fromNode && (selEdgeObj.fromPort || null) === (e.fromPort || null);
                const label = edgeLabelOf(e, nodes);
                /* else 是兜底路径而非命中路径，用虚线区分——这是图上真实存在的信息 */
                const isElse = e.fromPort === 'else';
                return (
                  <g
                    key={`${e.fromNode}-${e.fromPort || ''}-${e.toNode}`}
                    style={{ cursor: 'pointer' }}
                    onMouseDown={(ev) => { ev.stopPropagation(); select({ kind: 'edge', fromNode: e.fromNode, fromPort: e.fromPort, toNode: e.toNode }); }}
                  >
                    <path d={d} stroke="transparent" strokeWidth={14} fill="none" style={{ pointerEvents: 'all' }} />
                    <path
                      d={d} fill="none"
                      stroke={isSel ? '#C2410C' : '#C0AF97'}
                      strokeWidth={isSel ? 2.5 : 1.7}
                      strokeDasharray={isElse && !isSel ? '6 4' : undefined}
                      markerEnd={`url(#${isSel ? 'arwHot' : 'arw'})`}
                      style={{ pointerEvents: 'none' }}
                    />
                    {label && (
                      <text x={(s.x + t.x) / 2} y={(s.y + t.y) / 2 - 5} textAnchor="middle" fontSize="11"
                        fill={isSel ? '#C2410C' : '#5C4A38'} stroke="#FBF7F2" strokeWidth="4" paintOrder="stroke" style={{ pointerEvents: 'none' }}>
                        {label}
                      </text>
                    )}
                  </g>
                );
              })}
              {ghost && ghost.kind === 'connect' && (() => {
                const from = nodes.find((n) => n.nodeKey === ghost.from);
                if (!from) return null;
                const s = outPortPos(from, ghost.fromPort);
                return (
                  <path
                    d={`M ${s.x} ${s.y} C ${s.x} ${s.y + 46}, ${ghost.x} ${ghost.y - 46}, ${ghost.x} ${ghost.y}`}
                    fill="none" stroke="#C2410C" strokeWidth="1.6" strokeDasharray="5 4"
                    markerEnd="url(#arwHot)" style={{ pointerEvents: 'none' }}
                  />
                );
              })()}
            </svg>

            {nodes.map((n) => {
              const isSel = sel && sel.kind === 'node' && sel.key === n.nodeKey;
              const outPorts = n.nodeType === 'END' ? [] : (n.nodeType === 'BRANCH' ? branchPorts(n) : [null]);
              const connecting = !!(ghost && ghost.kind === 'connect');
              const isSrc = connecting && ghost.from === n.nodeKey;
              const isHover = connecting && hoverKey === n.nodeKey;
              /* 开始节点没有入口，连线时提前标灰，别等松手才报错 */
              const invalid = connecting && !isSrc && n.nodeType === 'START';
              const ticks = nodeTicks(n);
              const issues = nodeIssues(n, nodes, features);
              const cls = ['node-box', `ty-${n.nodeType.toLowerCase()}`]
                .concat(isSel ? ['sel'] : [])
                .concat(isHover && !isSrc && !invalid ? ['drop-ok'] : [])
                .concat(invalid ? ['drop-no'] : [])
                .concat(connecting && !isHover && !isSrc ? ['drop-dim'] : [])
                .join(' ');
              return (
                <div
                  key={n.nodeKey}
                  data-node-key={n.nodeKey}
                  className={cls}
                  style={{ left: n.x, top: n.y, height: NODE_H, '--nb-accent': TYPE_COLOR[n.nodeType] }}
                  onMouseDown={(e) => startNodeDrag(e, n)}
                >
                  <div className="nb-body">
                    <div className="nb-head">
                      <span className="nb-glyph"><NodeGlyph type={n.nodeType} /></span>
                      <span className="nb-title">{n.title}</span>
                      {issues.length > 0 && (
                        <span className="nb-warn" title={issues.join('；')} aria-label={`配置待完善：${issues.join('；')}`}>!</span>
                      )}
                    </div>
                    <div className="nb-meta">{nodeSub(n)}</div>
                    {ticks.length > 0 && (
                      <div className="nb-ticks">
                        {ticks.slice(0, 14).map((on, i) => (
                          <i key={i} className={`tick${on ? ' on' : ''}`} />
                        ))}
                        {ticks.length > 14 && <span className="ticks-more">+{ticks.length - 14}</span>}
                      </div>
                    )}
                  </div>

                  {n.nodeType !== 'START' && (
                    <div className="port-hit in" style={{ left: NODE_W / 2 - 9, top: -9 }}>
                      <span className="port-dot" />
                    </div>
                  )}
                  {outPorts.map((p) => {
                    const k = outPorts.length;
                    const idx = outPorts.indexOf(p);
                    const left = (n.nodeType === 'BRANCH' ? (NODE_W * (idx + 1)) / (k + 1) : NODE_W / 2) - 9;
                    return (
                      <div
                        key={p || 'out'}
                        className="port-hit"
                        style={{ left, top: NODE_H - 9 }}
                        onMouseDown={(e) => startConnect(e, n, p)}
                        title={p ? (p === 'else' ? 'else 出口' : `条件 ${p}`) : '出线端口'}
                      >
                        <span className="port-dot" />
                      </div>
                    );
                  })}

                  {/* 分支出口标签：不点开属性面板也能看出每条线走的是什么条件。
                      出口间距 = 176/(端口数+1)，超过 4 个后不足 36px 会挤在一起，此时让位给属性面板 */}
                  {n.nodeType === 'BRANCH' && outPorts.length <= 4 && outPorts.map((p, idx) => {
                    const txt = p === 'else'
                      ? (n.config.elseLabel || 'else')
                      : (((n.config.conditions || [])[idx] || {}).label || `c${idx}`);
                    const isSelPort = sel && sel.kind === 'edge' && sel.fromNode === n.nodeKey
                      && (sel.fromPort || null) === (p || null);
                    return (
                      <span
                        key={`lb-${p}`}
                        className={`port-label${p === 'else' ? ' is-else' : ''}${isSelPort ? ' on' : ''}`}
                        style={{ left: (NODE_W * (idx + 1)) / (outPorts.length + 1), top: NODE_H + 7 }}
                      >{txt}</span>
                    );
                  })}
                </div>
              );
            })}
          </div>

          {/* 落点十字准星：拖拽时明确「会落在哪」，而不是松手才知道 */}
          {ghost && ghost.kind === 'palette' && ghost.inside && (
            <div className="drop-guide" style={{ '--dg-c': TYPE_COLOR[ghost.type] }}>
              <span className="dg-h" style={{ top: ghost.ly }} />
              <span className="dg-v" style={{ left: ghost.lx }} />
              <span className="dg-node" style={{ left: ghost.lx, top: ghost.ly }}>
                <NodeGlyph type={ghost.type} />{TYPE_LABEL[ghost.type]}
              </span>
            </div>
          )}

          {/* 空画布引导：空态是一次邀请，说明下一步该做什么 */}
          {nodes.length === 0 && !ghost && (
            <div className="canvas-empty">
              <div className="ce-title">画布还是空的</div>
              <div className="ce-desc">
                从左侧「基础」组把<b>开始</b>拖进来定契约，<br />再拖入决策节点、从节点底部端口拉线连起来
              </div>
            </div>
          )}

          {/* 画布工具条 */}
          <div className="hud">
            <button className="btn sm" onClick={doAutoLayout}>自动布局</button>
            <button className="btn sm" onClick={fitView}>适应画布</button>
            <span className="hud-sep" />
            <button className="btn sm hud-sq" onClick={() => zoomBy(1 / 1.2)} title="缩小">−</button>
            <span className="hud-zoom mono">{Math.round(view.zoom * 100)}%</span>
            <button className="btn sm hud-sq" onClick={() => zoomBy(1.2)} title="放大">+</button>
            <span className="hud-sep" />
            <button className="btn sm" onClick={() => setView({ zoom: 1, pan: { x: 40, y: 20 } })}>重置视图</button>
          </div>

          {/* 缩略图：4000×4000 的画布里，用它回答「我现在在哪」 */}
          <div className="minimap" title="缩略图 · 点击或拖动可快速定位">
            <div className="mm-box" ref={mmBoxRef} onMouseDown={mmDown} style={{ width: MM_W, height: MM_H }}>
              <svg width={MM_W} height={MM_H}>
                {edges.map((e) => {
                  const a = nodes.find((n) => n.nodeKey === e.fromNode);
                  const b = nodes.find((n) => n.nodeKey === e.toNode);
                  if (!a || !b) return null;
                  return (
                    <line
                      key={`mm-${e.fromNode}-${e.fromPort || ''}-${e.toNode}`}
                      x1={mmOx + (a.x + NODE_W / 2 - gb.x0) * mmS}
                      y1={mmOy + (a.y + NODE_H - gb.y0) * mmS}
                      x2={mmOx + (b.x + NODE_W / 2 - gb.x0) * mmS}
                      y2={mmOy + (b.y - gb.y0) * mmS}
                      stroke="#C9B79F" strokeWidth="1"
                    />
                  );
                })}
                {nodes.map((n) => (
                  <rect
                    key={`mm-${n.nodeKey}`}
                    x={mmOx + (n.x - gb.x0) * mmS}
                    y={mmOy + (n.y - gb.y0) * mmS}
                    width={Math.max(3, NODE_W * mmS)}
                    height={Math.max(2, NODE_H * mmS)}
                    rx="1.2"
                    fill={TYPE_COLOR[n.nodeType]}
                    opacity=".8"
                  />
                ))}
                <rect
                  className="mm-vp"
                  x={mmVp.x} y={mmVp.y}
                  width={Math.max(6, mmVp.w)} height={Math.max(6, mmVp.h)}
                  rx="2"
                />
              </svg>
            </div>
          </div>

          {/* 拖拽中的浮标 */}
          {ghost && ghost.kind === 'palette' && (
            <div className="drag-chip" style={{
              position: 'fixed', left: ghost.cx + 14, top: ghost.cy + 8, borderColor: TYPE_COLOR[ghost.type]
            }}>
              <span className="dc-glyph" style={{ color: TYPE_COLOR[ghost.type] }}><NodeGlyph type={ghost.type} /></span>
              {TYPE_LABEL[ghost.type]}
            </div>
          )}
        </div>

        {/* 节点属性 */}
        <div
          className="prop-panel"
          style={{ maxHeight: 'calc(100vh - 300px)', overflowY: 'auto' }}
          onFocusCapture={recordField}
          onMouseDownCapture={recordField}
          onKeyDownCapture={recordField}
        >
          {/* 只读模式整块禁用：fieldset disabled 覆盖内部所有 input/select/textarea/button，
              比逐个控件加 disabled 可靠（删除按钮等也一并锁住） */}
          <fieldset disabled={readOnly} style={{ border: 0, padding: 0, margin: 0, minWidth: 0 }}>
          {selNodeObj ? (
            <NodeProp
              node={selNodeObj}
              nodes={nodes}
              features={features}
              lastFieldRef={lastFieldRef}
              patchNode={patchNode}
              setEdges={setEdges}
              onDelete={() => delNodeByKey(selNodeObj.nodeKey)}
            />
          ) : selEdgeObj ? (
            <EdgeProp
              edge={selEdgeObj}
              nodes={nodes}
              patchNode={patchNode}
              setEdges={setEdges}
              onDelete={() => delEdge(selEdgeObj)}
            />
          ) : (
            <div className="empty" style={{ padding: '48px 12px' }}>
              <div className="empty-title" style={{ fontSize: 14 }}>选择画布中的节点</div>
              <div className="empty-desc">
                {readOnly
                  ? <>点选画布中的节点或连线<br />在此查看它的规则配置（只读）</>
                  : <>左侧拖拽节点入画布，从端口拖出连线<br />选中节点或连线后在此配置</>}
              </div>
            </div>
          )}
          </fieldset>
        </div>
      </div>
      </div>

      <div className="canvas-foot">
        <span className="cf-stat">
          {nodes.length} 节点 · {edges.length} 连线
          {issueCount > 0 && <> · <b className="cf-warn">{issueCount} 个节点待完善</b></>}
        </span>
        <span className="cf-tip">
          {readOnly
            ? '只读查看 · 可缩放 / 平移 / 选中查看 · Esc 取消选中'
            : 'Delete 删除选中 · Esc 取消选中 · 分支端口 c0 / c1 / … 之后固定接 else'}
        </span>
      </div>
    </div>
  );
}

/* ---------------- 属性面板 ---------------- */

function NodeProp({ node, nodes, features, lastFieldRef, patchNode, setEdges, onDelete }) {
  const setConfig = (cfg) => patchNode(node.nodeKey, { config: cfg });
  const isRuleNode = ['BRANCH', 'CASE', 'CROSS', 'NORMAL'].includes(node.nodeType);
  return (
    <>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
        <b style={{ fontSize: 15 }}>{TYPE_LABEL[node.nodeType]}</b>
        <span className="mono" style={{ fontSize: 12, color: 'var(--ink-3)' }}>{node.nodeKey}</span>
      </div>
      <div className="field" style={{ marginBottom: 12 }}>
        <label>节点标题</label>
        <input className="input" value={node.title} onChange={(e) => patchNode(node.nodeKey, { title: e.target.value })} />
      </div>

      {node.nodeType === 'START' && <StartEditor node={node} setConfig={setConfig} />}
      {node.nodeType === 'END' && <EndEditor nodes={nodes} />}
      {isRuleNode && <VarChips nodes={nodes} features={features} lastFieldRef={lastFieldRef} />}
      {node.nodeType === 'BRANCH' && <BranchEditor node={node} setConfig={setConfig} setEdges={setEdges} />}
      {node.nodeType === 'CASE' && <CaseEditor node={node} setConfig={setConfig} />}
      {node.nodeType === 'CROSS' && <CrossEditor node={node} setConfig={setConfig} />}
      {node.nodeType === 'NORMAL' && <NormalEditor node={node} setConfig={setConfig} />}
      {isRuleNode && <RefHints node={node} nodes={nodes} features={features} />}

      <button className="btn danger sm" style={{ marginTop: 16 }} onClick={onDelete}>删除此节点</button>
    </>
  );
}

/* 开始节点 = 策略契约：入参 / 出参定义表 */
function StartEditor({ node, setConfig }) {
  const cfg = node.config;
  const inputs = cfg.inputs || [];
  const outputs = cfg.outputs || [];
  const setInput = (i, patch) => setConfig({ ...cfg, inputs: inputs.map((v, vi) => (vi === i ? { ...v, ...patch } : v)) });
  const addInput = () => setConfig({ ...cfg, inputs: [...inputs, { name: '', type: 'STRING', required: false, defaultValue: '', desc: '' }] });
  const delInput = (i) => setConfig({ ...cfg, inputs: inputs.filter((_, vi) => vi !== i) });
  const setOutput = (i, patch) => setConfig({ ...cfg, outputs: outputs.map((v, vi) => (vi === i ? { ...v, ...patch } : v)) });
  const addOutput = () => setConfig({ ...cfg, outputs: [...outputs, { name: '', type: 'STRING', desc: '' }] });
  const delOutput = (i) => setConfig({ ...cfg, outputs: outputs.filter((_, vi) => vi !== i) });
  const dupIn = dupNames(inputs);
  const dupOut = dupNames(outputs);
  return (
    <>
      <div className="field" style={{ marginBottom: 8 }}><label>策略入参（规则中引用 <span className="mono">c.名称</span>）</label>
        <div style={{ overflowX: 'auto', border: '1px solid #E8DFD2', borderRadius: 8 }}>
          <table className="xls">
            <colgroup>
              <col style={{ width: 78 }} /><col style={{ width: 76 }} /><col style={{ width: 30 }} /><col style={{ width: 70 }} /><col /><col style={{ width: 22 }} />
            </colgroup>
            <thead>
              <tr><th>名称</th><th>类型</th><th>必填</th><th>默认值</th><th>说明</th><th></th></tr>
            </thead>
            <tbody>
              {inputs.map((v, i) => (
                <tr key={i}>
                  <td><input className="mono" placeholder="applyAmount" value={v.name} onChange={(e) => setInput(i, { name: e.target.value })} /></td>
                  <td>
                    <select value={v.type} onChange={(e) => setInput(i, { type: e.target.value })}>
                      {PARAM_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                    </select>
                  </td>
                  <td style={{ textAlign: 'center' }}>
                    <input type="checkbox" checked={v.required} onChange={(e) => setInput(i, { required: e.target.checked })} style={{ width: 'auto' }} />
                  </td>
                  <td><input placeholder="—" value={v.defaultValue} onChange={(e) => setInput(i, { defaultValue: e.target.value })} /></td>
                  <td><input placeholder="说明" value={v.desc} onChange={(e) => setInput(i, { desc: e.target.value })} /></td>
                  <td className="gut"><button className="xbtn" title="删除入参" onClick={() => delInput(i)}>×</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {inputs.length === 0 && <div className="hint" style={{ marginTop: 6 }}>暂无入参，点击下方按钮添加。</div>}
        {dupIn.length > 0 && <div className="ref-warn">入参名称重复：{dupIn.join('、')}，请改为唯一名称。</div>}
      </div>
      <button className="btn sm" onClick={addInput}>+ 添加入参</button>

      <div className="field" style={{ marginTop: 14, marginBottom: 8 }}><label>策略出参（规则中赋值 <span className="mono">o.名称 = 值</span>）</label>
        <div style={{ overflowX: 'auto', border: '1px solid #E8DFD2', borderRadius: 8 }}>
          <table className="xls">
            <colgroup>
              <col style={{ width: 96 }} /><col style={{ width: 76 }} /><col /><col style={{ width: 22 }} />
            </colgroup>
            <thead>
              <tr><th>名称</th><th>类型</th><th>说明</th><th></th></tr>
            </thead>
            <tbody>
              {outputs.map((v, i) => (
                <tr key={i}>
                  <td><input className="mono" placeholder="finalDecision" value={v.name} onChange={(e) => setOutput(i, { name: e.target.value })} /></td>
                  <td>
                    <select value={v.type} onChange={(e) => setOutput(i, { type: e.target.value })}>
                      {PARAM_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                    </select>
                  </td>
                  <td><input placeholder="说明" value={v.desc} onChange={(e) => setOutput(i, { desc: e.target.value })} /></td>
                  <td className="gut"><button className="xbtn" title="删除出参" onClick={() => delOutput(i)}>×</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {outputs.length === 0 && <div className="hint" style={{ marginTop: 6 }}>暂无出参，点击下方按钮添加；未定义的 <span className="mono">o.</span> 引用会被标记。</div>}
        {dupOut.length > 0 && <div className="ref-warn">出参名称重复：{dupOut.join('、')}，请改为唯一名称。</div>}
      </div>
      <button className="btn sm" onClick={addOutput}>+ 添加出参</button>
      <div className="hint" style={{ marginTop: 10 }}>
        流程入口，画布中只允许一个。入参为策略级输入（<span className="mono">c.名称</span>），出参为最终 JSON map 输出的键（<span className="mono">o.名称 = 值</span> 在各规则的输出赋值中写入）。
      </div>
    </>
  );
}

/* 结束节点 = 输出契约预览：来自开始节点出参定义 + 全局赋值处数 */
function EndEditor({ nodes }) {
  const start = startNodeOf(nodes);
  const outputs = start ? (start.config.outputs || []) : [];
  const counts = {};
  nodes.forEach((n) => {
    nodeTexts(n).forEach((t) => {
      refsIn(t).forEach((r) => {
        if (r.startsWith('o.')) counts[r.slice(2)] = (counts[r.slice(2)] || 0) + 1;
      });
    });
  });
  if (outputs.length === 0) {
    return (
      <div className="hint">
        流程出口，画布中只允许一个。当前未定义出参：请先在「开始」节点声明策略出参，本节点预览最终输出的 JSON map 契约。
      </div>
    );
  }
  const preview = JSON.stringify(Object.fromEntries(outputs.map((o) => [o.name || '<未命名>', null])), null, 2);
  return (
    <>
      <div className="field" style={{ marginBottom: 8 }}><label>输出契约（来自开始节点出参定义）</label>
        <div style={{ overflowX: 'auto', border: '1px solid #E8DFD2', borderRadius: 8 }}>
          <table className="xls">
            <colgroup>
              <col style={{ width: 110 }} /><col style={{ width: 82 }} /><col /><col style={{ width: 70 }} />
            </colgroup>
            <thead>
              <tr><th>名称</th><th>类型</th><th>说明</th><th>赋值</th></tr>
            </thead>
            <tbody>
              {outputs.map((o, i) => (
                <tr key={i}>
                  <td style={{ padding: '5px 7px', fontFamily: 'var(--font-mono)' }}>o.{o.name || '—'}</td>
                  <td style={{ padding: '5px 7px', color: 'var(--ink-2)' }}>{o.type || 'STRING'}</td>
                  <td style={{ padding: '5px 7px', color: 'var(--ink-2)' }}>{o.desc || '—'}</td>
                  <td style={{ padding: '5px 7px', color: counts[o.name] ? 'var(--ink-1)' : '#B45309' }}>
                    {counts[o.name] ? `${counts[o.name]} 处` : '0 处'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <div className="field" style={{ marginBottom: 8 }}><label>JSON map 预览（未赋值的出参为 null）</label>
        <pre className="out-json">{preview}</pre>
      </div>
      <div className="hint">
        运行期在各决策 / 普通决策节点命中规则后执行 <span className="mono">o.</span> 赋值，最终按本契约组装为 map 类型的 JSON 字符串输出。赋值 0 处的出参大概率是漏配。
      </div>
    </>
  );
}

/* 变量 chips：点击插入最近聚焦的输入框 */
function VarChips({ nodes, features, lastFieldRef }) {
  const start = startNodeOf(nodes);
  const inputs = start ? (start.config.inputs || []) : [];
  const outputs = start ? (start.config.outputs || []) : [];
  const chip = (token, title) => (
    <button
      key={token}
      className="vchip"
      title={title}
      onMouseDown={(e) => e.preventDefault()}
      onClick={() => insertInto(lastFieldRef.current, token)}
    >{token}</button>
  );
  const hasAny = inputs.length || outputs.length || (features && features.length);
  if (!hasAny) return null;
  return (
    <div className="vchips">
      {inputs.length > 0 && <span className="grp">入参</span>}
      {inputs.map((v) => chip(`c.${v.name || '未命名'}`, `入参 · ${v.type || 'STRING'}${v.desc ? ' · ' + v.desc : ''}`))}
      {outputs.length > 0 && <span className="grp">出参</span>}
      {outputs.map((v) => chip(`o.${v.name || '未命名'}`, `出参 · ${v.type || 'STRING'}${v.desc ? ' · ' + v.desc : ''}`))}
      {features && features.length > 0 && <span className="grp">特征</span>}
      {features && features.map((f) => chip(`f.${f.code}`, `已上线特征 · ${f.name || f.code}`))}
    </div>
  );
}

/* 未定义引用提示：c. / o. 对照开始节点契约，f. 对照已上线特征 */
function RefHints({ node, nodes, features }) {
  const start = startNodeOf(nodes);
  const inputNames = new Set(start ? (start.config.inputs || []).map((v) => v.name) : []);
  const outputNames = new Set(start ? (start.config.outputs || []).map((v) => v.name) : []);
  const refs = new Set();
  nodeTexts(node).forEach((t) => refsIn(t).forEach((r) => refs.add(r)));
  const badC = [...refs].filter((r) => r.startsWith('c.') && !inputNames.has(r.slice(2)));
  const badO = [...refs].filter((r) => r.startsWith('o.') && !outputNames.has(r.slice(2)));
  const badF = features ? [...refs].filter((r) => r.startsWith('f.') && !features.some((f) => f.code === r.slice(2))) : [];
  if (!badC.length && !badO.length && !badF.length) return null;
  return (
    <div className="ref-warn">
      {badC.length > 0 && <div>未定义的入参引用：{badC.join('、')}（未在「开始」节点入参定义中声明）</div>}
      {badO.length > 0 && <div>未定义的出参引用：{badO.join('、')}（未在「开始」节点出参定义中声明）</div>}
      {badF.length > 0 && <div>未注册的特征编码：{badF.join('、')}（当前已上线特征中不存在）</div>}
    </div>
  );
}

function dupNames(list) {
  const seen = new Set();
  const dup = new Set();
  list.forEach((v) => {
    const n = (v.name || '').trim();
    if (!n) return;
    if (seen.has(n)) dup.add(n);
    seen.add(n);
  });
  return [...dup];
}

/* 分支：条件行 + 固定 else（唯一带 else 的节点类型） */
function BranchEditor({ node, setConfig, setEdges }) {
  const cfg = node.config;
  const setCond = (i, patch) => setConfig({ ...cfg, conditions: cfg.conditions.map((c, ci) => (ci === i ? { ...c, ...patch } : c)) });
  const addCond = () => setConfig({ ...cfg, conditions: [...cfg.conditions, { expr: '', label: `条件 ${cfg.conditions.length + 1}` }] });
  const delCond = (i) => {
    setConfig({ ...cfg, conditions: cfg.conditions.filter((_, ci) => ci !== i) });
    // 同步连线：删掉 c_i 的出线，c_{j>i} 端口序号前移
    setEdges((es) => es
      .filter((e) => !(e.fromNode === node.nodeKey && e.fromPort === `c${i}`))
      .map((e) => {
        if (e.fromNode === node.nodeKey && /^c\d+$/.test(e.fromPort || '') && Number(e.fromPort.slice(1)) > i) {
          return { ...e, fromPort: `c${Number(e.fromPort.slice(1)) - 1}` };
        }
        return e;
      }));
  };
  return (
    <>
      <div className="field" style={{ marginBottom: 8 }}><label>分支条件（if / elseif）</label>
        {cfg.conditions.map((c, i) => (
          <div key={i} style={{ display: 'grid', gridTemplateColumns: '30px 1fr 22px', gap: 6, marginBottom: 8, alignItems: 'start' }}>
            <span className="mono" style={{ fontSize: 11.5, color: 'var(--decision)', paddingTop: 8 }}>c{i}</span>
            <div>
              <input className="input" style={{ padding: '6px 9px', fontSize: 12.5, fontFamily: 'var(--font-mono)', marginBottom: 4 }}
                placeholder="条件表达式，如 f.overdue_cnt >= 3 或 c.applyAmount > 10000" value={c.expr} onChange={(e) => setCond(i, { expr: e.target.value })} />
              <input className="input" style={{ padding: '6px 9px', fontSize: 12.5 }}
                placeholder="分支名（显示在连线上，可在连线面板改）" value={c.label} onChange={(e) => setCond(i, { label: e.target.value })} />
            </div>
            <button className="xbtn" style={{ marginTop: 6 }} title="删除条件" onClick={() => delCond(i)}>×</button>
          </div>
        ))}
        <div style={{ display: 'grid', gridTemplateColumns: '30px 1fr', gap: 6, alignItems: 'center' }}>
          <span className="mono" style={{ fontSize: 11.5, color: 'var(--ink-3)' }}>else</span>
          <input className="input" style={{ padding: '6px 9px', fontSize: 12.5 }} value={cfg.elseLabel}
            onChange={(e) => setConfig({ ...cfg, elseLabel: e.target.value })} />
        </div>
      </div>
      <button className="btn sm" onClick={addCond}>+ 添加条件</button>
      <div className="hint" style={{ marginTop: 10 }}>
        自上而下判断，全部不命中走固定 else 出口；每个条件对应画布底部一个端口（c0、c1…），端口拖着接下游节点。
        分支仅做路由（条件里可用 <span className="mono">c.入参</span> / <span className="mono">f.特征</span>），输出赋值写在决策 / 普通决策节点。
      </div>
    </>
  );
}

/* 决策（case-when）：Excel 行表，按第一列条件逐行匹配 */
function CaseEditor({ node, setConfig }) {
  const rows = node.config.rows;
  const setRow = (i, patch) => setConfig({ rows: rows.map((r, ri) => (ri === i ? { ...r, ...patch } : r)) });
  const addRow = () => setConfig({ rows: [...rows, { cond: '', outputs: '', note: '' }] });
  const delRow = (i) => setConfig({ rows: rows.filter((_, ri) => ri !== i) });
  return (
    <>
      <div className="field" style={{ marginBottom: 8 }}><label>规则行（Excel 式配置）</label>
        <div style={{ overflowX: 'auto', border: '1px solid #E8DFD2', borderRadius: 8 }}>
          <table className="xls">
            <colgroup>
              <col style={{ width: 26 }} /><col style={{ width: 130 }} /><col /><col style={{ width: 62 }} /><col style={{ width: 24 }} />
            </colgroup>
            <thead>
              <tr><th></th><th>条件</th><th>输出赋值</th><th>备注</th><th></th></tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={i}>
                  <td className="gut">{i + 1}</td>
                  <td><input placeholder="如 f.overdue_cnt >= 3" value={r.cond} onChange={(e) => setRow(i, { cond: e.target.value })} /></td>
                  <td>
                    <textarea rows={2} placeholder={"o.finalDecision = 'REJECT'\n每行一条赋值"} value={r.outputs}
                      onChange={(e) => setRow(i, { outputs: e.target.value })} />
                  </td>
                  <td><input placeholder="备注" value={r.note} onChange={(e) => setRow(i, { note: e.target.value })} /></td>
                  <td className="gut"><button className="xbtn" title="删除行" onClick={() => delRow(i)}>×</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <button className="btn sm" onClick={addRow}>+ 添加规则行</button>
      <div className="hint" style={{ marginTop: 10 }}>
        Excel 语义：从上到下逐行匹配「条件」列（可用 <span className="mono">c.入参</span> / <span className="mono">f.特征</span>），命中即执行该行「输出赋值」并停止（首行优先）。
      </div>
    </>
  );
}

/* 交叉决策：首行 × 首列同时命中才执行单元格规则 */
function CrossEditor({ node, setConfig }) {
  const cfg = node.config;
  const setCell = (ri, ci, v) => setConfig({ ...cfg, cells: cfg.cells.map((r, i) => (i === ri ? r.map((c, j) => (j === ci ? v : c)) : r)) });
  const setRowVal = (ri, v) => setConfig({ ...cfg, rowValues: cfg.rowValues.map((x, i) => (i === ri ? v : x)) });
  const setColVal = (ci, v) => setConfig({ ...cfg, colValues: cfg.colValues.map((x, i) => (i === ci ? v : x)) });
  const addRow = () => setConfig({ ...cfg, rowValues: [...cfg.rowValues, `行值${cfg.rowValues.length + 1}`], cells: [...cfg.cells, cfg.colValues.map(() => '')] });
  const delRow = (ri) => setConfig({ ...cfg, rowValues: cfg.rowValues.filter((_, i) => i !== ri), cells: cfg.cells.filter((_, i) => i !== ri) });
  const addCol = () => setConfig({ ...cfg, colValues: [...cfg.colValues, `列值${cfg.colValues.length + 1}`], cells: cfg.cells.map((r) => [...r, '']) });
  const delCol = (ci) => setConfig({ ...cfg, colValues: cfg.colValues.filter((_, i) => i !== ci), cells: cfg.cells.map((r) => r.filter((_, i) => i !== ci)) });
  const canDelRow = cfg.rowValues.length > 1;
  const canDelCol = cfg.colValues.length > 1;
  return (
    <>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, marginBottom: 8 }}>
        <div><label style={{ fontSize: 12, color: 'var(--ink-3)', display: 'block', marginBottom: 5 }}>行变量（首列）</label>
          <input className="input" style={{ padding: '6px 9px', fontSize: 12.5, fontFamily: 'var(--font-mono)' }}
            placeholder="f.overdue_level" value={cfg.rowVar} onChange={(e) => setConfig({ ...cfg, rowVar: e.target.value })} /></div>
        <div><label style={{ fontSize: 12, color: 'var(--ink-3)', display: 'block', marginBottom: 5 }}>列变量（首行）</label>
          <input className="input" style={{ padding: '6px 9px', fontSize: 12.5, fontFamily: 'var(--font-mono)' }}
            placeholder="c.applyAmount" value={cfg.colVar} onChange={(e) => setConfig({ ...cfg, colVar: e.target.value })} /></div>
      </div>

      <div className="field" style={{ marginBottom: 8 }}><label>交叉矩阵（单元格填输出赋值）</label>
        <div style={{ overflowX: 'auto', border: '1px solid #E8DFD2', borderRadius: 8 }}>
          <table className="xls">
            <tbody>
              <tr>
                <td className="gut" style={{ width: 92, background: '#F7F1E7', fontSize: 10.5, lineHeight: 1.35, padding: '4px 3px', fontFamily: 'var(--font-mono)' }}>
                  {(cfg.rowVar || '行')}<br />\ {(cfg.colVar || '列')}
                </td>
                {cfg.colValues.map((cv, ci) => (
                  <th key={ci} style={{ padding: '2px 2px 2px 4px', minWidth: 68 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 2 }}>
                      <input className="th-input" style={{ minWidth: 0 }} value={cv} onChange={(e) => setColVal(ci, e.target.value)} />
                      <button className="xbtn" disabled={!canDelCol} style={{ opacity: canDelCol ? 1 : .3 }}
                        title="删除列" onClick={() => canDelCol && delCol(ci)}>×</button>
                    </div>
                  </th>
                ))}
              </tr>
              {cfg.rowValues.map((rv, ri) => (
                <tr key={ri}>
                  <td style={{ background: '#F7F1E7', padding: '2px 2px 2px 6px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 2 }}>
                      <input className="th-input mono" style={{ minWidth: 0, fontFamily: 'var(--font-mono)' }} value={rv} onChange={(e) => setRowVal(ri, e.target.value)} />
                      <button className="xbtn" disabled={!canDelRow} style={{ opacity: canDelRow ? 1 : .3 }}
                        title="删除行" onClick={() => canDelRow && delRow(ri)}>×</button>
                    </div>
                  </td>
                  {cfg.colValues.map((_, ci) => (
                    <td key={ci} style={{ background: cfg.cells[ri][ci] ? 'rgba(232,116,15,.07)' : 'transparent', padding: 0 }}>
                      <textarea rows={2} style={{ fontSize: 11, minHeight: 34, padding: '4px 5px' }}
                        placeholder="o.名称 = 值" value={cfg.cells[ri][ci]}
                        onChange={(e) => setCell(ri, ci, e.target.value)} />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <div style={{ display: 'flex', gap: 8 }}>
        <button className="btn sm" onClick={addRow}>+ 添加行</button>
        <button className="btn sm" onClick={addCol}>+ 添加列</button>
      </div>
      <div className="hint" style={{ marginTop: 10 }}>
        交叉决策语义：同时命中首列（{cfg.rowVar || '行变量'}）与首行（{cfg.colVar || '列变量'}）后，才执行交叉单元格的输出赋值（可多行，每行一条 <span className="mono">o.名称 = 值</span>）。
      </div>
    </>
  );
}

/* 普通决策：直接填写规则列表 */
function NormalEditor({ node, setConfig }) {
  const rules = node.config.rules;
  const setRule = (i, patch) => setConfig({ rules: rules.map((r, ri) => (ri === i ? { ...r, ...patch } : r)) });
  const addRule = () => setConfig({ rules: [...rules, { cond: '', outputs: '' }] });
  const delRule = (i) => setConfig({ rules: rules.filter((_, ri) => ri !== i) });
  return (
    <>
      <div className="field" style={{ marginBottom: 8 }}><label>规则列表</label>
        {rules.map((r, i) => (
          <div key={i} className="rule-card">
            <div className="rc-head">
              <span className="mono" style={{ fontSize: 11.5, color: 'var(--decision)' }}>R{i + 1}</span>
              <button className="xbtn" title="删除规则" onClick={() => delRule(i)}>×</button>
            </div>
            <input className="input" style={{ padding: '6px 9px', fontSize: 12.5, fontFamily: 'var(--font-mono)', marginBottom: 6 }}
              placeholder="条件表达式，留空表示兜底；如 c.age < 18 && f.risk_score > 600" value={r.cond} onChange={(e) => setRule(i, { cond: e.target.value })} />
            <textarea className="input" rows={2}
              style={{ padding: '6px 9px', fontSize: 12.5, fontFamily: 'var(--font-mono)', lineHeight: 1.5, resize: 'vertical' }}
              placeholder={"输出赋值，每行一条\n如 o.finalDecision = 'PASS'"} value={r.outputs} onChange={(e) => setRule(i, { outputs: e.target.value })} />
          </div>
        ))}
      </div>
      <button className="btn sm" onClick={addRule}>+ 添加规则</button>
      <div className="hint" style={{ marginTop: 10 }}>
        普通决策：自上而下匹配，第一条命中的规则生效；条件留空作为兜底。命中后执行其「输出赋值」。
      </div>
    </>
  );
}

/* 连线属性：分支出线标签写回分支条件（单源同步），其余连线存自定义标签 */
function EdgeProp({ edge, nodes, patchNode, setEdges, onDelete }) {
  const from = nodes.find((n) => n.nodeKey === edge.fromNode);
  const to = nodes.find((n) => n.nodeKey === edge.toNode);
  const idx = /^c\d+$/.test(edge.fromPort || '') ? Number(edge.fromPort.slice(1)) : -1;
  const cond = from && from.nodeType === 'BRANCH' && idx >= 0 ? from.config.conditions[idx] : null;
  const isBranchPort = !!(from && from.nodeType === 'BRANCH' && (edge.fromPort === 'else' || cond));
  const label = isBranchPort ? (edge.fromPort === 'else' ? (from.config.elseLabel || '') : (cond ? cond.label || '' : '')) : (edge.label || '');
  const setLabel = (v) => {
    if (isBranchPort) {
      const cfg = from.config;
      if (idx >= 0) {
        patchNode(from.nodeKey, { config: { ...cfg, conditions: cfg.conditions.map((c, ci) => (ci === idx ? { ...c, label: v } : c)) } });
      } else {
        patchNode(from.nodeKey, { config: { ...cfg, elseLabel: v } });
      }
    } else {
      setEdges((es) => es.map((e) => (
        e.fromNode === edge.fromNode && (e.fromPort || null) === (edge.fromPort || null) && e.toNode === edge.toNode
          ? { ...e, label: v || null } : e
      )));
    }
  };
  return (
    <>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <b style={{ fontSize: 15 }}>连线</b>
        <span className="mono" style={{ fontSize: 12, color: 'var(--ink-3)' }}>{edge.fromPort || 'out'}</span>
      </div>
      <div className="prop-row"><div className="k">起点</div><div className="v">{from ? `${from.title} (${edge.fromNode})` : edge.fromNode}</div></div>
      <div className="prop-row"><div className="k">终点</div><div className="v">{to ? `${to.title} (${edge.toNode})` : edge.toNode}</div></div>
      <div className="field" style={{ marginBottom: 10 }}>
        <label>分支标签{isBranchPort ? '（同步分支条件的分支名）' : '（存于本连线）'}</label>
        <input className="input" placeholder={isBranchPort ? '留空时连线上回退显示条件表达式' : '如 自动通过 / 转人工'}
          value={label} onChange={(e) => setLabel(e.target.value)} />
      </div>
      <div className="hint" style={{ marginBottom: 14 }}>
        {isBranchPort
          ? '本标签与源分支节点该端口条件的「分支名」同一份数据，两处修改双向同步。'
          : '非分支出线支持自定义标签，保存后显示在连线上；同一端口重复连线会替换原连线。'}
      </div>
      <button className="btn danger sm" onClick={onDelete}>删除此连线</button>
    </>
  );
}
