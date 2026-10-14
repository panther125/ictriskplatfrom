import React, { useEffect, useRef, useState } from 'react';

/* 只读画布：按编排坐标渲染节点与连线，并把该 Trace 实际走过的节点 / 连线高亮。
   与 StrategyEditor 的画布共用 node-box / port-label 样式；这里不提供编辑与拖拽。 */

const NODE_W = 176;
const NODE_H = 64;

const TYPE_COLOR = {
  START: '#7A6A57', BRANCH: '#C2410C', CASE: '#E8740F', CROSS: '#9A6B1F', NORMAL: '#D9A03C', END: '#7A6A57'
};

const ON_COLOR = '#C2410C';

/* 节点类型字形：与 StrategyEditor 画布同款，13px 下靠轮廓区分布局语义 */
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

function branchPorts(node) {
  return ((node.config || {}).conditions || []).map((_, i) => `c${i}`).concat(['else']);
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
  if (edge.fromPort === 'else') return (from.config || {}).elseLabel || '其余';
  const idx = Number(String(edge.fromPort || '').replace('c', ''));
  const c = ((from.config || {}).conditions || [])[idx];
  return c ? c.label || c.expr || `条件 ${idx + 1}` : null;
}

function nodeSub(node) {
  const c = node.config || {};
  switch (node.nodeType) {
    case 'START': return `${(c.inputs || []).length} 入参 · ${(c.outputs || []).length} 出参`;
    case 'END': return '输出契约 · 来自开始节点';
    case 'BRANCH': return `if/else · ${(c.conditions || []).length} 个条件`;
    case 'CASE': return `${(c.rows || []).length} 行规则 · 首行优先`;
    case 'CROSS': return `${(c.rowValues || []).length} × ${(c.colValues || []).length} 矩阵`;
    case 'NORMAL': return `${(c.rules || []).length} 条规则`;
    default: return '';
  }
}

function graphBounds(nodes) {
  let x0 = Infinity; let y0 = Infinity; let x1 = -Infinity; let y1 = -Infinity;
  nodes.forEach((n) => {
    x0 = Math.min(x0, n.x); y0 = Math.min(y0, n.y);
    x1 = Math.max(x1, n.x + NODE_W); y1 = Math.max(y1, n.y + NODE_H);
  });
  const pad = 56;
  return { x0: x0 - pad, y0: y0 - pad, x1: x1 + pad, y1: y1 + pad };
}

/** 执行步骤 → 高亮口径：节点集合 + 相邻节点对（用于匹配连线），特征步单列 */
export function analyzeRoute(steps) {
  const list = steps || [];
  const nodeSteps = list.filter((s) => !String(s.nodeId || '').startsWith('feat_'));
  const featSteps = list.filter((s) => String(s.nodeId || '').startsWith('feat_'));
  const msByNode = {};
  nodeSteps.forEach((s) => { msByNode[s.nodeId] = (msByNode[s.nodeId] || 0) + (Number(s.elapsedMs) || 0); });
  const execIds = new Set(nodeSteps.map((s) => s.nodeId));
  const edgeKeys = new Set();
  for (let i = 0; i + 1 < nodeSteps.length; i += 1) {
    edgeKeys.add(`${nodeSteps[i].nodeId}→${nodeSteps[i + 1].nodeId}`);
  }
  return { nodeSteps, featSteps, msByNode, execIds, edgeKeys };
}

export default function TrackCanvas({ graph, steps }) {
  const { nodes = [], edges = [] } = graph || {};
  const { msByNode, execIds, edgeKeys } = analyzeRoute(steps);

  const wrapRef = useRef(null);
  const [vp, setVp] = useState({ w: 720, h: 380 });

  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return undefined;
    const sync = () => setVp({ w: el.clientWidth, h: el.clientHeight });
    sync();
    if (typeof ResizeObserver === 'undefined') return undefined;
    const ro = new ResizeObserver(sync);
    ro.observe(el);
    return () => ro.disconnect();
  }, [graph]);

  if (!nodes.length) {
    return <div className="empty" style={{ padding: '32px 12px' }}>该策略暂无可展示的画布内容</div>;
  }

  const b = graphBounds(nodes);
  const scale = Math.min(1, vp.w / (b.x1 - b.x0), vp.h / (b.y1 - b.y0));
  const panX = (vp.w - (b.x1 - b.x0) * scale) / 2 - b.x0 * scale;
  const panY = (vp.h - (b.y1 - b.y0) * scale) / 2 - b.y0 * scale;

  return (
    <div
      ref={wrapRef}
      className="canvas-area"
      style={{
        height: 420, minHeight: 0,
        backgroundPosition: `${panX}px ${panY}px, ${panX}px ${panY}px`,
        backgroundSize: `${20 * scale}px ${20 * scale}px, ${100 * scale}px ${100 * scale}px`
      }}
    >
      <div style={{ position: 'absolute', left: 0, top: 0, width: 0, height: 0, transform: `translate(${panX}px, ${panY}px) scale(${scale})`, transformOrigin: '0 0' }}>
        <svg width={4000} height={4000} style={{ position: 'absolute', left: 0, top: 0, overflow: 'visible', pointerEvents: 'none' }}>
          <defs>
            <marker id="tarw" viewBox="0 0 10 10" refX="8.5" refY="5" markerWidth="7" markerHeight="7" orient="auto">
              <path d="M0,0 L10,5 L0,10 z" fill="#C0AF97" />
            </marker>
            <marker id="tarwOn" viewBox="0 0 10 10" refX="8.5" refY="5" markerWidth="7" markerHeight="7" orient="auto">
              <path d="M0,0 L10,5 L0,10 z" fill={ON_COLOR} />
            </marker>
          </defs>
          {edges.map((e) => {
            const from = nodes.find((n) => n.nodeKey === e.fromNode);
            const to = nodes.find((n) => n.nodeKey === e.toNode);
            if (!from || !to) return null;
            const s = outPortPos(from, e.fromPort);
            const t = inPortPos(to);
            const d = `M ${s.x} ${s.y} C ${s.x} ${s.y + 46}, ${t.x} ${t.y - 46}, ${t.x} ${t.y}`;
            const on = edgeKeys.has(`${e.fromNode}→${e.toNode}`);
            const label = edgeLabelOf(e, nodes);
            return (
              <g key={`${e.fromNode}-${e.fromPort || ''}-${e.toNode}`}>
                <path
                  d={d} fill="none"
                  stroke={on ? ON_COLOR : '#C0AF97'}
                  strokeWidth={on ? 2.6 : 1.5}
                  strokeDasharray={!on && e.fromPort === 'else' ? '6 4' : undefined}
                  opacity={on ? 1 : 0.45}
                  markerEnd={`url(#${on ? 'tarwOn' : 'tarw'})`}
                />
                {label && (
                  <text
                    x={(s.x + t.x) / 2} y={(s.y + t.y) / 2 - 5} textAnchor="middle" fontSize="11"
                    fill={on ? ON_COLOR : '#8A7A66'} stroke="#FBF7F2" strokeWidth="4" paintOrder="stroke"
                    fontWeight={on ? 700 : 400} opacity={on ? 1 : 0.75}
                  >
                    {label}
                  </text>
                )}
              </g>
            );
          })}
        </svg>

        {nodes.map((n) => {
          const on = execIds.has(n.nodeKey);
          const ms = msByNode[n.nodeKey];
          return (
            <div
              key={n.nodeKey}
              data-node-key={n.nodeKey}
              className={`node-box ty-${String(n.nodeType).toLowerCase()}`}
              style={{
                left: n.x, top: n.y, height: NODE_H, cursor: 'default',
                '--nb-accent': on ? ON_COLOR : TYPE_COLOR[n.nodeType],
                ...(on
                  ? { borderColor: ON_COLOR, boxShadow: '0 0 0 3px rgba(194,65,12,.16), 0 8px 18px -6px rgba(90,60,25,.28)' }
                  : { opacity: 0.4 })
              }}
            >
              <div className="nb-body">
                <div className="nb-head" style={{ paddingRight: on ? 38 : 0 }}>
                  <span className="nb-glyph"><NodeGlyph type={n.nodeType} /></span>
                  <span className="nb-title">{n.title || n.nodeKey}</span>
                </div>
                <div className="nb-meta">{n.nodeKey} · {nodeSub(n)}</div>
              </div>
              {on && ms != null && (
                <span style={{
                  position: 'absolute', right: 6, top: 5, fontSize: 9.5, lineHeight: '14px',
                  padding: '0 5px', borderRadius: 7, fontFamily: 'var(--font-mono)', fontWeight: 700,
                  color: '#fff', background: ON_COLOR
                }}>{Math.round(ms * 10) / 10}ms</span>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
