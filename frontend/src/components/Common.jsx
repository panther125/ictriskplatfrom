import React, { useEffect, useState } from 'react';

export function MetricCard({ label, value, note, mono = true }) {
  return (
    <div className="metric-card">
      <div className="label">{label}</div>
      <div className="value" style={mono ? {} : { fontFamily: 'var(--font-sans)' }}>{value}</div>
      <div className="note">{note}</div>
    </div>
  );
}

const BADGE_MAP = {
  'PASS': 'pass', '通过': 'pass',
  'REVIEW': 'review', '人工审核': 'review', '人工': 'review', '人工·复核': 'review',
  'REJECT': 'reject', '拒绝': 'reject', '拒绝·高风险': 'reject', '拒绝·中风险': 'reject',
  'CHALLENGE': 'challenge', '挑战验证': 'challenge',
  '兜底': 'fallback', '降级': 'fallback',
  'UNCONFIGURED': 'unconfig', '未配置→默认': 'unconfig'
};

export function ConclusionBadge({ text }) {
  const key = String(text ?? '');
  const cls = BADGE_MAP[key] || (key.toLowerCase().includes('reject') ? 'reject'
    : key.toLowerCase().includes('review') ? 'review'
    : key.toLowerCase().includes('pass') ? 'pass' : 'unconfig');
  return <span className={`badge badge-${cls}`}>{key || '—'}</span>;
}

const TAG_MAP = {
  '正常': 'normal', 'NORMAL': 'normal', '稳定': 'stable', 'STABLE': 'stable',
  '已发布': 'published', 'PUBLISHED': 'published', '已上线': 'published',
  '熔断': 'break', '熔断中': 'break',
  '突增': 'surge', 'SURGE': 'surge',
  '实验': 'experiment', 'EXPERIMENT': 'experiment',
  '草稿': 'draft', 'DRAFT': 'draft',
  '灰度': 'gray', 'GRAYSCALE': 'gray', '灰度中': 'gray', '灰度10%': 'gray',
  '已回滚': 'rollback', 'ROLLED_BACK': 'rollback', '待审批': 'warn', 'PENDING_REVIEW': 'warn',
  '降级运行': 'fallback', 'DRIFT': 'warn'
};

export function StatusTag({ text }) {
  const key = String(text ?? '');
  const cls = TAG_MAP[key] || TAG_MAP[key.toUpperCase()] || 'default';
  return <span className={`tag tag-${cls}`}>{key}</span>;
}

export function Seg({ options, value, onChange }) {
  return (
    <div className="seg">
      {options.map((o) => (
        <button
          key={o}
          type="button"
          className={o === value ? 'active' : ''}
          aria-pressed={o === value}
          onClick={() => onChange(o)}
        >{o}</button>
      ))}
    </div>
  );
}

export function SectionCard({ title, sub, children, className = '' }) {
  return (
    <div className={`card ${className}`}>
      <div className="card-title">
        <span>{title}</span>
        {sub && <span className="sub">{sub}</span>}
      </div>
      {children}
    </div>
  );
}

/* 统一页头：标题 + 说明 + 右侧操作区 */
export function PageHeader({ title, sub, actions }) {
  return (
    <div className="page-header">
      <div>
        <h1>{title}</h1>
        {sub && <div className="sub">{sub}</div>}
      </div>
      {actions && <div className="actions">{actions}</div>}
    </div>
  );
}

/* 空态：说清现状，并给出下一步动作 */
export function EmptyState({ title = '这里还没有内容', desc, action }) {
  return (
    <div className="empty">
      <div className="empty-title">{title}</div>
      {desc && <div className="empty-desc">{desc}</div>}
      {action && <div className="empty-act">{action}</div>}
    </div>
  );
}

export function formatNum(n) {
  return Number(n || 0).toLocaleString('en-US');
}

export function Modal({ open, title, sub, onClose, children, footer, width = 560, closeOnMask = true }) {
  // Esc 关闭 + 打开时锁定背景滚动
  useEffect(() => {
    if (!open) return;
    const onKey = (e) => { if (e.key === 'Escape') onClose?.(); };
    document.addEventListener('keydown', onKey);
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = prev;
    };
  }, [open, onClose]);

  if (!open) return null;
  return (
    <div
      className="modal-mask"
      onMouseDown={(e) => { if (closeOnMask && e.target === e.currentTarget) onClose?.(); }}
    >
      <div className="modal" style={{ width, maxWidth: '100%' }} role="dialog" aria-modal="true" aria-label={title}>
        <div className="modal-head">
          <div>
            <div className="t">{title}</div>
            {sub && <div className="s">{sub}</div>}
          </div>
          <button className="modal-close" onClick={onClose} aria-label="关闭">×</button>
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-foot">{footer}</div>}
      </div>
    </div>
  );
}

export function FormField({ label, children, required, error, hint }) {
  return (
    <div className="field">
      <label>{label}{required && <span className="req">*</span>}</label>
      {children}
      {error ? (
        <div className="field-err"><span>!</span><span>{error}</span></div>
      ) : hint ? (
        <div className="hint" style={{ marginTop: 6 }}>{hint}</div>
      ) : null}
    </div>
  );
}

/**
 * 折叠表达式回显里的超长引用值。
 *
 * 衍生特征验证时，后端会把表达式里的 `@@基础特征` 替换成实测值再回显；若被引用的是 JSON 型出参
 * （如视图整体返回，动辄数千字符），整段值会内联进表达式，把代码本身淹没。
 *
 * 折叠边界取自 `refValues` 的实测值本身（后端替换进去的就是它），因此在值里含空格、
 * 引号等任意字符时都能精确定位，不会切碎成多段。
 *
 * @param text      后端返回的 exprPreview
 * @param values    实测值列表（Object.values(trial.refValues)）
 */
export function foldLongValues(text, values, headKeep = 60, tailKeep = 24) {
  let out = String(text || '');
  (values || []).forEach((v) => {
    const s = String(v ?? '');
    if (s.length <= headKeep + tailKeep + 40) return;
    const i = out.indexOf(s);
    if (i < 0) return;
    out = `${out.slice(0, i + headKeep)} …⟪该引用值共 ${s.length} 字符，已折叠⟫… ${out.slice(i + s.length - tailKeep)}`;
  });
  return out;
}

/**
 * 长值单元格：JSON 型出参（如视图整体返回）动辄数千字符，直接铺满会把整个结果区撑穿。
 * 默认缩略显示（首尾保留），需要时展开完整值并在单元格内滚动。
 */
export function LongValueCell({ value }) {
  const [open, setOpen] = useState(false);
  if (value === null || value === undefined || value === '') return <span style={{ color: '#B6A997' }}>NULL</span>;
  const s = String(value);
  if (s.length <= 124) return <>{s}</>;
  return (
    <>
      <div style={{ maxHeight: 140, overflow: 'auto' }}>{open ? s : foldLongValues(s, [s])}</div>
      <button className="btn sm" style={{ marginTop: 4 }} onClick={() => setOpen(v => !v)}>
        {open ? '收起' : `展开完整值（${s.length} 字符）`}
      </button>
    </>
  );
}
