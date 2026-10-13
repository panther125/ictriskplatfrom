import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client.js';
import { ConclusionBadge, StatusTag } from '../components/Common.jsx';

/** 试算入参模板：overdue_cnt / risk_score / apply_amount 是决策条件实际读取的字段 */
const DEFAULT_PARAMS = {
  user_id: 'U100823',
  apply_amount: 50000,
  channel: 'APP',
  user_level: 'B',
  overdue_cnt: 3,
  risk_score: 742,
  debug: true
};

const SCENE_PRESET = {
  '命中缓存': { overdue_cnt: 2, risk_score: 700 },
  '降级场景': { overdue_cnt: 0, risk_score: 810 },
  '字段缺失': { overdue_cnt: 0, risk_score: 600 },
  '上次示例': DEFAULT_PARAMS
};

/** 解析输入框文本：空文本视作空对象；非法 JSON / 非对象给出可直接展示的错误 */
const parseParamsText = (text) => {
  const t = String(text ?? '').trim();
  if (!t) return { ok: true, params: {} };
  try {
    const v = JSON.parse(t);
    if (!v || typeof v !== 'object' || Array.isArray(v)) {
      return { ok: false, error: '试算入参必须是 JSON 对象，例如 {"user_id":"U100823","overdue_cnt":3}' };
    }
    return { ok: true, params: v };
  } catch (e) {
    return { ok: false, error: `JSON 解析失败：${e.message}` };
  }
};

const splitStep = (title) => {
  const parts = String(title || '').split('·');
  return { head: parts[0] || '—', rest: parts.slice(1).join(' · ') || '—' };
};

const truncateText = (s, n) => {
  const t = String(s ?? '');
  return t.length > n ? `${t.slice(0, n)}…` : t;
};

const jsonBrief = (v) => {
  try { return truncateText(JSON.stringify(v), 120); } catch { return String(v); }
};

/* ---- 逐节点 / 特征计算 / 流量判定卡片的行内样式（对齐 mockups/trial-traffic-route.html） ---- */
const MINI_TAG = {
  display: 'inline-block', fontSize: 10.5, lineHeight: '16px', padding: '0 6px',
  borderRadius: 4, background: 'var(--bg-workspace)', border: '1px solid var(--stroke-weak)',
  color: 'var(--ink-2)', marginLeft: 4, verticalAlign: '1px', fontFamily: 'var(--font-mono)'
};
const FR_LINE = { fontSize: 12, color: '#5C4A38', lineHeight: 1.9, marginTop: 6 };
const FR_LBL = { color: 'var(--ink-3)', marginRight: 4 };
const FR_WARN = { fontSize: 12, color: '#B45309', lineHeight: 1.9, marginTop: 6 };
const HIT_PILL = {
  display: 'inline-flex', alignItems: 'center', gap: 6, height: 22, padding: '0 10px',
  borderRadius: 999, background: 'rgba(26,127,90,.12)', color: 'var(--pass)',
  fontSize: 12, fontWeight: 600
};
const HIT_ROW_BG = { background: 'rgba(232,116,15,.10)' };
const ND_TH = { padding: '6px 8px', height: 'auto', whiteSpace: 'nowrap' };
const ND_TD = { padding: '7px 8px', height: 'auto', verticalAlign: 'top', lineHeight: 1.65 };
const NODE_RESULT_BADGE = {
  HIT: 'badge-pass', COND_TRUE: 'badge-pass',
  COND_FALSE: 'badge-unconfig', EVAL_FAIL: 'badge-unconfig',
  INFO: 'badge-unconfig', OUTPUT: 'badge-unconfig'
};
const fmtMs = (n) => Math.round((Number(n) || 0) * 10) / 10;

/** 节点「判定表达式 / 计算」列：主行表达式 + 副行细节（FEATURE 细节已含表达式本身，去掉前缀防重复） */
const nodeCalcText = (n) => {
  if (!n.expr) return { main: null, sub: n.detail || null };
  let sub = n.detail || '';
  if (sub.startsWith(n.expr)) sub = sub.slice(n.expr.length).replace(/^\s*→\s*/, '');
  return { main: n.expr, sub: sub || null };
};

/** 结论来源：编排图命中 / 默认规则集 —— 让使用者知道结论是谁给的 */
const execSourceText = (src) => ({
  GRAPH: '编排图命中',
  DEFAULT_RULES: '默认规则集（该版本未编排决策图）',
  DEFAULT_RULES_NO_MATCH: '默认规则集（未命中编排条件）'
}[src] || (src || '—'));

export default function Trial() {
  const navigate = useNavigate();
  const [tab, setTab] = useState('单笔调试');
  // 原始文本为唯一数据源：勿改回「parse 后 setState 回写」——非法中间态不会 setState，React 受控回滚会让输入框改不动
  const [paramsText, setParamsText] = useState(() => JSON.stringify(DEFAULT_PARAMS, null, 2));
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [resolving, setResolving] = useState(false);
  // 解析入参结果（null = 尚未解析过）：仅用于按钮行下方的提示行
  const [resolveNote, setResolveNote] = useState(null);
  const paramsParse = useMemo(() => parseParamsText(paramsText), [paramsText]);

  // ---- 试算对象：策略（按 code 定位） + 版本 ----
  const [strategies, setStrategies] = useState([]);
  const [strategyCode, setStrategyCode] = useState('');
  const [versionId, setVersionId] = useState(null);

  const [cases, setCases] = useState([]);
  const [caseResults, setCaseResults] = useState(null);
  const [runningCases, setRunningCases] = useState(false);

  const [replayTraceIds, setReplayTraceIds] = useState('[]');
  const [replayVersion, setReplayVersion] = useState('');
  const [replayResult, setReplayResult] = useState(null);
  const [shadowResult, setShadowResult] = useState(null);

  const strategy = useMemo(
    () => strategies.find(s => s.code === strategyCode) || null,
    [strategies, strategyCode]
  );
  const version = useMemo(
    () => (strategy?.versions || []).find(v => v.id === versionId) || null,
    [strategy, versionId]
  );
  // 自动模式 = 不指定版本：后端按切流配置判定命中版本后执行（routing 随试算结果回显）
  const autoMode = !!strategy && versionId == null;

  // 切换策略/版本要自动重新解析入参，但解析会改写 paramsText → 不能进依赖数组（否则死循环）。
  // 用 ref 读最新的入参文本与试算对象，effect 只依赖「策略 code + 版本 id」。
  const latest = useRef({});
  latest.current = { paramsText, strategy, versionId };

  // 策略清单（含各策略的版本），首次进入默认选中第一个策略；版本默认「自动 · 按切流判定」
  useEffect(() => {
    let alive = true;
    api.trial.strategies()
      .then((list) => {
        if (!alive) return;
        const arr = list || [];
        setStrategies(arr);
        if (arr.length) {
          const s = arr[0];
          const defId = s.defaultVersionId ?? s.versions?.[0]?.id ?? null;
          setStrategyCode(s.code);
          setVersionId(null);
          setReplayVersion((s.versions || []).find(v => v.id === defId)?.versionNo || '');
        }
      })
      .catch((e) => { if (alive) setError(e.message || '策略列表加载失败'); });
    return () => { alive = false; };
  }, []);

  // 回归用例集跟随所选策略
  useEffect(() => {
    if (!strategy?.id) { setCases([]); return undefined; }
    let alive = true;
    api.trial.cases(strategy.id)
      .then(d => { if (alive) setCases(d || []); })
      .catch(() => { if (alive) setCases([]); });
    return () => { alive = false; };
  }, [strategy?.id]);

  const changeStrategy = (code) => {
    const s = strategies.find(x => x.code === code);
    // 默认「自动」：不带 version_id，由切流判定；回放目标版本仍取推荐版本
    const defId = s?.defaultVersionId ?? s?.versions?.[0]?.id ?? null;
    setStrategyCode(code);
    setVersionId(null);
    setReplayVersion((s?.versions || []).find(v => v.id === defId)?.versionNo || '');
    setResult(null); setCaseResults(null); setShadowResult(null); setResolveNote(null);
  };

  const changeVersion = (val) => {
    const id = val === '' ? null : Number(val);
    setVersionId(id);
    const v = (strategy?.versions || []).find(x => x.id === id);
    if (v) setReplayVersion(v.versionNo);
    setResult(null); setResolveNote(null);
  };

  /** 试算请求体：带上所选策略编码（后端按 code 定位）与版本 */
  const trialPayload = useCallback((extra) => {
    const p = { ...(extra || paramsParse.params) };
    if (strategy) p.strategy_code = strategy.code;
    if (versionId) p.version_id = versionId;
    return p;
  }, [paramsParse, strategy, versionId]);

  const run = async () => {
    if (!strategy) { setError('请先选择试算策略'); return; }
    if (!paramsParse.ok) { setError(paramsParse.error); return; }
    setLoading(true); setError('');
    try {
      setResult(await api.trial.execute(trialPayload()));
    } catch (e) {
      // 不再用 mock 静默兜底：后端异常必须暴露，避免「假成功」
      setResult(null);
      setError(e.message || '试算失败');
    } finally {
      setLoading(false);
    }
  };

  /**
   * 解析入参：按当前试算对象（策略 + 版本）扫描决策图引用，重建输入框键集 ——
   * 同名键沿用输入框当前值，新键补模板占位值，与该策略无关的旧键移除；
   * 输入框为空或非法 JSON 时直接用解析模板生成。
   *
   * 依赖空数组是刻意的：入参文本与试算对象都从 latest.current 读，
   * 这样它才能被「切换策略/版本」的 effect 安全复用而不自触发。
   */
  const doResolveInputs = useCallback(async () => {
    const { paramsText: text, strategy: s, versionId: vid } = latest.current;
    if (!s) { setError('请先选择试算策略'); return null; }
    setResolving(true); setError('');
    try {
      const parsedNow = parseParamsText(text);
      const data = await api.trial.resolveInputs({
        strategy_code: s.code,
        ...(vid ? { version_id: vid } : {}),
        // 自动模式：带上当前输入框 JSON，后端按同一份切流算法判定「本笔将执行哪个版本」再解析其入参
        ...(parsedNow.ok ? { params: parsedNow.params } : {})
      });
      const current = parsedNow.ok ? parsedNow.params : {};
      const template = data.template || {};
      const keys = Object.keys(template);
      // 无引用可解析（如各版本均未编排）时保持输入框原样，避免被清成空对象
      if (keys.length > 0) {
        const next = {};
        keys.forEach(k => {
          next[k] = Object.prototype.hasOwnProperty.call(current, k) ? current[k] : template[k];
        });
        setParamsText(JSON.stringify(next, null, 2));
      }
      setResolveNote(data);
      return data;
    } catch (e) {
      setError(e.message || '解析入参失败');
      return null;
    } finally {
      setResolving(false);
    }
  }, []);

  // 切换策略或版本即自动解析该版本引用的入参到试算输入，无需再手动点「解析入参」
  useEffect(() => {
    if (!strategyCode) return;
    doResolveInputs();
  }, [strategyCode, versionId, doResolveInputs]);

  const runAllCases = async () => {
    setRunningCases(true); setError('');
    try {
      setCaseResults(await api.trial.runAllCases(strategy?.id));
    } catch (e) {
      setError(e.message || '用例执行失败');
    } finally {
      setRunningCases(false);
    }
  };

  const saveAsCase = async () => {
    if (!result) return;
    if (!paramsParse.ok) { setError(paramsParse.error); return; }
    setError('');
    try {
      await api.trial.createCase({
        name: `case_${new Date().toISOString().slice(0, 10).replace(/-/g, '')}_${cases.length + 1}`,
        strategyId: strategy?.id ?? null,
        inputsJson: JSON.stringify(trialPayload()),
        expectConclusion: result.response?.conclusion || result.record?.conclusion,
        tags: '手工保存'
      });
      setCases(await api.trial.cases(strategy?.id));
    } catch (e) {
      setError(e.message || '保存用例失败');
    }
  };

  const doBatchReplay = async () => {
    setError('');
    try {
      const ids = JSON.parse(replayTraceIds || '[]');
      setReplayResult(await api.trial.batchReplay(ids, strategy?.id, replayVersion));
    } catch (e) {
      setError(e.message || '批量回放失败');
    }
  };

  const doShadowCompare = async () => {
    if (!strategy) { setError('请先选择试算策略'); return; }
    if (!paramsParse.ok) { setError(paramsParse.error); return; }
    setError('');
    try {
      setShadowResult(await api.trial.shadowCompare(trialPayload(), strategy.id));
    } catch (e) {
      setError(e.message || '影子对比失败');
    }
  };

  const statusOf = (c) => {
    const r = caseResults?.find(x => x.caseId === c.id);
    return r ? { actual: r.actual, status: r.status } : { actual: '—', status: '未运行' };
  };

  const noStrategy = strategies.length === 0;

  // ---- 试算结果派生：逐节点 / 特征计算 / 最终输出 ----
  const nodeDetails = result?.nodeDetails || [];
  const featureRuns = result?.featureRuns || [];
  const okRuns = featureRuns.filter(r => r.success);
  const nodeMs = nodeDetails.reduce((a, n) => a + (Number(n.elapsedMs) || 0), 0);
  const featMs = okRuns.reduce((a, r) => a + (Number(r.execElapsedMs ?? r.elapsedMs) || 0), 0);
  const featSourceNote = featureRuns.length === 0 ? null
    : okRuns.length === featureRuns.length ? '真实引擎值'
      : okRuns.length === 0 ? '内置派生兜底' : '真实引擎值 / 内置派生兜底';

  return (
    <div>
      <div className="page-header">
        <div>
          <h1>试算中心</h1>
          <div className="sub">运行态 / 试算中心 · 单笔调试 · 批量回放 · 影子对比</div>
        </div>
        <div className="actions" style={{ flexWrap: 'wrap', rowGap: 8 }}>
          <span style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>试算策略</span>
          <select
            className="select" style={{ width: 236 }}
            value={strategyCode} onChange={e => changeStrategy(e.target.value)}
            disabled={noStrategy} aria-label="试算策略"
          >
            {noStrategy && <option value="">（暂无策略，请先在策略管理创建）</option>}
            {strategies.map(s => (
              <option key={s.id} value={s.code}>{s.code} · {s.name}</option>
            ))}
          </select>
          <span style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>版本</span>
          <select
            className="select" style={{ width: 300 }}
            value={versionId ?? ''} onChange={e => changeVersion(e.target.value)}
            disabled={!(strategy?.versions || []).length} aria-label="试算版本"
          >
            {!(strategy?.versions || []).length && <option value="">（该策略暂无版本）</option>}
            {(strategy?.versions || []).length > 0 && <option value="">自动 · 按切流判定</option>}
            {(strategy?.versions || []).map(v => (
              <option key={v.id} value={v.id}>
                {v.versionNo} · {v.statusText} · {v.nodeCount > 0 ? `${v.nodeCount} 节点` : '未编排'} · 流量 {v.grayscalePct ?? 0}%
              </option>
            ))}
          </select>
        </div>
      </div>

      {/* 当前试算对象摘要：一眼看清这次试算跑的是哪个策略的哪个版本 */}
      <div style={{
        display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'center',
        marginBottom: 16, padding: '10px 14px', background: '#FFFCF8',
        border: '1px solid var(--stroke-weak)', borderRadius: 10,
        fontSize: 12.5, color: 'var(--ink-2)'
      }}>
        <span>策略 <b className="mono" style={{ color: 'var(--ink-1)' }}>{strategy?.code || '—'}</b></span>
        <span style={{ color: 'var(--ink-weak)' }}>|</span>
        <span>{strategy?.name || '未选择策略'}{strategy?.category ? ` · ${strategy.category}` : ''}</span>
        {strategy && <StatusTag text={strategy.statusText} />}
        <span style={{ color: 'var(--ink-weak)' }}>|</span>
        <span>版本 <b className="mono" style={{ color: 'var(--ink-1)' }}>{autoMode ? '自动' : (version?.versionNo || '—')}</b></span>
        {autoMode ? (
          result?.routing ? (
            result.routing.versionNo ? (
              <span>按切流配置判定 → 本次命中 <b className="mono" style={{ color: 'var(--decision)' }}>{result.routing.versionNo}</b></span>
            ) : (
              <span style={{ color: 'var(--ink-3)' }}>无在线版本，按配置最完整版本试算</span>
            )
          ) : (
            <span style={{ color: 'var(--ink-3)' }}>按切流配置判定命中版本（试算后回显）</span>
          )
        ) : (
          <>
            {version && <StatusTag text={version.statusText} />}
            {version && <span>{version.stateLabel || '—'} · 流量 {version.grayscalePct ?? 0}%{version.bundle ? ` · Bundle ${version.bundle}` : ''}</span>}
          </>
        )}
        <span style={{ color: 'var(--ink-weak)' }}>|</span>
        {autoMode ? (
          result ? (
            <span>决策节点 <b className="mono" style={{ color: 'var(--ink-1)' }}>{nodeDetails.length}</b> 个（命中版本）</span>
          ) : (
            <span>决策节点 <b className="mono" style={{ color: 'var(--ink-1)' }}>待判定</b></span>
          )
        ) : (
          <span>决策节点 <b className="mono" style={{ color: 'var(--ink-1)' }}>{version?.nodeCount ?? 0}</b> 个</span>
        )}
        <span style={{ color: 'var(--ink-weak)' }}>|</span>
        <span>回归用例 <b className="mono" style={{ color: 'var(--ink-1)' }}>{cases.length}</b> 条</span>
      </div>

      <div className="seg" style={{ marginBottom: 16 }}>
        {['单笔调试', '批量回放', '影子对比'].map(t => (
          <button key={t} className={tab === t ? 'active' : ''} onClick={() => setTab(t)}>{t}</button>
        ))}
        <span style={{ alignSelf: 'center', fontSize: 12.5, color: 'var(--ink-2)', marginLeft: 8 }}>回归用例集 · {cases.length}</span>
      </div>

      {error && (
        <div className="warn-bar" style={{ marginBottom: 16, justifyContent: 'flex-start', gap: 8 }}>
          <b style={{ color: 'var(--reject)' }}>请求失败</b> {error}
        </div>
      )}

      {tab === '批量回放' && (
        <div className="card" style={{ marginBottom: 16 }}>
          <div className="card-title">
            <span>批量回放</span>
            <span className="sub">POST /trial/batch-replay · 按当前策略 {strategy?.code || '—'} 回放</span>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: 12 }}>
            <div className="field" style={{ marginBottom: 0 }}>
              <label>traceId 列表（JSON 数组）</label>
              <textarea className="textarea" style={{ minHeight: 80 }} value={replayTraceIds} onChange={e => setReplayTraceIds(e.target.value)} />
            </div>
            <div className="field" style={{ marginBottom: 0 }}>
              <label>目标版本</label>
              <select className="select" value={replayVersion} onChange={e => setReplayVersion(e.target.value)}>
                {(strategy?.versions || []).map(v => (
                  <option key={v.id} value={v.versionNo}>{v.versionNo} · {v.statusText}</option>
                ))}
              </select>
              <button className="btn primary" style={{ marginTop: 10 }} onClick={doBatchReplay}>开始回放</button>
            </div>
          </div>
          {replayResult && (
            <table className="table" style={{ marginTop: 12 }}>
              <thead><tr><th>#</th><th>回放结论</th><th>状态</th></tr></thead>
              <tbody>
                {replayResult.map((r, i) => (
                  <tr key={i}>
                    <td className="mono" style={{ fontSize: 12.5 }}>{i + 1}</td>
                    <td><ConclusionBadge text={r.actual} /></td>
                    <td className="mono" style={{ fontSize: 12.5 }}>{r.status}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {replayResult && replayResult.length === 0 && <div className="empty">无可回放记录</div>}
        </div>
      )}

      {tab === '影子对比' && (
        <div className="card" style={{ marginBottom: 16 }}>
          <div className="card-title">
            <span>影子对比</span>
            <span className="sub">POST /trial/shadow-compare · 使用左栏入参 · 策略 {strategy?.code || '—'}</span>
          </div>
          <button className="btn primary" onClick={doShadowCompare}>执行对比</button>
          {shadowResult && (
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginTop: 12 }}>
              <div className="prop-row"><div className="k">线上版本结论</div><div className="v"><ConclusionBadge text={shadowResult.onlineResult?.conclusion} /> · {shadowResult.onlineResult?.elapsedMs}ms</div></div>
              <div className="prop-row"><div className="k">候选版本结论</div><div className="v"><ConclusionBadge text={shadowResult.candidateResult?.conclusion} /> · {shadowResult.candidateResult?.elapsedMs}ms</div></div>
              <div style={{ gridColumn: '1 / -1' }}>
                <span className={`badge ${shadowResult.sameConclusion ? 'badge-pass' : 'badge-reject'}`}>
                  {shadowResult.sameConclusion ? '结论一致' : '结论不一致'}
                </span>
              </div>
            </div>
          )}
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) minmax(0,1.4fr)', gap: 16 }}>
        {/* 左栏：输入 + 回归用例 */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div className="card">
            <div className="card-title"><span>试算输入</span><span className="sub">POST /trial/execute</span></div>
            <textarea className="textarea" style={{ minHeight: 160 }}
              value={paramsText}
              onChange={e => setParamsText(e.target.value)} />
            {!paramsParse.ok && (
              <div style={{ fontSize: 12, color: 'var(--reject)', marginTop: 6 }}>{paramsParse.error}</div>
            )}
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 10, flexWrap: 'wrap' }}>
              <span style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>试算对象</span>
              <span className="tag tag-gray" style={{ background: 'rgba(194,65,12,.10)', color: 'var(--decision)', border: 'none' }}>
                {strategy?.code || '—'} · {autoMode ? '自动（按切流判定）' : (version?.versionNo || '—')}
                {autoMode
                  ? (result?.routing?.versionNo ? ` → 命中 ${result.routing.versionNo}` : '')
                  : ` · 流量 ${version?.grayscalePct ?? 0}%`}
              </span>
            </div>
            <div style={{ display: 'flex', gap: 6, marginTop: 8, flexWrap: 'wrap' }}>
              <button className="btn sm" disabled={!strategy || resolving} onClick={doResolveInputs}>
                {resolving ? '解析中…' : '解析入参'}
              </button>
              {Object.keys(SCENE_PRESET).map(k => (
                <button key={k} className="btn sm" onClick={() => setParamsText(prev => {
                  const parsed = parseParamsText(prev);
                  return JSON.stringify({ ...(parsed.ok ? parsed.params : DEFAULT_PARAMS), ...SCENE_PRESET[k] }, null, 2);
                })}>{k}</button>
              ))}
            </div>
            {resolveNote && (
              <div style={{ marginTop: 10 }}>
                <div style={{ fontSize: 12, lineHeight: 1.8 }}>
                  <span style={{ color: 'var(--pass)', fontWeight: 600 }}>
                    ✓ 已按 {resolveNote.version || '自动命中版本'} 解析入参
                  </span>
                  <span style={{ color: 'var(--ink-2)' }}>
                    ：策略自定义入参 {resolveNote.counts?.paramCount ?? 0} 个
                    {' · '}引用特征 {resolveNote.counts?.featureCount ?? 0} 个
                    {' · '}特征入参合计 {resolveNote.counts?.featureInputCount ?? 0} 个，已填入下方输入框
                  </span>
                  {resolveNote.routing?.versionNo && (
                    <div style={{ color: 'var(--ink-2)' }}>
                      ⓘ 自动模式：按切流判定本笔将执行 <b className="mono" style={{ color: 'var(--decision)' }}>{resolveNote.routing.versionNo}</b>
                      {resolveNote.routing.slot != null && `（哈希槽位 ${resolveNote.routing.slot}）`}
                      {resolveNote.emptyGraph ? '，该版本未编排决策图。' : '，已按该版本引用解析入参。'}
                    </div>
                  )}
                  {(resolveNote.warnings || []).map((w, i) => (
                    <div key={i} style={{ color: '#B45309' }}>⚠ {w}</div>
                  ))}
                </div>

                <div className="grid grid-2" style={{ gap: 10, marginTop: 8 }}>
                  <div>
                    <div className="sub" style={{ marginBottom: 4 }}>策略自定义入参（决策图 param.* 引用）</div>
                    {(resolveNote.params || []).length === 0 ? (
                      <div className="sub">该版本未引用策略入参</div>
                    ) : (
                      <table className="table" style={{ fontSize: 12 }}>
                        <thead><tr><th>入参名</th><th>引用节点</th></tr></thead>
                        <tbody>
                          {(resolveNote.params || []).map((p, i) => (
                            <tr key={i}>
                              <td className="mono">{p.name}</td>
                              <td className="sub">{(p.refNodes || []).join('、') || '—'}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    )}
                  </div>
                  <div>
                    <div className="sub" style={{ marginBottom: 4 }}>特征入参（决策图 feat.* 引用 · 展开特征定义）</div>
                    {(resolveNote.features || []).length === 0 ? (
                      <div className="sub">该版本未引用特征</div>
                    ) : (
                      <table className="table" style={{ fontSize: 12 }}>
                        <thead><tr><th>特征</th><th>入参名</th><th>类型</th><th>默认值</th><th>来源</th></tr></thead>
                        <tbody>
                          {(resolveNote.features || []).flatMap((f, fi) =>
                            (f.inputs || []).length === 0
                              ? [(
                                <tr key={`${f.code}-none`}>
                                  <td className="mono">{f.code}</td>
                                  <td colSpan={4} className="sub">无入参</td>
                                </tr>
                              )]
                              : (f.inputs || []).map((inp, ii) => (
                                <tr key={`${f.code}-${ii}`}>
                                  <td className="mono">{ii === 0 ? f.code : ''}</td>
                                  <td className="mono">{inp.name}</td>
                                  <td className="sub">{inp.type || '—'}</td>
                                  <td className="mono">{inp.defaultValue ?? inp.sampleValue ?? '—'}</td>
                                  <td className="sub">{inp.source || '—'}</td>
                                </tr>
                              ))
                          )}
                        </tbody>
                      </table>
                    )}
                  </div>
                </div>
              </div>
            )}
            <div style={{ fontSize: 12, color: 'var(--ink-3)', marginTop: 8, lineHeight: 1.7 }}>
              {autoMode ? (
                <>自动模式：先按<b>切流配置</b>判定本笔将执行的版本（按 user_id 哈希固定，可复现），再运行命中版本——带图版本按节点条件<b>真实求值</b>（按优先级短路），未编排走<b>默认准入规则集</b>。</>
              ) : version?.nodeCount > 0 ? (
                <>该版本登记了 {version.nodeCount} 个决策节点，试算按节点条件<b>真实求值</b>（按优先级短路），命中哪个节点就用它的结论与原因码。</>
              ) : (
                <>所选版本未编排决策图，试算走<b>默认准入规则集</b>：overdue_cnt ≥ 3 → REJECT(R007)；risk_score ≥ 800 → REJECT(R015)；其余 → REVIEW。</>
              )}
              <br />特征计算：决策图引用到的特征按四引擎（SQL / Java / Python / OP）<b>真实执行</b>，出参回填 feat.* 参与求值；引擎不可用时回退内置派生并挂 ⚠。debug=true 时记录完整 trace。
            </div>
            <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
              <button className="btn primary" onClick={run} disabled={loading || !strategy}>{loading ? '试算中…' : '试算 →'}</button>
              <button className="btn" onClick={saveAsCase} disabled={!result}>保存为用例</button>
            </div>
          </div>

          <div className="card">
            <div className="card-title">
              <span>回归用例集</span>
              <span className="sub">{cases.length} 条 · 仅当前策略 · 发布前自动全量跑</span>
            </div>
            <table className="table">
              <thead><tr><th>用例</th><th>期望</th><th>实际</th><th>状态</th></tr></thead>
              <tbody>
                {cases.map(c => {
                  const s = statusOf(c);
                  return (
                    <tr key={c.id}>
                      <td style={{ fontSize: 12.5 }}>{c.name}</td>
                      <td><ConclusionBadge text={c.expectConclusion} /></td>
                      <td>{s.actual === '—' ? <span style={{ fontSize: 12.5, color: 'var(--ink-3)' }}>—</span> : <ConclusionBadge text={s.actual} />}</td>
                      <td>
                        <span className={`badge ${s.status === '一致' ? 'badge-pass' : s.status === '不一致' ? 'badge-reject' : 'badge-unconfig'}`}>
                          {s.status}
                        </span>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            {cases.length === 0 && (
              <div className="empty">
                当前策略「{strategy?.code || '—'}」暂无用例，可在试算后点「保存为用例」创建
              </div>
            )}
            <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
              <button className="btn sm" onClick={runAllCases} disabled={runningCases || cases.length === 0}>
                {runningCases ? '执行中…' : `运行全部 ${cases.length} 条用例`}
              </button>
            </div>
          </div>
        </div>

        {/* 右栏：流量判定 → 逐节点计算 → 特征计算 → 最终输出 → 变量快照 */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>

          {result?.routing && (
            <div className="card">
              <div className="card-title">
                <span>流量判定 · 命中版本</span>
                <span className="sub">按切流配置模拟 · 不连生产路由</span>
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', marginBottom: 10 }}>
                <span className="tag tag-gray" style={{ background: 'rgba(194,65,12,.10)', color: 'var(--decision)', border: 'none' }}>
                  {result.routing.modeText || '—'}
                </span>
                <span style={{ fontSize: 12, color: '#5C4A38' }}>
                  <span style={{ color: 'var(--ink-3)' }}>试算键 </span>
                  {result.routing.bizKey || 'user_id'} = <b className="mono">{result.routing.bizValue ?? '（缺省）'}</b>
                  {result.routing.slot != null && <> → 哈希槽位 <b className="mono">{result.routing.slot}</b></>}
                </span>
              </div>
              <table className="table">
                <thead>
                  <tr><th>版本</th><th>状态</th><th>份额区间</th><th>份额</th><th style={{ width: 120 }}>判定</th></tr>
                </thead>
                <tbody>
                  {(result.routing.candidates || []).map((c, i) => (
                    <tr key={i} style={c.hit ? HIT_ROW_BG : (c.eligible === false ? { opacity: .55 } : undefined)}>
                      <td className="mono"><b style={c.hit ? { color: 'var(--decision)' } : undefined}>{c.versionNo}</b></td>
                      <td><StatusTag text={c.statusText} /></td>
                      <td className="mono">{c.rangeText || '—'}</td>
                      <td className="mono">{c.pctNote || (c.pct != null ? `${c.pct}%` : '—')}</td>
                      <td>
                        {c.hit ? <span style={HIT_PILL}>● 命中</span>
                          : c.note ? <span style={{ fontSize: 12, color: 'var(--ink-3)' }}>{c.note}</span>
                            : <span style={{ color: 'var(--ink-3)', fontSize: 12.5 }}>—</span>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <div style={{ marginTop: 10, padding: '9px 12px', background: '#FFFCF8', borderRadius: 8, fontSize: 13 }}>
                {result.routing.reason || '—'}
              </div>
              {(result.routing.warnings || []).map((w, i) => (
                <div key={i} style={FR_WARN}>⚠ {w}</div>
              ))}
            </div>
          )}

          <div className="card">
            <div className="card-title">
              <span>执行路径 · 逐节点计算</span>
              {result && (
                <span className="mono" style={{ fontSize: 12.5, color: 'var(--ink-2)', display: 'flex', alignItems: 'center', gap: 8 }}>
                  {nodeDetails.length > 0 ? `${nodeDetails.length} 节点 · ` : `${result.steps?.length || 0} span · `}
                  traceId {result.record?.traceId}
                  <button
                    className="btn sm"
                    title="跳转到决策日志，按该 traceId 检索本次试算的全链路"
                    onClick={() => navigate(`/track?traceId=${encodeURIComponent(result.record?.traceId || '')}`)}
                  >
                    在决策日志中查看 →
                  </button>
                </span>
              )}
            </div>
            {result ? (
              <>
                {nodeDetails.length > 0 ? (
                  <table className="table" style={{ fontSize: 12 }}>
                    <thead>
                      <tr>
                        <th style={{ ...ND_TH, width: 132 }}>节点</th>
                        <th style={ND_TH}>判定表达式 / 计算</th>
                        <th style={{ ...ND_TH, width: 190 }}>读取变量</th>
                        <th style={{ ...ND_TH, width: 84 }}>结果</th>
                        <th style={{ ...ND_TH, width: 56 }}>耗时</th>
                      </tr>
                    </thead>
                    <tbody>
                      {nodeDetails.map((n, i) => {
                        const calc = nodeCalcText(n);
                        return (
                          <tr key={i} style={n.result === 'HIT' || n.result === 'COND_TRUE' ? HIT_ROW_BG : undefined}>
                            <td style={ND_TD}>
                              <b style={{ fontWeight: 600, color: 'var(--ink-1)' }}>{n.nodeId}</b>{' '}{n.title}
                              <span style={MINI_TAG}>{n.nodeType}</span>
                            </td>
                            <td style={ND_TD} className="mono">
                              {calc.main ? <div>{calc.main}</div> : null}
                              {calc.sub ? <div style={{ color: 'var(--ink-3)', fontSize: 11.5 }}>{calc.sub}</div> : null}
                              {!calc.main && !calc.sub ? '—' : null}
                            </td>
                            <td style={ND_TD} className="mono">
                              {(n.varsRead || []).length === 0 ? <span style={{ color: 'var(--ink-3)' }}>—</span>
                                : n.varsRead.map((v, j) => (
                                  <div key={j} style={v.missing ? { color: 'var(--ink-3)' } : undefined}>
                                    {v.name} = {v.missing ? '（缺值）' : jsonBrief(v.value)}
                                  </div>
                                ))}
                            </td>
                            <td style={ND_TD}><span className={`badge ${NODE_RESULT_BADGE[n.result] || 'badge-unconfig'}`}>{n.resultText}</span></td>
                            <td style={ND_TD} className="mono">{n.elapsedMs}ms</td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                ) : (
                  <table className="table">
                    <thead><tr><th>节点</th><th>输入输出 / 状态</th><th>耗时</th></tr></thead>
                    <tbody>
                      {(result.steps || []).map((s, i) => {
                        const { head, rest } = splitStep(s.stepTitle);
                        return (
                          <tr key={i}>
                            <td className="mono" style={{ fontSize: 12.5 }}>{head}</td>
                            <td style={{ fontSize: 12.5, color: '#5C4A38' }}>{rest}</td>
                            <td className="mono" style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>{s.elapsedMs}ms</td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                )}
                <div style={{ marginTop: 12, padding: '10px 12px', background: '#FFFCF8', borderRadius: 8, fontSize: 13.5 }}>
                  总耗时 <b style={{ color: 'var(--decision)' }}>{result.record?.elapsedMs ?? '—'}ms</b>
                  {featureRuns.length > 0 && <>（节点 {fmtMs(nodeMs)} + 特征真实执行 {fmtMs(featMs)}）</>}
                  {' · '}结论 <ConclusionBadge text={result.record?.conclusion} /> · 命中规则 <span className="mono">{result.record?.hitRule || '—'}</span> · 结论来源 <span className="mono">{execSourceText(result.response?.execSource)}</span> · 降级 {result.record?.degraded ? 1 : 0} 次
                  <span className="mono" style={{ float: 'right', fontSize: 12.5, color: 'var(--ink-2)' }}>
                    {result.record?.version} · {result.routing ? '自动判定命中' : '手动选择'}
                  </span>
                </div>
              </>
            ) : <div className="empty">点击「试算 →」执行后展示链路</div>}
          </div>

          {result && featureRuns.length > 0 && (
            <div className="card">
              <div className="card-title">
                <span>特征计算</span>
                <span className="sub">决策图引用特征 {featureRuns.length} 个 · 引擎真实执行 · 出参回填 feat.*</span>
              </div>
              {featureRuns.map((fr, i) => {
                const firstOut = (fr.outputs || []).find(o => !o.missing && o.value != null);
                return (
                  <div key={i} style={{ border: '1px solid var(--stroke-weak)', borderRadius: 10, padding: '10px 12px', marginTop: i === 0 ? 0 : 10 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                      <span className="mono" style={{ fontWeight: 600, color: 'var(--ink-1)', fontSize: 13 }}>{fr.code}</span>
                      {fr.name && <span style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>{fr.name}</span>}
                      {fr.engine && <span style={{ ...MINI_TAG, marginLeft: 0 }}>{fr.engine} 引擎</span>}
                      {fr.success ? (
                        <span style={{ ...MINI_TAG, marginLeft: 0 }}>真实 {fr.execElapsedMs ?? fr.elapsedMs ?? '—'}ms</span>
                      ) : (
                        <span style={{ ...MINI_TAG, marginLeft: 0, color: '#B45309', borderColor: '#F0C9A0' }}>兜底</span>
                      )}
                      {firstOut && <span style={{ ...MINI_TAG, marginLeft: 0 }}>出参 {firstOut.name} = {jsonBrief(firstOut.value)}</span>}
                    </div>
                    {(fr.valuesUsed || []).length > 0 && (
                      <div style={FR_LINE}>
                        <span style={FR_LBL}>入参取值</span>
                        <span className="mono">
                          {fr.valuesUsed.map(u => `${u.name} = ${u.provided ? jsonBrief(u.value) : '—'}（${u.provided ? '试算输入' : '未提供'}）`).join(' · ')}
                        </span>
                      </div>
                    )}
                    {fr.refValues && Object.keys(fr.refValues).length > 0 && (
                      <div style={FR_LINE}>
                        <span style={FR_LBL}>引用实值</span>
                        <span className="mono">
                          {Object.entries(fr.refValues).map(([k, v]) => `${String(k).startsWith('@@') ? k : `@@${k}`} = ${jsonBrief(v)}`).join(' · ')}
                        </span>
                      </div>
                    )}
                    {fr.exprPreview && (
                      <div style={FR_LINE}>
                        <span style={FR_LBL}>表达式</span>
                        <span className="mono">{truncateText(fr.exprPreview, 160)}</span>
                      </div>
                    )}
                    {fr.success && firstOut && (
                      <div style={FR_LINE}>
                        <span style={FR_LBL}>回填</span>
                        出参 {firstOut.name} → <b className="mono">feat.{fr.code} = {jsonBrief(firstOut.value)}</b>（引用名口径），节点 / 条件按此求值
                      </div>
                    )}
                    {fr.success && !firstOut && (
                      <div style={FR_WARN}>⚠ 引擎执行成功但出参为空，feat.{fr.code} 未回填</div>
                    )}
                    {(fr.warnings || []).map((w, j) => <div key={j} style={FR_WARN}>⚠ {w}</div>)}
                  </div>
                );
              })}
            </div>
          )}

          <div className="card">
            <div className="card-title"><span>最终输出</span><span className="sub">结论 · 原因码 · 执行版本 · 报文</span></div>
            {result ? (
              <>
                <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12, flexWrap: 'wrap' }}>
                  <ConclusionBadge text={result.record?.conclusion} />
                  <span style={{ fontSize: 13, color: 'var(--ink-2)' }}>
                    {result.routing?.versionNo
                      ? <>按切流判定命中 <b className="mono" style={{ color: 'var(--decision)' }}>{result.routing.versionNo}</b></>
                      : <>手动指定 <b className="mono" style={{ color: 'var(--decision)' }}>{result.record?.version}</b></>}
                    {' · '}{execSourceText(result.response?.execSource)}
                  </span>
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0,1fr))', gap: '8px 12px', fontSize: 12.5 }}>
                  <div className="prop-row" style={{ border: 'none', padding: '2px 0', marginBottom: 0 }}>
                    <div className="k" style={{ width: 68 }}>原因码</div>
                    <div className="v">{result.record?.reasonCode || '—'}</div>
                  </div>
                  <div className="prop-row" style={{ border: 'none', padding: '2px 0', marginBottom: 0 }}>
                    <div className="k" style={{ width: 68 }}>命中规则</div>
                    <div className="v">{result.record?.hitRule || '—'}</div>
                  </div>
                  <div className="prop-row" style={{ border: 'none', padding: '2px 0', marginBottom: 0 }}>
                    <div className="k" style={{ width: 68 }}>结论来源</div>
                    <div className="v">{execSourceText(result.response?.execSource)}</div>
                  </div>
                  <div className="prop-row" style={{ border: 'none', padding: '2px 0', marginBottom: 0 }}>
                    <div className="k" style={{ width: 68 }}>执行版本</div>
                    <div className="v">{result.record?.version}{result.routing ? '（自动判定）' : '（手动）'}</div>
                  </div>
                  <div className="prop-row" style={{ border: 'none', padding: '2px 0', marginBottom: 0 }}>
                    <div className="k" style={{ width: 68 }}>总耗时</div>
                    <div className="v">{result.record?.elapsedMs ?? '—'}ms</div>
                  </div>
                  <div className="prop-row" style={{ border: 'none', padding: '2px 0', marginBottom: 0 }}>
                    <div className="k" style={{ width: 68 }}>特征真实计算</div>
                    <div className="v">{featureRuns.length > 0 ? `${okRuns.length} / ${featureRuns.length}` : '未引用'}</div>
                  </div>
                  <div className="prop-row" style={{ border: 'none', padding: '2px 0', marginBottom: 0 }}>
                    <div className="k" style={{ width: 68 }}>traceId</div>
                    <div className="v">{result.record?.traceId || '—'}</div>
                  </div>
                  <div className="prop-row" style={{ border: 'none', padding: '2px 0', marginBottom: 0 }}>
                    <div className="k" style={{ width: 68 }}>降级</div>
                    <div className="v">{result.record?.degraded ? 1 : 0} 次</div>
                  </div>
                  <div className="prop-row" style={{ border: 'none', padding: '2px 0', marginBottom: 0 }}>
                    <div className="k" style={{ width: 68 }}>Bundle</div>
                    <div className="v">{result.response?.hitBundle || '—'}</div>
                  </div>
                </div>
                <div className="code-block" style={{ marginTop: 12 }}>{JSON.stringify(result.response, null, 2)}</div>
              </>
            ) : <div className="empty">—</div>}
          </div>

          <div className="card">
            <div className="card-title"><span>变量快照</span><span className="sub">view / feat / param / node</span></div>
            {result ? (
              Object.entries(result.variableSnapshot || {}).map(([group, vars]) => (
                <div key={group} style={{ marginBottom: 10 }}>
                  <div style={{ fontSize: 12.5, color: 'var(--ink-2)', marginBottom: 4 }}>
                    {group}
                    {group.startsWith('feat') && featSourceNote && <span style={{ color: 'var(--ink-3)' }}> · {featSourceNote}</span>}
                  </div>
                  {Object.entries(vars || {}).map(([k, v]) => (
                    <div key={k} className="mono" style={{ fontSize: 12.5 }}>
                      {k} = <b style={{ color: 'var(--decision)' }}>{String(v)}</b>
                    </div>
                  ))}
                </div>
              ))
            ) : <div className="empty">—</div>}
          </div>
        </div>
      </div>
    </div>
  );
}
