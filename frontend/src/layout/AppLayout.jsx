import React, { useEffect, useMemo, useRef, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
  IconDashboard, IconView, IconFeature, IconStrategy, IconTrial,
  IconMonitor, IconLog, IconSearch
} from './NavIcons';

const NAV = [
  { group: '配置态', items: [
    { to: '/dashboard', label: '总览', icon: <IconDashboard/>, alias: '概览 首页 dashboard' },
    { to: '/views', label: '视图管理', icon: <IconView/>, alias: '数据源 取数 view 字段' },
    { to: '/features', label: '特征工作台', icon: <IconFeature/>, alias: '基础特征 衍生特征 feature' },
    { to: '/strategy', label: '策略管理', icon: <IconStrategy/>, alias: '策略编排 决策 规则 矩阵 树 版本 流量 strategy' },
    { to: '/trial', label: '试算中心', icon: <IconTrial/>, alias: '单笔调试 回放 trial 验证' }
  ]},
  { group: '发布与运行', items: [
    { to: '/monitoring', label: '运行监控', icon: <IconMonitor/>, alias: '健康度 告警 熔断 monitoring' },
    { to: '/track', label: '决策日志与回放', icon: <IconLog/>, alias: '链路 追踪 track 日志' }
  ]}
];

const CRUMB = {
  '/dashboard': ['策略中心', '总览'],
  '/views': ['数据接入', '视图管理'],
  '/features': ['特征口径', '特征工作台'],
  '/strategy': ['决策中心', '策略管理'],
  '/trial': ['试算中心', '单笔调试'],
  '/cross-tree': ['决策中心', '交叉决策树'],
  '/monitoring': ['发布与运行', '运行监控'],
  '/track': ['发布与运行', '决策日志与回放']
};

/* 平台 Logo：暖橙渐变底 + 白色「决策树」分支 —— 呼应 视图→特征→策略 三层解耦 */
export function BrandLogo({ size = 26 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" style={{ flexShrink: 0, display: 'block' }}>
      <defs>
        <linearGradient id="brand-logo-g" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#F08A24" />
          <stop offset="1" stopColor="#B93A0A" />
        </linearGradient>
      </defs>
      <rect width="24" height="24" rx="6.5" fill="url(#brand-logo-g)" />
      <g stroke="#fff" strokeWidth="1.7" strokeLinecap="round" fill="none">
        <path d="M12 8v2.6" />
        <path d="M12 10.6 7.4 15.4" />
        <path d="M12 10.6 16.6 15.4" />
      </g>
      <circle cx="12" cy="6.6" r="2" fill="#fff" />
      <circle cx="6.9" cy="17.1" r="2" fill="#fff" />
      <circle cx="17.1" cy="17.1" r="2" fill="#fff" />
    </svg>
  );
}

export default function AppLayout() {
  const loc = useLocation();
  // 同一路由被多个入口引用时只高亮第一个，避免侧栏同时点亮两项
  const nav = useMemo(() => {
    const seen = new Set();
    return NAV.map((g) => ({
      ...g,
      items: g.items.map((it) => {
        const key = it.to.split('?')[0];
        const active = loc.pathname === key && !seen.has(key);
        if (active) seen.add(key);
        return { ...it, active };
      })
    }));
  }, [loc.pathname]);

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <div className="title"><BrandLogo /><span>风控策略平台</span></div>
        </div>
        {nav.map((g) => (
          <div className="nav-group" key={g.group}>
            <div className="group-label">{g.group}</div>
            {g.items.map((it) => (
              <NavLink
                key={it.label + it.to}
                to={it.to}
                className={'nav-item' + (it.active ? ' active' : '')}
                end={it.to === '/dashboard'}
                title={it.label}
              >
                <span className="nav-icon">{it.icon}</span>
                <span>{it.label}</span>
              </NavLink>
            ))}
          </div>
        ))}
        <div className="footer">
          PROD · 引擎 8/8 就绪<br />v1.4.2 · build 20261011
        </div>
      </aside>
      <div className="main">
        <div className="topbar">
          <div className="crumb">{renderCrumb(loc.pathname)}</div>
          <QuickSearch nav={nav} />
        </div>
        <div className="content">
          <Outlet />
        </div>
      </div>
    </div>
  );
}

function renderCrumb(path) {
  const m = CRUMB[path] || (path.startsWith('/strategy') ? ['决策中心', '策略编排'] : null);
  if (!m) return <b>风控策略平台</b>;
  return (
    <>
      <b>{m[0]}</b> <span style={{ margin: '0 8px', color: '#C9BBA9' }}>/</span>
      {m[1]}
    </>
  );
}

/* 顶栏快捷跳转：输入即搜页面，支持 ↑↓ / Enter / Esc */
function QuickSearch({ nav }) {
  const navigate = useNavigate();
  const [q, setQ] = useState('');
  const [open, setOpen] = useState(false);
  const [sel, setSel] = useState(0);
  const [focus, setFocus] = useState(false);
  const boxRef = useRef(null);
  const inputRef = useRef(null);

  const flat = useMemo(() => nav.flatMap((g) => g.items.map((it) => ({ ...it, group: g.group }))), [nav]);
  const hits = useMemo(() => {
    const kw = q.trim().toLowerCase();
    if (!kw) return flat;
    return flat.filter((it) =>
      it.label.toLowerCase().includes(kw) || (it.alias || '').toLowerCase().includes(kw)
    );
  }, [flat, q]);

  useEffect(() => { setSel(0); }, [q]);

  // 点击外部关闭
  useEffect(() => {
    if (!open) return;
    const onDoc = (e) => { if (boxRef.current && !boxRef.current.contains(e.target)) setOpen(false); };
    document.addEventListener('mousedown', onDoc);
    return () => document.removeEventListener('mousedown', onDoc);
  }, [open]);

  // Ctrl/Cmd + K 聚焦搜索
  useEffect(() => {
    const onKey = (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault(); inputRef.current?.focus(); setOpen(true);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const go = (it) => { if (it) { navigate(it.to); setQ(''); setOpen(false); inputRef.current?.blur(); } };

  const onKeyDown = (e) => {
    if (e.key === 'ArrowDown') { e.preventDefault(); setOpen(true); setSel((s) => Math.min(s + 1, hits.length - 1)); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setSel((s) => Math.max(s - 1, 0)); }
    else if (e.key === 'Enter') { e.preventDefault(); go(hits[sel]); }
    else if (e.key === 'Escape') { setOpen(false); inputRef.current?.blur(); }
  };

  const highlight = (text) => {
    const kw = q.trim();
    if (!kw) return text;
    const i = text.toLowerCase().indexOf(kw.toLowerCase());
    if (i < 0) return text;
    return <>{text.slice(0, i)}<mark>{text.slice(i, i + kw.length)}</mark>{text.slice(i + kw.length)}</>;
  };

  return (
    <div className={'quick-search' + (focus ? ' focus' : '')} ref={boxRef}>
      <div className="qs-input" onClick={() => { inputRef.current?.focus(); setOpen(true); }}>
        <IconSearch />
        <input
          ref={inputRef}
          value={q}
          placeholder="搜索页面 / 跳转发（Ctrl+K）"
          onChange={(e) => { setQ(e.target.value); setOpen(true); }}
          onFocus={() => { setFocus(true); setOpen(true); }}
          onBlur={() => setFocus(false)}
          onKeyDown={onKeyDown}
          aria-label="搜索页面"
        />
        {!q && <span className="qs-kbd">Ctrl K</span>}
      </div>
      {open && (
        <div className="qs-menu">
          {hits.length ? (
            hits.map((it, i) => {
              const prev = i > 0 ? hits[i - 1].group : null;
              return (
                <React.Fragment key={it.label + it.to}>
                  {prev !== it.group && <div className="qs-group">{it.group}</div>}
                  <div
                    className={'qs-item' + (i === sel ? ' sel' : '')}
                    onMouseEnter={() => setSel(i)}
                    onMouseDown={(e) => e.preventDefault()}
                    onClick={() => go(it)}
                  >
                    <span className="qs-ico">{it.icon}</span>
                    <span>{highlight(it.label)}</span>
                  </div>
                </React.Fragment>
              );
            })
          ) : (
            <div className="qs-empty">没有匹配的页面，试试「视图」「特征」「试算」</div>
          )}
        </div>
      )}
    </div>
  );
}
