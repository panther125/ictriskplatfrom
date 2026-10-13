import React, { useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/client.js';
import { foldLongValues, LongValueCell } from '../components/Common.jsx';

/**
 * 新建特征：先选「基础特征 / 衍生特征」，两条分支分别有各自的配置与验证方式。
 *
 *  · 基础特征：步骤 1 只选视图（限已发布上线）；步骤 2 从该视图的出参清单里选 1 个作为特征输出值
 *              （1:1 引用，不加工）。出参确定后才创建草稿，验证时直接复用该视图登记的入参去跑视图取数。
 *  · 衍生特征：写表达式，用 @@基础特征编码 引用上游基础特征；
 *              验证时自动解析所有 @@ 引用，合并去重出被引用基础特征的入参供填写。
 */

const TYPE_META = {
  BASE: {
    key: 'BASE',
    title: '基础特征',
    tag: '1:1 引用视图出参',
    desc: '不加工，直接把视图的一个出参语义化成特征。仅可选择「已发布上线」的视图（草稿 / 降级中不可选）；价值在口径收敛与血缘治理，验证时复用视图入参。',
    steps: ['基本信息与视图绑定', '入参与出参', '验证', '发布前检查']
  },
  DERIVED: {
    key: 'DERIVED',
    title: '衍生特征',
    tag: '表达式加工 / @@ 引用',
    desc: '用表达式加工产出，可用 @@基础特征编码 引用上游基础特征；验证时自动解析引用并合并其入参。',
    steps: ['基本信息与表达式', '入参与出参', '验证', '发布前检查']
  }
};

const DATA_TYPES = ['STRING', 'INTEGER', 'LONG', 'DECIMAL', 'BOOLEAN', 'DATETIME'];

const ENGINE_LABELS = {
  SQL: 'SQL · Calcite 内存执行',
  JAVA: 'Java · Janino 编译执行',
  PYTHON: 'Python · 独立 Runner',
  OP: 'OP · 算子求值'
};

const SOURCE_STYLE = {
  VIEW: { text: '视图入参', bg: 'rgba(217,132,32,.12)', color: 'var(--view)' },
  FEATURE: { text: '引用特征入参', bg: 'rgba(232,116,15,.12)', color: 'var(--feature)' },
  PARAM: { text: '决策入参', bg: 'rgba(106,92,204,.12)', color: '#5C4FBF' },
  BUILTIN_TIME: { text: '系统内置', bg: 'rgba(140,123,107,.12)', color: '#6B5B4B' }
};

/**
 * 把最新解析出的入参并入「入参定义」列表：
 *  · 同名列保留用户改过的类型 / 必填 / 默认值 / 是否参与；
 *  · 已移除（enabled=false）与手工补充（source=MANUAL）的行，解析结果里没有也要留着，
 *    否则用户一改表达式，被移除的行就会自己“复活”、手工补的行会凭空消失。
 */
function mergeInputRows(rows, inputs) {
  const byName = new Map((rows || []).map(r => [r.varCode, r]));
  const next = [];
  const seen = new Set();
  for (const i of inputs || []) {
    const old = byName.get(i.name);
    seen.add(i.name);
    next.push({
      varCode: i.name,
      varType: old?.varType || i.type || 'STRING',
      source: old?.source || i.source || 'FEATURE',
      required: old ? old.required !== false : i.required !== false,
      defaultValue: old ? (old.defaultValue ?? '') : (i.defaultValue || ''),
      enabled: old ? old.enabled !== false : true,
      sourceLabel: i.sourceLabel || '',
      parsed: true
    });
  }
  for (const r of rows || []) {
    if (seen.has(r.varCode)) continue;
    if (r.enabled === false || r.source === 'MANUAL') next.push({ ...r });
  }
  return next;
}

function SourceTag({ source, label }) {
  const s = SOURCE_STYLE[source] || SOURCE_STYLE.VIEW;
  return (
    <span style={{ fontSize: 12, padding: '1px 6px', borderRadius: 4, background: s.bg, color: s.color, whiteSpace: 'nowrap' }}>
      {label || s.text}
    </span>
  );
}

export default function FeatureCreate({ onBack }) {
  const navigate = useNavigate();
  const { featureId: routeFeatureId } = useParams();
  const isEdit = !!routeFeatureId;
  const goBack = () => { if (onBack) onBack(); else navigate('/features'); };

  const [step, setStep] = useState(1);
  const [featureType, setFeatureType] = useState('BASE');
  const [views, setViews] = useState([]);
  const [viewDetail, setViewDetail] = useState(null);
  const [baseFeatures, setBaseFeatures] = useState([]);
  const [resolution, setResolution] = useState({ inputs: [], warnings: [] });
  const [refs, setRefs] = useState([]);
  const [resolving, setResolving] = useState(false);
  const [testValues, setTestValues] = useState({});
  const [trial, setTrial] = useState(null);
  const [precheck, setPrecheck] = useState([]);
  const [featureId, setFeatureId] = useState(() => (routeFeatureId ? Number(routeFeatureId) : null));
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState('');
  const [loading, setLoading] = useState(isEdit);
  const [readOnly, setReadOnly] = useState(false);
  const [nameCheck, setNameCheck] = useState(null);
  const [similars, setSimilars] = useState([]);
  const exprRef = useRef(null);

  const [form, setForm] = useState({
    code: 'user_overdue_level',
    displayName: '逾期等级',
    domain: 'user',
    owner: '王敏 · 风控策略组',
    businessDesc: '按用户近 90 日黑名单命中与逾期次数判定的风险等级，含当前逾期中订单；口径与风控报表 v3 一致。',
    computeType: 'SQL',
    dataType: 'STRING',
    expr: "CASE\n  WHEN @@user_blacklist_hit >= 1 THEN 'REJECT'\n  WHEN @@user_overdue_cnt_90d >= 3 THEN 'REVIEW'\n  ELSE 'PASS'\nEND"
  });
  const [base, setBase] = useState({ viewId: null, viewCode: '', viewField: '' });
  /** 入参定义（可编辑）：解析结果打底，用户在步骤 2 的调整叠加在上面 */
  const [inputRows, setInputRows] = useState([]);
  /** 出参定义（可编辑）：衍生特征可多个；基础特征由绑定视图字段决定，不走这里 */
  const [outputRows, setOutputRows] = useState([]);
  /** 改名同步结果：{oldCode,newCode,changed,total,details:[{target,count}]} */
  const [renameInfo, setRenameInfo] = useState(null);
  const origCodeRef = useRef('');

  const meta = TYPE_META[featureType];

  useEffect(() => {
    // 基础特征的取数来源必须是「已发布上线」的视图，草稿 / 降级中的视图不可选，后端也会二次校验
    api.views.list({ status: 'PUBLISHED' }).then(v => setViews(Array.isArray(v) ? v : [])).catch(() => setViews([]));
    api.features.baseFeatures(80).then(b => setBaseFeatures(Array.isArray(b) ? b : [])).catch(() => setBaseFeatures([]));
  }, []);

  // 编辑模式：拉取特征详情回填表单；已发布特征进入只读态
  useEffect(() => {
    if (!isEdit) return;
    let alive = true;
    api.features.detail(routeFeatureId).then(d => {
      if (!alive || !d?.feature) return;
      const f = d.feature;
      setForm({
        code: f.code || '',
        displayName: f.name || '',
        domain: 'user',
        owner: f.owner || '',
        businessDesc: f.businessDesc || '',
        computeType: f.computeType || 'SQL',
        dataType: f.dataType || 'STRING',
        expr: f.expr || ''
      });
      setFeatureType(f.featureType === 'BASE' ? 'BASE' : 'DERIVED');
      setBase({ viewId: f.viewId || null, viewCode: f.viewCode || '', viewField: f.viewField || '' });
      origCodeRef.current = f.code || '';
      // 入参 / 出参定义回填（保存过就用保存的，历史数据为空时由后面的兜底逻辑补上）
      setInputRows((d.inputVars || []).map(v => ({
        varCode: v.varCode,
        varType: v.varType || 'STRING',
        source: v.source || 'FEATURE',
        required: v.required !== false,
        defaultValue: v.defaultValue || '',
        enabled: v.enabled !== false
      })));
      setOutputRows((d.outputVars || []).map(v => ({
        varCode: v.varCode,
        varType: v.varType || 'STRING',
        description: v.description || ''
      })));
      setReadOnly(f.status === 'PUBLISHED');
      setLoading(false);
    }).catch(e => { if (alive) { setErr(e.message); setLoading(false); } });
    return () => { alive = false; };
  }, [routeFeatureId]);

  // 选视图后拉详情（含出参清单与视图入参）
  useEffect(() => {
    if (featureType !== 'BASE' || !base.viewId) { setViewDetail(null); return; }
    let alive = true;
    api.views.detail(base.viewId).then(d => {
      if (!alive) return;
      setViewDetail(d);
      setBase(prev => {
        const fields = d?.fields || [];
        const keep = fields.some(f => f.fieldName === prev.viewField);
        return {
          ...prev,
          viewCode: d?.view?.code || '',
          // 出参由用户在步骤 2「出参定义」里显式选择，这里不做兜底默认，避免静默替用户决定输出值
          viewField: keep ? prev.viewField : ''
        };
      });
    }).catch(() => { if (alive) setViewDetail(null); });
    return () => { alive = false; };
  }, [featureType, base.viewId]);

  // 实时解析：基础特征 → 视图入参；衍生特征 → :param + @@ 引用带入的入参
  useEffect(() => {
    const isBase = featureType === 'BASE';
    const ready = isBase ? !!base.viewId : !!String(form.expr || '').trim();
    if (!ready) { setResolution({ inputs: [], warnings: [] }); setRefs([]); return; }
    const payload = {
      featureType,
      viewId: isBase ? base.viewId : null,
      viewCode: isBase ? base.viewCode : null,
      viewField: isBase ? base.viewField : null,
      expr: isBase ? null : form.expr
    };
    let alive = true;
    setResolving(true);
    const timer = setTimeout(() => {
      api.features.resolveInputs(payload)
        .then(r => {
          if (!alive) return;
          setResolution({ inputs: r?.inputs || [], warnings: r?.warnings || [] });
          setRefs(r?.refs || []);
        })
        .catch(e => { if (alive) setResolution({ inputs: [], warnings: [e.message] }); })
        .finally(() => { if (alive) setResolving(false); });
    }, 320);
    return () => { alive = false; clearTimeout(timer); };
  }, [featureType, base.viewId, base.viewCode, base.viewField, form.expr]);

  // 入参变化时自动补齐测试值（沿用视图入参默认值）
  useEffect(() => {
    setTestValues(prev => {
      const next = { ...prev };
      for (const r of inputRows || []) {
        if (r.enabled === false) continue;
        if (next[r.varCode] === undefined) next[r.varCode] = r.defaultValue || '';
      }
      return next;
    });
  }, [inputRows]);

  // 解析结果变化时并入「入参定义」（保留用户已改的类型 / 必填 / 默认值 / 移除标记）
  useEffect(() => {
    setInputRows(rows => mergeInputRows(rows, resolution.inputs));
  }, [resolution]);

  // 衍生特征的出参定义兜底：默认一个 result，用户可增删改
  useEffect(() => {
    if (featureType !== 'DERIVED') return;
    setOutputRows(rows => (rows.length ? rows : [{ varCode: 'result', varType: form.dataType || 'STRING', description: '表达式返回值' }]));
  }, [featureType]);

  const baseField = (viewDetail?.fields || []).find(f => f.fieldName === base.viewField);
  const isListView = viewDetail?.view?.readMode === 'LIST';
  const blockedRefs = refs.filter(r => !r.resolved);

  const switchType = (t) => {
    setFeatureType(t);
    setErr('');
    setFeatureId(null);
    setStep(1);
    setTrial(null);
    setPrecheck([]);
    setInputRows([]);
    setOutputRows([]);
  };

  const insertRef = (code) => {
    const token = `@@${code}`;
    const el = exprRef.current;
    if (!el) { setForm(f => ({ ...f, expr: `${f.expr}${token}` })); return; }
    const start = el.selectionStart ?? form.expr.length;
    const end = el.selectionEnd ?? start;
    const next = `${form.expr.slice(0, start)}${token}${form.expr.slice(end)}`;
    setForm(f => ({ ...f, expr: next }));
    requestAnimationFrame(() => {
      el.focus();
      el.setSelectionRange(start + token.length, start + token.length);
    });
  };

  // 步骤 1 → 2：只做命名/绑定校验与查重，**此时不落库**（出参要到步骤 2 才选，没有出参后端不会接受创建）
  const goStep2 = async () => {
    setErr('');
    // 编码可改：只有真的改了才做格式校验，历史数据里不合规的旧编码不阻塞其它编辑
    const codeChanged = !isEdit || form.code !== origCodeRef.current;
    if (codeChanged && !/^[a-z][a-z0-9_]*$/.test(form.code)) { setErr('特征编码只能是小写字母、数字与下划线，且以字母开头'); return; }
    if ((form.businessDesc || '').trim().length < 10) { setErr('业务口径说明至少 10 个字'); return; }
    if (featureType === 'BASE') {
      if (!base.viewId) { setErr(views.length === 0 ? '暂无已发布上线的视图，请先到「视图管理」把目标视图发布上线后再创建基础特征' : '请选择基础特征绑定的视图（仅可选已发布上线的视图）'); return; }
      if (!isEdit && !views.some(v => v.id === base.viewId)) { setErr('所选视图不是「已发布上线」状态，请重新选择'); return; }
      if (isListView) { setErr('该视图取数模式为 LIST（返回多行），不能直接建基础特征，请改建成聚合类衍生特征'); return; }
    } else {
      if (!String(form.expr || '').trim()) { setErr('请填写衍生特征表达式'); return; }
      if (blockedRefs.length) { setErr(`存在 ${blockedRefs.length} 个无法解析的 @@ 引用，请先修正`); return; }
    }
    // 编辑模式且编码未变：无需重复命名与查重校验，直接进入入参核对
    if (isEdit && !codeChanged) { setStep(2); return; }
    setBusy(true);
    try {
      const [vc, sim] = await Promise.all([
        api.features.validateName(form.code, form.domain),
        api.features.similar(form.code).catch(() => [])
      ]);
      setNameCheck(vc); setSimilars(sim || []);
      setStep(2);
    } catch (e) { setErr(e.message || '校验失败'); }
    finally { setBusy(false); }
  };

  // 步骤 2 → 3：出参已确定，此时才创建草稿并登记入参
  const goStep3 = async () => {
    setErr('');
    if (featureType === 'BASE') {
      if (!base.viewField) { setErr('请先在「出参定义」里选择一个视图出参作为特征输出值'); return; }
      if (!(viewDetail?.fields || []).some(f => f.fieldName === base.viewField)) {
        setErr('所选出参已不在该视图的出参清单里（视图可能已变更），请重新选择'); return;
      }
    } else {
      if (!String(form.expr || '').trim()) { setErr('请填写衍生特征表达式'); return; }
      if (blockedRefs.length) { setErr(`存在 ${blockedRefs.length} 个无法解析的 @@ 引用，请先修正`); return; }
      const badOut = outputRows.find(r => !/^[a-zA-Z_][a-zA-Z0-9_]*$/.test(r.varCode || ''));
      if (badOut) { setErr('出参名不合法：只能由字母、数字、下划线组成，且以字母开头'); return; }
      const badIn = inputRows.find(r => r.enabled !== false && r.source === 'MANUAL'
        && !/^[a-zA-Z_][a-zA-Z0-9_.]*$/.test(r.varCode || ''));
      if (badIn) { setErr('手工补充的入参需要填写合法变量名（字母开头，可含数字、下划线、点）'); return; }
    }
    setBusy(true);
    try {
      const payload = featureType === 'BASE'
        ? {
          code: form.code, name: form.displayName, featureType: 'BASE', computeType: 'REF',
          dataType: baseField?.fieldType || 'STRING',
          businessDesc: form.businessDesc, owner: form.owner,
          viewId: base.viewId, viewCode: base.viewCode, viewField: base.viewField
        }
        : {
          code: form.code, name: form.displayName, featureType: 'DERIVED', computeType: form.computeType,
          // 特征主类型跟随第 1 个出参，避免出参与特征类型不一致
          dataType: outputRows[0]?.varType || form.dataType,
          businessDesc: form.businessDesc, owner: form.owner,
          expr: form.expr
        };
      // 新建时初始化为草稿；编辑时省略状态字段，保持原状态（后端为 null 安全合并）
      if (!isEdit) { payload.status = 'DRAFT'; payload.verifyStatus = 'UNVERIFIED'; }
      // 首次进入 = 创建草稿；从步骤 1 回退改过绑定再回来 = 就地更新，避免留下重复草稿
      let id = featureId;
      if (id) {
        // 改了编码：走改名接口，它会顺带把下游 @@ 引用与策略配置里的引用一起改掉
        if (isEdit && form.code !== origCodeRef.current) {
          const r = await api.features.rename(id, form.code);
          origCodeRef.current = form.code;
          setRenameInfo(r);
        }
        await api.features.update(id, payload);
      } else {
        const created = await api.features.create(payload);
        id = created?.id ?? null;
        setFeatureId(id);
      }
      if (id) {
        await api.features.syncInputs(id).catch(() => {});
        // 把用户在步骤 2 编辑后的入参 / 出参定义整份保存，覆盖自动解析登记的结果
        if (inputRows.length) {
          await api.features.saveInputs(id, inputRows.map(r => ({
            varCode: r.varCode,
            varType: r.varType,
            source: r.source,
            required: r.required !== false,
            defaultValue: r.defaultValue || null,
            enabled: r.enabled !== false
          })));
        }
        if (featureType === 'DERIVED' && outputRows.length) {
          await api.features.saveOutputs(id, outputRows.map((r, i) => ({
            varCode: r.varCode,
            varType: r.varType,
            description: r.description || null,
            sortNo: i
          })));
        }
      }
      setStep(3);
    } catch (e) { setErr(e.message || (isEdit ? '保存失败' : '创建失败')); }
    finally { setBusy(false); }
  };

  const runTrial = async () => {
    if (!featureId) { setErr('特征尚未创建，无法验证'); return; }
    setBusy(true); setErr(''); setTrial(null);
    try {
      const res = await api.features.trial(featureId, testValues);
      setTrial(res);
      await api.features.update(featureId, { verifyStatus: res?.success ? 'VERIFIED' : 'ERROR' }).catch(() => {});
    } catch (e) { setErr(e.message || '验证失败'); }
    finally { setBusy(false); }
  };

  const goStep4 = async () => {
    if (!featureId) { setErr('特征尚未创建'); return; }
    setBusy(true); setErr('');
    try { setPrecheck(await api.features.precheck(featureId)); setStep(4); }
    catch (e) { setErr(e.message || '获取检查项失败'); }
    finally { setBusy(false); }
  };

  const submit = async () => {
    if (!featureId) return;
    setBusy(true); setErr('');
    try {
      await api.features.submitApproval(featureId, `新建${meta.title}提交审批`);
      goBack();
    } catch (e) { setErr(e.message || '提交失败'); setBusy(false); }
  };

  if (loading) return <div className="empty">特征加载中…</div>;

  return (
    <div>
      <div className="page-header" style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <button className="btn sm" onClick={goBack}>← 返回</button>
        <div>
          <h1>{isEdit ? (readOnly ? '查看特征' : '编辑特征') : `新建${meta.title}`}</h1>
          <div className="sub">
            配置态 / 特征 · {form.code} · {isEdit
              ? (readOnly ? '已发布上线 · 只读' : `编辑 ${meta.title} · 特征 ID ${featureId}`)
              : (featureId ? `特征 ID ${featureId}` : '草稿未保存')} · {meta.tag}
          </div>
        </div>
        {busy && <span style={{ marginLeft: 'auto', fontSize: 13.5, color: 'var(--ink-2)' }}>处理中…</span>}
      </div>

      {readOnly && (
        <div className="warn-bar" style={{ marginBottom: 16, justifyContent: 'space-between', alignItems: 'center' }}>
          <span>
            <b style={{ color: 'var(--review)' }}>已发布上线 · 只读</b>
            　特征 <span className="mono">{form.code}</span> 已发布，线上口径已定版，因此不可编辑。
            如需修改，请先在特征列表点「下线」将其下线（逻辑下线，列表将不再展示该特征，可由后端恢复为草稿后再编辑）。
          </span>
          <button className="btn sm" onClick={goBack}>去特征列表</button>
        </div>
      )}

      {err && <div className="warn-bar" style={{ marginBottom: 12, justifyContent: 'flex-start' }}><b style={{ color: 'var(--reject)' }}>失败</b> {err}</div>}
      {renameInfo?.changed && (
        <div
          className="warn-bar"
          style={{ marginBottom: 12, justifyContent: 'flex-start', alignItems: 'center', flexWrap: 'wrap',
            background: 'rgba(31,107,58,.07)', borderColor: 'rgba(31,107,58,.28)' }}
        >
          <b style={{ color: 'var(--pass)' }}>编码已更新</b>
          <span style={{ fontSize: 13.5 }}>
            <span className="mono">{renameInfo.oldCode}</span> → <span className="mono">{renameInfo.newCode}</span>
            {renameInfo.total > 0
              ? <>，同步更新了下游 <b>{renameInfo.total}</b> 处引用（{(renameInfo.details || []).map(d => `${d.target} ${d.count}`).join('、')}）</>
              : '，没有发现需要同步的下游引用'}
          </span>
          <button className="btn sm" style={{ marginLeft: 'auto' }} onClick={() => setRenameInfo(null)}>知道了</button>
        </div>
      )}

      {/* 已发布上线的特征为只读：用 fieldset 原生禁用内部全部表单控件与步骤按钮 */}
      <fieldset disabled={readOnly} style={{ border: 0, margin: 0, padding: 0, minInlineSize: 0, opacity: readOnly ? 0.92 : 1 }}>
      <div className="steps">
        {meta.steps.map((s, i) => (
          <React.Fragment key={s}>
            <div className={`step ${step > i + 1 ? 'done' : ''} ${step === i + 1 ? 'active' : ''}`}>
              <span className="num">{step > i + 1 ? '✓' : i + 1}</span>
              <span>{s}</span>
            </div>
            {i < meta.steps.length - 1 && <div className="step-line" />}
          </React.Fragment>
        ))}
      </div>

      {step === 1 && (
        <StepBasic
          form={form} setForm={setForm}
          featureType={featureType} switchType={switchType}
          views={views} base={base} setBase={setBase} viewDetail={viewDetail}
          baseFeatures={baseFeatures} refs={refs} blockedRefs={blockedRefs}
          resolution={resolution} resolving={resolving} exprRef={exprRef} insertRef={insertRef}
          onNext={goStep2} busy={busy} nameCheck={nameCheck} similars={similars} isEdit={isEdit}
        />
      )}
      {step === 2 && (
        <StepIO
          featureType={featureType} resolution={resolution} baseField={baseField}
          viewDetail={viewDetail} base={base} setBase={setBase} form={form} setForm={setForm}
          inputRows={inputRows} setInputRows={setInputRows}
          outputRows={outputRows} setOutputRows={setOutputRows}
          onPrev={() => setStep(1)} onNext={goStep3} busy={busy} isEdit={isEdit}
        />
      )}
      {step === 3 && (
        <StepVerify
          featureType={featureType} resolution={resolution} inputRows={inputRows} testValues={testValues}
          setTestValues={setTestValues} trial={trial} runTrial={runTrial}
          onPrev={() => setStep(2)} onNext={goStep4} busy={busy}
        />
      )}
      {step === 4 && (
        <StepRelease items={precheck} onPrev={() => setStep(3)} onDone={isEdit ? goBack : submit} busy={busy} form={form} meta={meta} isEdit={isEdit} />
      )}
      </fieldset>
    </div>
  );
}

/* ===== 步骤 1：基本信息 + 类型分支配置 ===== */
function StepBasic({
  form, setForm, featureType, switchType, views, base, setBase, viewDetail,
  baseFeatures, refs, blockedRefs, resolution, resolving, exprRef, insertRef,
  onNext, busy, nameCheck, similars, isEdit
}) {
  const isBase = featureType === 'BASE';
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 320px', gap: 16 }}>
      <div className="card">
        <div className="card-title"><span>特征类型</span><span className="sub">{isEdit ? '创建后不可变更' : '决定后续配置与验证方式'}</span></div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: isEdit ? 8 : 18 }}>
          {Object.values(TYPE_META).map(t => {
            const active = t.key === featureType;
            return (
              <button
                key={t.key}
                onClick={() => { if (!isEdit) switchType(t.key); }}
                style={{
                  textAlign: 'left', cursor: isEdit ? 'not-allowed' : 'pointer', padding: '12px 14px', borderRadius: 10,
                  border: `1px solid ${active ? 'var(--feature)' : 'var(--stroke-weak)'}`,
                  background: active ? 'rgba(232,116,15,.06)' : '#fff',
                  boxShadow: active ? '0 0 0 2px rgba(232,116,15,.12)' : 'none',
                  opacity: isEdit && !active ? 0.55 : 1
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <span style={{ fontSize: 14, fontWeight: 600, color: active ? 'var(--feature)' : '#241C15' }}>{t.title}</span>
                  <span style={{ fontSize: 12, padding: '1px 6px', borderRadius: 4, background: 'rgba(232,116,15,.10)', color: 'var(--feature)' }}>{t.tag}</span>
                  {active && <span style={{ marginLeft: 'auto', color: 'var(--feature)', fontSize: 13.5 }}>✓</span>}
                </div>
                <div style={{ fontSize: 12.5, color: 'var(--ink-2)', lineHeight: 1.6, marginTop: 6 }}>{t.desc}</div>
              </button>
            );
          })}
        </div>
        {isEdit && (
          <div style={{ fontSize: 12.5, color: 'var(--ink-3)', marginBottom: 14 }}>编辑模式：特征类型为创建时确定，不可切换；如需换类型请在列表删除后重新创建。</div>
        )}

        <div className="card-title" style={{ marginTop: 4 }}><span>基本信息</span><span className="sub">带 * 为必填</span></div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
          <div className="field">
            <label>特征编码 *</label>
            <input
              className="input mono"
              value={form.code}
              onChange={e => setForm({ ...form, code: e.target.value })}
            />
            <div style={{ fontSize: 12, color: isEdit ? 'var(--review)' : 'var(--ink-3)', marginTop: 4 }}>
              {isEdit
                ? '编码可修改：保存时会自动同步下游 @@ 引用与策略配置里的引用（历史决策日志保持原样）'
                : '唯一标识，小写字母开头，可含数字与下划线'}
            </div>
          </div>
          <div className="field">
            <label>显示名称 *</label>
            <input className="input" value={form.displayName} onChange={e => setForm({ ...form, displayName: e.target.value })} />
          </div>
          <div className="field">
            <label>归属域 *</label>
            <select className="select" value={form.domain} onChange={e => setForm({ ...form, domain: e.target.value })}>
              <option value="user">用户域 · user</option>
              <option value="device">设备域 · device</option>
              <option value="order">订单域 · order</option>
            </select>
          </div>
          <div className="field">
            <label>责任人</label>
            <input className="input" value={form.owner} onChange={e => setForm({ ...form, owner: e.target.value })} />
          </div>
        </div>
        <div className="field">
          <label>业务口径说明 *（≥10 字）</label>
          <textarea className="textarea" style={{ minHeight: 74 }} value={form.businessDesc} onChange={e => setForm({ ...form, businessDesc: e.target.value })} />
        </div>

        {isBase
          ? <BaseConfig views={views} base={base} setBase={setBase} viewDetail={viewDetail} />
          : (
            <DerivedConfig
              form={form} setForm={setForm} baseFeatures={baseFeatures} refs={refs}
              blockedRefs={blockedRefs} resolution={resolution} resolving={resolving}
              exprRef={exprRef} insertRef={insertRef}
            />
          )}

        <div className="info-bar" style={{ marginTop: 16 }}>
          <span>
            {isEdit
              ? <>点「下一步」进入入参 / 出参核对，可调整类型、必填、默认值与出参清单；保存后按{isBase ? '绑定视图' : '@@ 引用'}重新解析入参，不改变特征状态</>
              : <>下一步将调用 /features/validate-name 与 /features/similar 做命名校验与查重，
                创建草稿后按{isBase ? '绑定视图' : '@@ 引用'}自动解析入参并登记</>}
          </span>
        </div>
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <div className="card">
          <div className="card-title">
            <span>命名规范校验</span>
            <span className="sub">{nameCheck ? `${nameCheck.checks?.filter(c => c.pass).length || 0} / ${nameCheck.checks?.length || 0} 通过` : isEdit ? '编码未变更' : '提交后校验'}</span>
          </div>
          {isEdit && !nameCheck ? (
            <div className="empty">编码未变更时不重复做命名与查重校验；改成新编码后点「下一步」会重新校验唯一性。</div>
          ) : (nameCheck?.checks || [
            { item: '域.实体.指标_命名模式', pass: true },
            { item: '全小写 + 下划线分隔', pass: true },
            { item: '窗口类指标建议带时间后缀', pass: false, suggest: true, suggestText: '建议' },
            { item: '无保留字冲突', pass: true },
            { item: '同域内名称唯一', pass: true }
          ]).map(item => (
            <div key={item.item} style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '6px 0', fontSize: 12.5 }}>
              <span style={{ color: item.pass ? 'var(--pass)' : item.suggest ? 'var(--review)' : 'var(--reject)' }}>
                {item.pass ? '●' : item.suggest ? '◐' : '○'}
              </span>
              <span style={{ color: item.pass ? '#5C4A38' : item.suggest ? '#8B7340' : 'var(--ink-2)' }}>{item.item}</span>
              {item.suggest && <span style={{ color: 'var(--review)', marginLeft: 'auto' }}>{item.suggestText || '建议'}</span>}
            </div>
          ))}
        </div>

        <div className="card">
          <div className="card-title"><span>相似特征提醒</span><span className="sub">{similars.length ? `查重 ${similars.length} 条` : '—'}</span></div>
          {similars.length ? similars.map(s => (
            <div key={s.name} className="row" style={{ padding: '8px 0' }}>
              <span className="mono" style={{ fontSize: 12.5 }}>{s.name}</span>
              <span style={{ fontSize: 12.5, color: 'var(--review)' }}>相似 {s.similarity}</span>
            </div>
          )) : <div className="empty">暂无相似特征</div>}
        </div>

        <div className="card">
          <div className="card-title"><span>{isEdit ? '下一步 · 核对入参与出参' : '下一步 · 登记入参与出参'}</span></div>
          <div style={{ fontSize: 12.5, color: 'var(--ink-2)', lineHeight: 1.7, marginBottom: 12 }}>
            {isEdit
              ? '下一步可逐个调整入参的类型 / 必填 / 默认值，以及衍生特征的出参清单；点保存原地更新该特征（保持当前状态）。'
              : isBase
                ? '基础特征不加工：下一步里从该视图的出参清单中选 1 个作为特征输出值，入参即该视图登记的入参；确认后才创建草稿。'
                : '下一步确认表达式与 @@ 引用；确认后才创建草稿，并把被引用基础特征的入参合并去重后登记。'}
          </div>
          <button className="btn primary" style={{ width: '100%' }} disabled={busy} onClick={onNext}>
            {busy ? '校验中…' : isEdit ? '下一步：核对入参与出参' : '下一步：登记入参与出参'}
          </button>
        </div>
      </div>
    </div>
  );
}

/* ===== 基础特征：视图 + 出参 选择 ===== */
function BaseConfig({ views, base, setBase, viewDetail }) {
  const isListView = viewDetail?.view?.readMode === 'LIST';
  return (
    <div style={{ marginTop: 18 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
        <b style={{ fontSize: 14 }}>基础特征绑定</b>
        <span style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>1 个视图 → 1 个出参 → 1 个特征，严格禁止加工</span>
      </div>
      {views.length === 0 ? (
        <div className="warn-bar" style={{ justifyContent: 'flex-start', marginBottom: 12 }}>
          <b style={{ color: 'var(--review)' }}>暂无已发布上线的视图</b>
          基础特征只能绑定发布上线的视图。请先到「视图管理」把目标视图发布上线，再回到这里创建。
        </div>
      ) : (
        <div className="info-bar" style={{ marginBottom: 12, fontSize: 12.5 }}>
          只展示<b>已发布上线</b>的视图（共 {views.length} 个）。已发布视图的口径已定版，不可再编辑；如需修改请先下线。
        </div>
      )}
      <div className="field">
        <label>选择视图 * <span style={{ fontWeight: 400, color: 'var(--ink-3)' }}>（仅可选已发布上线）</span></label>
        <select className="select" value={base.viewId || ''} disabled={views.length === 0}
          onChange={e => setBase({ viewId: e.target.value ? Number(e.target.value) : null, viewCode: '', viewField: '' })}>
          <option value="">{views.length === 0 ? '— 暂无已发布上线的视图 —' : '— 请选择视图 —'}</option>
          {views.map(v => (
            <option key={v.id} value={v.id}>
              {v.code} · {v.name}{v.readMode === 'LIST' ? '（LIST 多行）' : ''}
            </option>
          ))}
        </select>
        <div style={{ fontSize: 12.5, color: 'var(--ink-2)', marginTop: 6, lineHeight: 1.7 }}>
          出参不在这一步选 —— 选定视图后到下一步「入参与出参」，从该视图的出参清单里挑 1 个作为特征输出值。
        </div>
      </div>

      {viewDetail && (
        <>
          {isListView && (
            <div className="warn-bar" style={{ marginTop: 4, justifyContent: 'flex-start' }}>
              <b style={{ color: 'var(--review)' }}>注意</b>
              该视图取数模式为 LIST（返回多行），1:1 引用会拿不到确定值，请改用聚合类衍生特征。
            </div>
          )}
          <div style={{ display: 'flex', gap: 16, marginTop: 12, fontSize: 12.5, color: 'var(--ink-2)', flexWrap: 'wrap' }}>
            <span>数据源 <b className="mono" style={{ color: '#5C4A38' }}>{viewDetail.view?.sourceType}</b></span>
            <span>取数模式 <b className="mono" style={{ color: '#5C4A38' }}>{viewDetail.view?.readMode}</b></span>
            <span>出参 <b className="mono" style={{ color: '#5C4A38' }}>{(viewDetail.fields || []).length}</b></span>
            <span>视图入参 <b className="mono" style={{ color: '#5C4A38' }}>{(viewDetail.inputs || []).length}</b></span>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14, marginTop: 14 }}>
            <div style={{ border: '1px dashed var(--stroke-weak)', borderRadius: 8, padding: '10px 12px', background: 'rgba(217,132,32,.04)' }}>
              <div style={{ fontSize: 12.5, fontWeight: 600, color: '#5C4F41', marginBottom: 8 }}>特征输出值</div>
              <div style={{ fontSize: 12.5, color: 'var(--ink-2)', lineHeight: 1.7 }}>
                {base.viewField
                  ? <>已选 <span className="mono" style={{ color: '#241C15' }}>{base.viewField}</span></>
                  : <>待下一步选择 · 该视图共 <b>{(viewDetail.fields || []).length}</b> 个候选出参</>}
              </div>
            </div>
            <div style={{ border: '1px solid var(--stroke-weak)', borderRadius: 8, padding: '10px 12px' }}>
              <div style={{ fontSize: 12.5, fontWeight: 600, color: '#5C4F41', marginBottom: 8 }}>验证用入参（取自视图）</div>
              {(viewDetail.inputs || []).length ? (viewDetail.inputs || []).map(i => (
                <div key={i.varCode} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12.5, padding: '3px 0' }}>
                  <span className="mono" style={{ color: '#241C15' }}>{i.varCode}</span>
                  <span className="mono" style={{ color: 'var(--ink-2)' }}>{i.varType || 'STRING'}</span>
                  {i.required ? <span style={{ color: 'var(--reject)', fontSize: 12 }}>必填</span> : <span style={{ color: '#B6A997', fontSize: 12 }}>选填</span>}
                </div>
              )) : <div style={{ fontSize: 12.5, color: '#B6A997' }}>该视图未登记入参，验证时无需填值</div>}
            </div>
          </div>
        </>
      )}
    </div>
  );
}

/* ===== 加工表达式 · 帮助角标（悬停查看分语言示例） ===== */
const EXPR_EXAMPLES = [
  {
    lang: 'SQL',
    desc: '单行单列返回，别名必须是 result',
    code: "SELECT CASE\n    WHEN @@user_blacklist_hit >= 1 THEN 'REJECT'\n    WHEN @@user_overdue_cnt_90d >= 3 THEN 'REVIEW'\n    ELSE 'PASS' END AS result\nFROM t_input"
  },
  {
    lang: 'Java',
    desc: '固定方法签名，只需写方法体；入参从 in 读取',
    code: 'public static Object apply(Map<String, Object> in, Ctx ctx) {\n    if (@@user_blacklist_hit >= 1) return "REJECT";\n    if (@@user_overdue_cnt_90d >= 3) return "REVIEW";\n    return "PASS";\n}'
  },
  {
    lang: 'Python',
    desc: '固定函数签名；入参从 inp 读取',
    code: 'def apply(inp: dict, ctx) -> object:\n    if @@user_blacklist_hit >= 1: return "REJECT"\n    if @@user_overdue_cnt_90d >= 3: return "REVIEW"\n    return "PASS"'
  },
  {
    lang: 'OP',
    desc: '可视化算子编辑器：选特征/入参 → 选比较符 → 填阈值与输出，自动生成算子树',
    code: "条件映射：@@user_blacklist_hit ≥ 1 → 'REJECT'\n条件映射：@@user_overdue_cnt_90d ≥ 3 → 'REVIEW'\n兜底输出：→ 'PASS'"
  }
];

function ExprHelp({ current }) {
  const [open, setOpen] = useState(false);
  const [place, setPlace] = useState({ dir: 'down', maxH: 560 });
  const wrapRef = useRef(null);
  // 悬浮卡较高：打开前量一次上下可用空间，朝空间大的一侧弹出并限制高度，避免超出视口
  const show = () => {
    const el = wrapRef.current;
    let dir = 'down';
    let maxH = 680;
    if (el) {
      const r = el.getBoundingClientRect();
      const below = window.innerHeight - r.bottom - 24;
      const above = r.top - 24;
      dir = below >= above ? 'down' : 'up';
      maxH = Math.max(300, Math.min(dir === 'down' ? below : above, 680));
    }
    setPlace({ dir, maxH });
    setOpen(true);
  };
  return (
    <span
      ref={wrapRef}
      style={{ position: 'relative', display: 'inline-flex', alignItems: 'center', marginLeft: 6 }}
      onMouseEnter={show}
      onMouseLeave={() => setOpen(false)}
    >
      <span
        role="img"
        aria-label="查看各语言写法示例"
        style={{
          width: 18, height: 18, borderRadius: '50%', flexShrink: 0,
          display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
          cursor: 'help', userSelect: 'none', fontSize: 11.5, fontWeight: 700,
          color: 'var(--decision)', background: 'rgba(194,65,12,.08)', border: '1px solid rgba(194,65,12,.35)'
        }}
      >?</span>
      {open && (
        <div
          style={{
            position: 'absolute', right: -4, zIndex: 90,
            ...(place.dir === 'down' ? { top: '100%', paddingTop: 8 } : { bottom: '100%', paddingBottom: 8 })
          }}
        >
          <div style={{
            width: 560, maxHeight: place.maxH, overflowY: 'auto',
            background: '#fff', border: '1px solid var(--stroke-weak)', borderRadius: 10,
            boxShadow: '0 10px 32px rgba(60,40,20,.18)', padding: '12px 14px'
          }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 12 }}>
              <b style={{ fontSize: 13.5 }}>衍生特征写法示例</b>
              <span style={{ fontSize: 12, color: 'var(--ink-2)' }}>
                <b className="mono">@@编码</b> 引用基础特征 · <b className="mono">:param.x</b> 引用决策入参
              </span>
            </div>
            <div style={{ fontSize: 12, color: 'var(--ink-3)', marginTop: 3 }}>
              验证时 @@ 会替换为实测取值；示例编码可换成本页「可引用的基础特征」里的任意编码。
            </div>
            {EXPR_EXAMPLES.map(ex => (
              <div key={ex.lang} style={{ marginTop: 7 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
                  <span
                    className="tag"
                    style={{
                      fontSize: 12, padding: '1px 8px',
                      ...(current === ex.lang
                        ? { color: 'var(--decision)', borderColor: 'rgba(194,65,12,.35)', background: 'rgba(194,65,12,.10)' }
                        : { color: 'var(--ink-2)' })
                    }}
                  >{ex.lang}</span>
                  <span style={{ fontSize: 12, color: 'var(--ink-2)' }}>{ex.desc}</span>
                </div>
                <pre className="code-block" style={{ margin: 0, padding: '6px 10px', fontSize: 12, lineHeight: 1.5 }}>{ex.code}</pre>
              </div>
            ))}
          </div>
        </div>
      )}
    </span>
  );
}

/* ===== 衍生特征：表达式 + @@ 引用解析（OP 走可视化算子编辑器） ===== */

const OP_OPS = [['EQ', '='], ['NE', '≠'], ['GT', '>'], ['GTE', '≥'], ['LT', '<'], ['LTE', '≤'], ['IN', '属于'], ['IS_NULL', '为空'], ['NOT_NULL', '非空']];
const NO_RIGHT_OPS = ['IS_NULL', 'NOT_NULL'];

function constToText(v) {
  if (Array.isArray(v)) return v.map(x => (x == null ? '' : String(x))).join(', ');
  return v == null ? '' : String(v);
}

function coerceConst(s) {
  const t = String(s).trim();
  if (/^true$/i.test(t)) return true;
  if (/^false$/i.test(t)) return false;
  if (/^-?\d+(\.\d+)?$/.test(t)) return Number(t);
  return String(s);
}

function ruleComplete(r) {
  const leftOk = r.leftKind === 'const' ? String(r.leftConst).trim() !== '' : String(r.leftRef || '').trim() !== '';
  if (!leftOk) return false;
  if (String(r.then ?? '').trim() === '') return false;
  if (NO_RIGHT_OPS.includes(r.op)) return true;
  return String(r.rightConst ?? '').trim() !== '';
}

/** 把可视化规则拼成 OpTreeEngine 的 CASE 算子树（仅收录填写完整的规则） */
function buildOpTree(rules, elseValue) {
  const complete = rules.filter(ruleComplete);
  if (!complete.length) return '';
  const side = (kind, ref, value) => (kind === 'ref' ? { type: 'REF', ref } : { type: 'CONST', value: coerceConst(value) });
  const branches = complete.map(r => {
    const when = { type: 'CMP', op: r.op, left: side(r.leftKind, r.leftRef, r.leftConst) };
    if (!NO_RIGHT_OPS.includes(r.op)) {
      when.right = {
        type: 'CONST',
        value: r.op === 'IN'
          ? String(r.rightConst).split(',').map(x => x.trim()).filter(Boolean).map(coerceConst)
          : coerceConst(r.rightConst)
      };
    }
    return { when, then: { type: 'CONST', value: coerceConst(r.then) } };
  });
  const tree = { type: 'CASE', branches };
  if (String(elseValue).trim() !== '') tree.else = { type: 'CONST', value: coerceConst(elseValue) };
  return JSON.stringify(tree, null, 2);
}

/** 解析已有算子树为可视化规则；遇到嵌套算子等复杂结构返回 null（落高级模式） */
function parseOpTree(expr) {
  let t;
  try { t = JSON.parse(expr); } catch { return null; }
  if (!t || t.type !== 'CASE' || !Array.isArray(t.branches)) return null;
  const side = n => {
    if (!n || typeof n !== 'object') return null;
    if (n.type === 'REF' && typeof n.ref === 'string' && n.ref) return { kind: 'ref', ref: n.ref };
    if (n.type === 'CONST') return { kind: 'const', value: n.value };
    return null;
  };
  const rules = [];
  for (const b of t.branches) {
    const w = b && b.when;
    if (!w || w.type !== 'CMP') return null;
    const left = side(w.left);
    const op = String(w.op || '').toUpperCase();
    if (!left || !OP_OPS.some(o => o[0] === op)) return null;
    if (!b.then || b.then.type !== 'CONST') return null;
    let rightConst = '';
    if (!NO_RIGHT_OPS.includes(op)) {
      const r = side(w.right);
      if (!r || r.kind !== 'const') return null;
      rightConst = constToText(r.value);
    }
    rules.push({
      leftKind: left.kind,
      leftRef: left.kind === 'ref' ? left.ref : '',
      leftConst: left.kind === 'const' ? constToText(left.value) : '',
      op,
      rightConst,
      then: constToText(b.then.value)
    });
  }
  const els = t.else ? side(t.else) : null;
  return { rules, elseValue: els && els.kind === 'const' ? constToText(els.value) : '' };
}

function OpRuleEditor({ rules, elseValue, refOptions, onChange }) {
  const upd = (i, patch) => onChange(rules.map((r, j) => (j === i ? { ...r, ...patch } : r)), elseValue);
  const incomplete = rules.filter(r => !ruleComplete(r)).length;
  const selStyle = { height: 28, fontSize: 12.5, padding: '0 6px' };
  const inpStyle = { height: 28, fontSize: 12.5, padding: '0 8px' };
  return (
    <div style={{ border: '1px solid var(--stroke-weak)', borderRadius: 10, padding: '12px 14px', background: '#FFFDF9' }}>
      {rules.map((r, i) => (
        <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '6px 0', borderBottom: i < rules.length - 1 ? '1px solid var(--stroke-weak)' : 'none' }}>
          <span style={{ fontSize: 12.5, color: 'var(--ink-2)', width: 26, flexShrink: 0 }}>{i === 0 ? '当' : '再'}</span>
          <select
            className="select" style={{ ...selStyle, width: 190, flexShrink: 0 }}
            value={r.leftKind === 'const' ? '@CONST' : r.leftRef}
            onChange={e => upd(i, e.target.value === '@CONST' ? { leftKind: 'const', leftRef: '' } : { leftKind: 'ref', leftRef: e.target.value })}
          >
            <option value="">— 选择特征 / 入参 —</option>
            {refOptions.features.length > 0 && (
              <optgroup label="基础特征">
                {refOptions.features.map(f => <option key={f.code} value={`@@${f.code}`}>@@{f.code} · {f.name}</option>)}
              </optgroup>
            )}
            {refOptions.inputs.length > 0 && (
              <optgroup label="验证入参">
                {refOptions.inputs.map(x => <option key={x.value} value={x.value}>{x.label}</option>)}
              </optgroup>
            )}
            <optgroup label="其他">
              <option value="@CONST">常量值…</option>
            </optgroup>
          </select>
          {r.leftKind === 'const' && (
            <input
              className="input mono" style={{ ...inpStyle, width: 90, flexShrink: 0 }} placeholder="常量"
              value={r.leftConst} onChange={e => upd(i, { leftConst: e.target.value })}
            />
          )}
          <select className="select" style={{ ...selStyle, width: 78, flexShrink: 0 }} value={r.op} onChange={e => upd(i, { op: e.target.value })}>
            {OP_OPS.map(([v, label]) => <option key={v} value={v}>{label}</option>)}
          </select>
          {!NO_RIGHT_OPS.includes(r.op) && (
            <input
              className="input mono" style={{ ...inpStyle, flex: 1, minWidth: 80 }} placeholder={r.op === 'IN' ? '多个值用英文逗号分隔' : '比较值'}
              value={r.rightConst} onChange={e => upd(i, { rightConst: e.target.value })}
            />
          )}
          <span style={{ fontSize: 12.5, color: 'var(--ink-2)', flexShrink: 0 }}>→ 输出</span>
          <input
            className="input mono" style={{ ...inpStyle, width: 120, flexShrink: 0 }} placeholder="输出值"
            value={r.then} onChange={e => upd(i, { then: e.target.value })}
          />
          <button className="btn sm" style={{ padding: '1px 7px', flexShrink: 0 }} title="删除该规则" onClick={() => onChange(rules.filter((_, j) => j !== i), elseValue)}>×</button>
        </div>
      ))}
      {!rules.length && (
        <div className="empty" style={{ padding: '10px 0' }}>还没有规则，点下方「添加规则」开始配置条件映射</div>
      )}
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 10 }}>
        <button className="btn sm" onClick={() => onChange([...rules, { leftKind: 'ref', leftRef: '', leftConst: '', op: 'EQ', rightConst: '', then: '' }], elseValue)}>+ 添加规则</button>
        <span style={{ marginLeft: 'auto', fontSize: 12.5, color: 'var(--ink-2)', display: 'inline-flex', alignItems: 'center', gap: 6 }}>
          兜底输出（都不满足时）
          <input
            className="input mono" style={{ ...inpStyle, width: 150 }} placeholder="如 PASS"
            value={elseValue} onChange={e => onChange(rules, e.target.value)}
          />
        </span>
      </div>
      {incomplete > 0 && (
        <div style={{ fontSize: 12, color: 'var(--review)', marginTop: 8 }}>
          有 {incomplete} 条规则未填完整，暂不会写入算子树（补齐后自动生效）；至少需要 1 条完整规则才能保存。
        </div>
      )}
    </div>
  );
}

function DerivedConfig({ form, setForm, baseFeatures, refs, blockedRefs, resolution, resolving, exprRef, insertRef }) {
  const isOp = form.computeType === 'OP';
  const [adv, setAdv] = useState(false);
  const [advMsg, setAdvMsg] = useState('');
  const [rules, setRules] = useState([]);
  const [elseValue, setElseValue] = useState('');
  const draftsRef = useRef({});
  const lastWrittenRef = useRef(null);

  // 外部 expr 变化（编辑回填 / 切语言换稿）时重新解析为可视化规则
  useEffect(() => {
    if (form.computeType !== 'OP') return;
    if (form.expr === lastWrittenRef.current) return;
    const expr = String(form.expr || '').trim();
    if (!expr) { setRules([]); setElseValue(''); return; }
    const parsed = parseOpTree(expr);
    if (parsed) { setRules(parsed.rules); setElseValue(parsed.elseValue); }
    else if (!adv) { setAdv(true); setAdvMsg('当前算子树包含可视化规则之外的算子结构，已自动切到高级 JSON 模式。'); }
  }, [form.computeType, form.expr, adv]);

  const switchLang = (t) => {
    if (t === form.computeType) return;
    draftsRef.current[form.computeType] = form.expr;
    const draft = draftsRef.current[t];
    // 该语言尚无草稿时默认填入对应写法示例，避免把另一种语言的文本原样带过去
    const demo = t === 'OP' ? '' : (EXPR_EXAMPLES.find(ex => ex.lang === t)?.code || '');
    const next = draft !== undefined ? draft : demo;
    lastWrittenRef.current = null;
    setAdvMsg('');
    setForm({ ...form, computeType: t, expr: next });
  };

  const applyRules = (nextRules, nextElse) => {
    setRules(nextRules);
    setElseValue(nextElse);
    const json = buildOpTree(nextRules, nextElse);
    lastWrittenRef.current = json;
    setForm(f => ({ ...f, expr: json }));
  };

  const backToVisual = () => {
    const expr = String(form.expr || '').trim();
    const parsed = expr ? parseOpTree(expr) : { rules: [], elseValue: '' };
    if (parsed) { setAdv(false); setAdvMsg(''); }
    else setAdvMsg('当前 JSON 无法转换为可视化规则（含嵌套算子等结构），请继续用高级 JSON 编辑，或清空后重新配置。');
  };

  const refOptions = {
    features: baseFeatures,
    inputs: (resolution.inputs || []).map(i => i.source === 'PARAM'
      ? { value: `:param.${i.name}`, label: `:param.${i.name}（决策入参）` }
      : { value: i.name, label: `${i.name}（${i.source === 'FEATURE' ? '引用特征入参' : '视图入参'}）` })
  };

  return (
    <div style={{ marginTop: 18 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
        <b style={{ fontSize: 14 }}>加工表达式</b>
        <div className="seg" style={{ marginLeft: 8 }}>
          {['SQL', 'Java', 'Python', 'OP'].map(t => (
            <button key={t} className={t === form.computeType ? 'active' : ''} onClick={() => switchLang(t)}>{t}</button>
          ))}
        </div>
        <span style={{ marginLeft: 'auto', display: 'inline-flex', alignItems: 'center' }}>
          <span style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>用 <b className="mono">@@编码</b> 引用基础特征 · <b className="mono">:param.x</b> 引用决策入参</span>
          <ExprHelp current={form.computeType} />
        </span>
      </div>

      {isOp && !adv && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
          <span style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>
            可视化算子编辑器：按顺序命中第 1 条规则即输出对应值；每行「特征/入参 比较 值 → 输出」对应算子树一个 CASE 分支。
          </span>
          <button className="btn sm" style={{ marginLeft: 'auto', flexShrink: 0 }} onClick={() => setAdv(true)}>高级 JSON</button>
        </div>
      )}

      {isOp && !adv ? (
        <>
          {advMsg && <div className="warn-bar" style={{ marginBottom: 8, justifyContent: 'flex-start' }}>{advMsg}</div>}
          <OpRuleEditor rules={rules} elseValue={elseValue} refOptions={refOptions} onChange={applyRules} />
          {form.expr ? (
            <>
              <div style={{ fontSize: 12, color: 'var(--ink-2)', marginTop: 10, marginBottom: 4 }}>已生成的算子树 JSON（随规则自动更新，保存的是这份 JSON）：</div>
              <pre className="code-block" style={{ margin: 0, maxHeight: 150, overflow: 'auto', fontSize: 12 }}>{form.expr}</pre>
            </>
          ) : (
            <div className="empty" style={{ marginTop: 10 }}>补齐第 1 条规则后这里会显示自动生成的算子树 JSON</div>
          )}
        </>
      ) : (
        <>
          {isOp && advMsg && <div className="warn-bar" style={{ marginBottom: 8, justifyContent: 'flex-start' }}>{advMsg}</div>}
          {isOp && (
            <div style={{ display: 'flex', alignItems: 'center', marginBottom: 8 }}>
              <span style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>
                高级模式：直接编辑算子树 JSON（支持 CONST/REF/CMP/LOGIC/ARITH/CASE/DICT/FILLNULL 全部算子）。
              </span>
              <button className="btn sm" style={{ marginLeft: 'auto', flexShrink: 0 }} onClick={backToVisual}>返回可视化（简单条件映射可转回）</button>
            </div>
          )}
          <textarea
            ref={exprRef}
            className="textarea mono"
            style={{ minHeight: 140, fontSize: 13.5 }}
            value={form.expr}
            onChange={e => { if (isOp) lastWrittenRef.current = null; setForm(f => ({ ...f, expr: e.target.value })); }}
          />
        </>
      )}

      {(!isOp || adv) && (
        <div style={{ marginTop: 10 }}>
          <div style={{ fontSize: 12.5, fontWeight: 600, color: '#5C4F41', marginBottom: 6 }}>
            可引用的基础特征（点击插入 <span className="mono">@@</span>）
          </div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
            {baseFeatures.length ? baseFeatures.map(f => (
              <button
                key={f.code}
                className="btn sm"
                onClick={() => insertRef(f.code)}
                title={`${f.name} · 视图 ${f.viewCode || '—'}.${f.viewField || '—'}`}
                style={{ fontSize: 12.5, padding: '2px 8px' }}
              >
                @@{f.code}
              </button>
            )) : <span style={{ fontSize: 12.5, color: '#B6A997' }}>平台内还没有基础特征，先创建一个再来引用</span>}
          </div>
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14, marginTop: 14 }}>
        <div style={{ border: '1px solid var(--stroke-weak)', borderRadius: 8, padding: '10px 12px' }}>
          <div style={{ fontSize: 12.5, fontWeight: 600, color: '#5C4F41', marginBottom: 8, display: 'flex', alignItems: 'center' }}>
            <span>@@ 引用解析</span>
            {resolving && <span style={{ marginLeft: 8, color: '#B6A997', fontWeight: 400 }}>解析中…</span>}
            {!resolving && refs.length > 0 && (
              <span style={{ marginLeft: 8, fontWeight: 400, color: blockedRefs.length ? 'var(--reject)' : 'var(--pass)' }}>
                {refs.length - blockedRefs.length} / {refs.length} 可解析
              </span>
            )}
          </div>
          {refs.length ? refs.map(r => (
            <div key={r.raw} style={{ padding: '6px 0', borderBottom: '1px solid var(--stroke-weak)' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <span className="mono" style={{ fontSize: 12.5, color: r.resolved ? '#241C15' : 'var(--reject)' }}>{r.raw}</span>
                <span style={{ fontSize: 12, color: r.resolved ? 'var(--pass)' : 'var(--reject)' }}>{r.resolved ? '可解析' : '不可解析'}</span>
              </div>
              <div style={{ fontSize: 12, color: 'var(--ink-2)', marginTop: 3 }}>
                {r.resolved
                  ? <>基础特征 · 视图 <span className="mono">{r.viewCode}.{r.viewField}</span> · 带入入参 {(r.inputs || []).length} 个</>
                  : r.message}
              </div>
            </div>
          )) : <div className="empty">表达式中还没有 @@ 引用</div>}
        </div>

        <div style={{ border: '1px solid var(--stroke-weak)', borderRadius: 8, padding: '10px 12px' }}>
          <div style={{ fontSize: 12.5, fontWeight: 600, color: '#5C4F41', marginBottom: 8 }}>
            自动解析出的入参（验证时填写）
          </div>
          {(resolution.inputs || []).length ? (resolution.inputs || []).map(i => (
            <div key={i.name} style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12.5, padding: '4px 0' }}>
              <span className="mono" style={{ color: '#241C15' }}>{i.name}</span>
              <span className="mono" style={{ color: 'var(--ink-2)' }}>{i.type}</span>
              <SourceTag source={i.source} />
              {i.required && <span style={{ color: 'var(--reject)', fontSize: 12 }}>必填</span>}
            </div>
          )) : <div className="empty">还没有可解析的入参</div>}
        </div>
      </div>
    </div>
  );
}

/* ===== 步骤 2：入参登记 + 出参定义 ===== */
function StepIO({ featureType, resolution, baseField, viewDetail, base, setBase, form, setForm,
                  inputRows, setInputRows, outputRows, setOutputRows, onPrev, onNext, busy, isEdit }) {
  const isBase = featureType === 'BASE';
  const rows = inputRows || [];
  const activeCount = rows.filter(r => r.enabled !== false).length;
  const removedCount = rows.length - activeCount;
  const patchInput = (i, patch) => setInputRows(list => list.map((r, idx) => (idx === i ? { ...r, ...patch } : r)));
  const patchOutput = (i, patch) => setOutputRows(list => list.map((r, idx) => (idx === i ? { ...r, ...patch } : r)));
  return (
    <div>
      <div className="card" style={{ marginBottom: 16 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
          <b>入参定义 · InputRef</b>
          <span style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span className="badge badge-review">{activeCount} 个入参</span>
            {removedCount > 0 && <span className="badge badge-warn">{removedCount} 个已移除</span>}
          </span>
        </div>
        <div style={{ fontSize: 12.5, color: 'var(--ink-2)', marginBottom: 10, lineHeight: 1.7 }}>
          变量名与来源由表达式 / 视图绑定自动解析；<b>语义类型、是否必填、默认测试值</b>都可以按需调整。
          用不上的入参点「移除」——移除后验证时不再要求填写，随时可以恢复。
        </div>
        <table className="table">
          <thead>
            <tr>
              <th>变量引用</th><th style={{ width: 130 }}>语义类型</th>
              <th style={{ width: 130 }}>来源</th><th style={{ width: 190 }}>默认测试值</th>
              <th style={{ width: 76 }}>必填</th><th style={{ width: 88 }}>操作</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.varCode}-${i}`} style={r.enabled === false ? { opacity: 0.45 } : undefined}>
                <td className="mono" style={{ fontWeight: 500 }}>
                  {r.source === 'MANUAL' ? (
                    <input
                      className="input mono"
                      style={{ fontSize: 12.5, padding: '3px 6px', height: 30 }}
                      placeholder="如 param.channel"
                      value={r.varCode}
                      onChange={e => patchInput(i, { varCode: e.target.value })}
                    />
                  ) : (
                    <>
                      {r.varCode}
                      {r.sourceLabel
                        ? <div style={{ fontSize: 12, color: 'var(--ink-3)', fontWeight: 400 }}>{r.sourceLabel}</div>
                        : null}
                    </>
                  )}
                </td>
                <td>
                  <select
                    className="select"
                    style={{ height: 30, fontSize: 12.5 }}
                    value={r.varType}
                    onChange={e => patchInput(i, { varType: e.target.value })}
                  >
                    {DATA_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
                  </select>
                </td>
                <td><SourceTag source={r.source} /></td>
                <td>
                  <input
                    className="input"
                    style={{ fontSize: 12.5, padding: '3px 6px', height: 30 }}
                    placeholder="验证时预填"
                    value={r.defaultValue || ''}
                    onChange={e => patchInput(i, { defaultValue: e.target.value })}
                  />
                </td>
                <td>
                  <input
                    type="checkbox"
                    checked={r.required !== false}
                    title="必填：没填测试值就不允许验证"
                    onChange={e => patchInput(i, { required: e.target.checked })}
                  />
                </td>
                <td>
                  {r.enabled === false ? (
                    <button className="btn sm" onClick={() => patchInput(i, { enabled: true })}>恢复</button>
                  ) : (
                    <button className="btn sm" onClick={() => patchInput(i, { enabled: false })}>移除</button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {!rows.length && (
          <div className="empty">
            {isBase ? '该视图未登记入参' : '表达式中没有引用需要入参的视图字段或决策入参'}
          </div>
        )}
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 12, flexWrap: 'wrap' }}>
          <button
            className="btn sm"
            onClick={() => setInputRows(list => [...list, {
              varCode: '', varType: 'STRING', source: 'MANUAL',
              required: true, defaultValue: '', enabled: true
            }])}
          >
            ＋ 手工补充入参
          </button>
          <span style={{ fontSize: 12.5, color: 'var(--ink-3)' }}>
            用于登记表达式里引用到、但来源无法自动解析的变量（变量名需与表达式里写法一致）
          </span>
        </div>
      </div>

      <div className="card" style={{ marginBottom: 16 }}>
        <div className="card-title">
          <span>出参定义 · Output</span>
          <span className="sub">{isBase ? '1 个视图 → 1 个出参，继承视图字段类型' : '表达式输出'}</span>
        </div>
        {isBase ? (
          <>
            <div className="field" style={{ maxWidth: 520 }}>
              <label>
                选择出参作为特征输出值 *{' '}
                <span style={{ fontWeight: 400, color: 'var(--ink-3)' }}>
                  （取自视图 <span className="mono">{viewDetail?.view?.code || '—'}</span> 的出参清单）
                </span>
              </label>
              <select
                className="select"
                value={base?.viewField || ''}
                disabled={!(viewDetail?.fields || []).length}
                onChange={e => setBase({ ...base, viewField: e.target.value })}
              >
                <option value="">
                  {(viewDetail?.fields || []).length ? '— 请选择出参 —' : '该视图没有登记任何出参'}
                </option>
                {(viewDetail?.fields || []).map(f => (
                  <option key={f.fieldName} value={f.fieldName}>
                    {f.fieldName} · {f.fieldType}{f.description ? ` · ${f.description}` : ''}
                  </option>
                ))}
              </select>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 14, marginTop: 14 }}>
              <div><div style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>出参名</div><div className="mono" style={{ fontSize: 13.5 }}>{baseField?.fieldName || '—'}</div></div>
              <div><div style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>类型（自动继承，不可转换）</div><div className="mono" style={{ fontSize: 13.5 }}>{baseField?.fieldType || '—'}</div></div>
              <div><div style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>来源</div><div className="mono" style={{ fontSize: 13.5 }}>{viewDetail?.view?.code}.{baseField?.fieldName || '—'}</div></div>
            </div>

            {!base?.viewField && (
              <div className="warn-bar" style={{ marginTop: 12, justifyContent: 'flex-start' }}>
                <b style={{ color: 'var(--review)' }}>待选择</b>
                基础特征必须选定 1 个视图出参作为输出值；选定后点「下一步」才会创建草稿并进入验证。
              </div>
            )}
          </>
        ) : (
          <>
            <div style={{ fontSize: 12.5, color: 'var(--ink-2)', marginBottom: 12, lineHeight: 1.7 }}>
              可以定义<b>多个出参</b>。表达式返回单个值时会落到第 1 个出参；
              返回结构化结果（Python 返回 dict、SQL 返回多列、Java 返回 Map）时按出参名逐项取值。
              第 1 个出参的类型即特征的对外类型。
            </div>
            {(outputRows || []).map((r, i) => (
              <div key={i} style={{ display: 'grid', gridTemplateColumns: '1fr 140px 1fr 76px', gap: 10, alignItems: 'end', marginBottom: 10 }}>
                <div className="field" style={{ margin: 0 }}>
                  <label style={{ fontSize: 12.5 }}>出参名 *</label>
                  <input className="input mono" value={r.varCode} onChange={e => patchOutput(i, { varCode: e.target.value })} />
                </div>
                <div className="field" style={{ margin: 0 }}>
                  <label style={{ fontSize: 12.5 }}>类型</label>
                  <select className="select" value={r.varType} onChange={e => patchOutput(i, { varType: e.target.value })}>
                    {DATA_TYPES.map(t => <option key={t} value={t}>{t}</option>)}
                  </select>
                </div>
                <div className="field" style={{ margin: 0 }}>
                  <label style={{ fontSize: 12.5 }}>说明</label>
                  <input className="input" value={r.description || ''} onChange={e => patchOutput(i, { description: e.target.value })} />
                </div>
                <button
                  className="btn sm"
                  disabled={(outputRows || []).length <= 1}
                  title={(outputRows || []).length <= 1 ? '至少保留 1 个出参' : '删除该出参'}
                  onClick={() => setOutputRows(list => list.filter((_, idx) => idx !== i))}
                >
                  删除
                </button>
              </div>
            ))}
            <button
              className="btn sm"
              onClick={() => setOutputRows(list => [...list, {
                varCode: list.length === 0 ? 'result' : '', varType: 'STRING', description: ''
              }])}
            >
              ＋ 添加出参
            </button>
          </>
        )}
      </div>

      {(resolution.warnings || []).length > 0 && (
        <div className="warn-bar" style={{ marginBottom: 16, justifyContent: 'flex-start', flexDirection: 'column', alignItems: 'flex-start' }}>
          {(resolution.warnings || []).map((w, i) => <div key={i} style={{ fontSize: 13.5 }}>· {w}</div>)}
        </div>
      )}

      <div className="info-bar">
        <span>
          {isEdit ? '点「下一步」即保存修改' : '点「下一步」才创建草稿'}：入参由 POST /features/sync-inputs 按当前{isBase ? '视图绑定' : '@@ 引用'}
          {isEdit ? '重新解析并落库' : '自动解析并落库'}；
          下游决策节点与交叉决策树可直接引用该特征
        </span>
      </div>

      <div className="wizard-foot">
        <button className="btn" onClick={onPrev}>上一步</button>
        <button className="btn primary" disabled={busy} onClick={onNext}>
          {busy ? (isEdit ? '保存中…' : '创建中…') : '下一步：验证'}
        </button>
      </div>
    </div>
  );
}

/* ===== 步骤 3：验证（测试值取自解析出的入参） ===== */
function StepVerify({ featureType, resolution, inputRows, testValues, setTestValues, trial, runTrial, onPrev, onNext, busy }) {
  const isBase = featureType === 'BASE';
  // 验证用「入参定义」里用户调整后的清单（含必填 / 默认值 / 移除），没有编辑过的行时退回解析结果
  const inputs = (inputRows && inputRows.length)
    ? inputRows.filter(r => r.enabled !== false).map(r => ({
        name: r.varCode, type: r.varType, source: r.source,
        sourceLabel: r.sourceLabel, required: r.required !== false, defaultValue: r.defaultValue
      }))
    : (resolution.inputs || []);
  const missing = inputs.filter(i => i.required && !String(testValues[i.name] ?? '').trim());
  // 表达式回显默认折叠超长引用值（JSON 型出参会被整段内联，淹没表达式本身）
  const [rawExpr, setRawExpr] = useState(false);

  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(0, 1fr)', gap: 16 }}>
      <div className="card">
        <div className="card-title">
          <span>验证 · 测试入参</span>
          <span className="sub">{isBase ? '取自绑定视图的入参' : '@@ 引用自动解析出的入参'}</span>
        </div>
        {inputs.length ? (
          <table className="table">
            <thead><tr><th>入参</th><th style={{ width: 90 }}>类型</th><th style={{ width: 130 }}>来源</th><th>测试值</th></tr></thead>
            <tbody>
              {inputs.map(i => (
                <tr key={i.name}>
                  <td className="mono" style={{ fontSize: 13.5 }}>
                    {i.name}{i.required && <span style={{ color: 'var(--reject)', fontSize: 12, marginLeft: 4 }}>*</span>}
                  </td>
                  <td><span className="tag" style={{ fontSize: 12 }}>{i.type}</span></td>
                  <td><SourceTag source={i.source} /></td>
                  <td>
                    <input
                      className="input mono"
                      style={{ fontSize: 12.5, padding: '3px 6px', height: 26 }}
                      placeholder={i.defaultValue || '请输入测试值'}
                      value={testValues[i.name] ?? ''}
                      onChange={e => setTestValues(v => ({ ...v, [i.name]: e.target.value }))}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <div className="empty">没有需要填写的入参，可直接验证</div>
        )}
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginTop: 12 }}>
          <button className="btn primary" disabled={busy || missing.length > 0} onClick={runTrial}>
            {busy ? '验证中…' : isBase ? '执行视图取数验证 →' : '解析引用并执行 →'}
          </button>
          {missing.length > 0 && <span style={{ fontSize: 12.5, color: 'var(--review)' }}>还有 {missing.length} 个必填入参未填</span>}
        </div>
        <div style={{ fontSize: 12.5, color: 'var(--ink-2)', marginTop: 10, lineHeight: 1.7 }}>
          {isBase
            ? '将用上面这组入参真实执行绑定视图的取数脚本（MySQL / HTTP / CSV / PMML 走各自适配器），只核对特征选定的出参。'
            : '将先用这组测试值真实取每个 @@ 引用基础特征的实测值，再把 @@ 替换为实测值交给所选引擎真实执行（SQL→Calcite / Java→Janino / Python→独立 Runner / OP→算子求值），直接给出衍生输出。'}
        </div>
      </div>

      <div className="card">
        <div className="card-title">
          <span>验证结果</span>
          {trial?.mode && <span className="sub">{trial.mode === 'VIEW_EXEC' ? '视图真实执行' : trial.mode === 'EXPR_EXEC' ? '表达式真实执行' : '引用取值核对'}</span>}
        </div>
        {!trial && <div className="empty">点击左侧按钮执行验证</div>}
        {trial && (
          <>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
              <span className={`badge badge-${trial.success ? 'pass' : 'reject'}`}>{trial.success ? '✓ 验证通过' : '✗ 验证未通过'}</span>
              {trial.elapsedMs != null && <span className="mono" style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>{trial.elapsedMs} ms</span>}
              {trial.viewCode && <span className="mono" style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>{trial.viewCode}.{trial.viewField}</span>}
            </div>

            {trial.error && (
              <div style={{ background: 'rgba(190,58,43,.08)', border: '1px solid rgba(190,58,43,.25)', borderRadius: 8, padding: '10px 12px', fontSize: 13.5, color: 'var(--reject)', lineHeight: 1.7, whiteSpace: 'pre-wrap', marginBottom: 10 }}>
                {trial.error}
              </div>
            )}

            {trial.mode === 'EXPR_EXEC' && (
              (trial.outputResults || []).length > 0 ? (
                <div style={{ marginBottom: 10 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6, flexWrap: 'wrap' }}>
                    <span style={{ fontSize: 12.5, fontWeight: 600, color: '#5C4F41' }}>出参验证值</span>
                    {trial.engine && <span className="tag" style={{ fontSize: 12 }}>{ENGINE_LABELS[trial.engine] || trial.engine}</span>}
                    {trial.execElapsedMs != null && <span className="mono" style={{ fontSize: 12, color: 'var(--ink-2)' }}>引擎耗时 {trial.execElapsedMs} ms</span>}
                  </div>
                  <table className="table">
                    <thead><tr><th style={{ width: 200 }}>出参</th><th style={{ width: 120 }}>类型</th><th>值</th></tr></thead>
                    <tbody>
                      {(trial.outputResults || []).map(o => (
                        <tr key={o.name}>
                          <td className="mono" style={{ fontSize: 13.5 }}>{o.name}</td>
                          <td><span className="tag" style={{ fontSize: 12 }}>{o.type}</span></td>
                          <td className="mono" style={{ fontSize: 13.5, wordBreak: 'break-all' }}>
                            {o.missing
                              ? <span style={{ color: 'var(--review)' }}>未产出（结果里没有该字段）</span>
                              : <LongValueCell value={o.value} />}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  <div style={{ marginTop: 8, fontSize: 12.5, color: 'var(--ink-2)', lineHeight: 1.7 }}>
                    引擎原始返回值：
                    <span className="mono" style={{ marginLeft: 6, color: trial.derivedValue == null ? 'var(--ink-3)' : 'var(--pass)' }}>
                      <LongValueCell value={trial.derivedValue} />
                    </span>
                    {trial.derivedValueType && <span className="tag" style={{ fontSize: 12, marginLeft: 6 }}>{trial.derivedValueType}</span>}
                  </div>
                </div>
              ) : (
                <div style={{ marginBottom: 10, border: '1px solid var(--stroke-weak)', borderRadius: 8, padding: '10px 12px', background: 'rgba(31,107,58,.05)' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6, flexWrap: 'wrap' }}>
                    <span style={{ fontSize: 12.5, fontWeight: 600, color: '#5C4F41' }}>衍生输出值</span>
                    {trial.engine && <span className="tag" style={{ fontSize: 12 }}>{ENGINE_LABELS[trial.engine] || trial.engine}</span>}
                    {trial.derivedValueType && <span className="tag" style={{ fontSize: 12 }}>{trial.derivedValueType}</span>}
                    {trial.execElapsedMs != null && <span className="mono" style={{ fontSize: 12, color: 'var(--ink-2)' }}>引擎耗时 {trial.execElapsedMs} ms</span>}
                  </div>
                  <div className="mono" style={{ fontSize: 14, wordBreak: 'break-all', color: trial.derivedValue == null ? 'var(--ink-3)' : 'var(--pass)' }}>
                    <LongValueCell value={trial.derivedValue} />
                  </div>
                </div>
              )
            )}

            {trial.mode === 'VIEW_EXEC' && trial.outputs && (
              <div style={{ marginBottom: 10 }}>
                <div style={{ fontSize: 12.5, fontWeight: 600, color: '#5C4F41', marginBottom: 6 }}>出参实测值</div>
                <table className="table">
                  <thead><tr><th>出参</th><th>值</th></tr></thead>
                  <tbody>
                    {Object.entries(trial.outputs).map(([k, v]) => (
                      <tr key={k}>
                        <td className="mono" style={{ fontSize: 13.5 }}>{k}</td>
                        <td className="mono" style={{ fontSize: 13.5, wordBreak: 'break-all' }}><LongValueCell value={v} /></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {(trial.refs || []).length > 0 && (
              <div style={{ marginBottom: 10 }}>
                <div style={{ fontSize: 12.5, fontWeight: 600, color: '#5C4F41', marginBottom: 6 }}>引用基础特征实测值</div>
                <table className="table">
                  <thead><tr><th style={{ width: 190 }}>引用</th><th>实测值</th><th style={{ width: 130, whiteSpace: 'nowrap' }}>来源</th></tr></thead>
                  <tbody>
                    {(trial.refs || []).map(r => (
                      <tr key={r.code}>
                        <td className="mono" style={{ fontSize: 13.5 }}>@@{r.code}</td>
                        <td className="mono" style={{ fontSize: 13.5, wordBreak: 'break-all' }}>
                          <LongValueCell value={trial.refValues?.[r.code]} />
                        </td>
                        <td className="mono" style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>{r.viewCode}.{r.viewField}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            {trial.exprPreview && (
              <>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', margin: '10px 0 6px', gap: 8 }}>
                  <span style={{ fontSize: 12.5, fontWeight: 600, color: '#5C4F41' }}>
                    表达式回显（@@ 已替换为实测值）{!rawExpr && '· 超长引用值已折叠'}
                  </span>
                  <button className="btn sm" onClick={() => setRawExpr(v => !v)}>
                    {rawExpr ? '折叠长引用值' : '显示原始全文'}
                  </button>
                </div>
                <pre className="code-block" style={{ margin: 0, maxHeight: 160, overflow: 'auto', fontSize: 12.5 }}>{rawExpr ? trial.exprPreview : foldLongValues(trial.exprPreview, Object.values(trial.refValues || {}))}</pre>
              </>
            )}

            {(trial.warnings || []).length > 0 && (
              <div style={{ marginTop: 10 }}>
                {(trial.warnings || []).map((w, i) => (
                  <div key={i} style={{ fontSize: 12.5, color: 'var(--review)', lineHeight: 1.7 }}>· {w}</div>
                ))}
              </div>
            )}
          </>
        )}
      </div>

      <div className="wizard-foot" style={{ gridColumn: '1 / -1' }}>
        <button className="btn" onClick={onPrev}>上一步</button>
        <button className="btn primary" disabled={busy || !trial?.success} onClick={onNext}>
          {busy ? '检查中…' : '下一步：发布前检查'}
        </button>
      </div>
    </div>
  );
}

/* ===== 步骤 4：发布前检查 ===== */
function StepRelease({ items, onPrev, onDone, busy, form, meta, isEdit }) {
  const blocked = (items || []).some(i => i.type === 'block');
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 320px', gap: 16 }}>
      <div className="card">
        <div className="card-title">
          <span>发布前检查清单</span>
          <span className={`badge ${blocked ? 'badge-warn' : 'badge-pass'}`}>
            {blocked ? `${items.filter(i => i.type === 'block').length} 项阻断` : '无阻断'}
          </span>
        </div>
        {(items || []).map((c, i) => (
          <div key={i} className={`check-item ${c.type}`}>
            <div className="icon">{c.type === 'pass' ? '✓' : c.type === 'block' ? '!' : '?'}</div>
            <div className="content">
              <div className="title">{c.title}</div>
              <div className="desc">{c.desc}</div>
            </div>
            <div className="result">{c.result}</div>
          </div>
        ))}
        {!items?.length && <div className="empty">未获取到检查项</div>}
      </div>

      <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
        <div className="card">
          <div className="card-title"><span>发布摘要</span><span className="sub">PROD · 生产</span></div>
          {[
            ['特征类型', meta.title],
            ['特征编码', form.code],
            ['显示名称', form.displayName],
            ['计算方式', form.computeType],
            ['目标环境', 'PROD（生产）']
          ].map(([k, v]) => (
            <div key={k} style={{ display: 'flex', justifyContent: 'space-between', padding: '6px 0', fontSize: 13.5, borderBottom: '1px solid var(--stroke-weak)' }}>
              <span style={{ color: 'var(--ink-2)' }}>{k}</span>
              <span className="mono" style={{ fontWeight: 500 }}>{v}</span>
            </div>
          ))}
          <div style={{ fontSize: 12.5, color: 'var(--ink-2)', marginTop: 8, lineHeight: 1.5 }}>
            {isEdit
              ? '修改已保存且不改变特征当前状态；如需上线请在特征列表点「发布」。线上决策统一读取发布时冻结的 Bundle 快照。'
              : '提交后进入审批流，审批通过后方可发布；线上决策统一读取发布时冻结的 Bundle 快照。'}
          </div>
        </div>
      </div>

      <div style={{ gridColumn: '1 / -1', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <span style={{ fontSize: 13.5, color: blocked ? 'var(--reject)' : 'var(--ink-2)' }}>
          {blocked
            ? (isEdit ? '存在阻断项，建议修复后再上线' : '存在阻断项，需修复后方可提交审批')
            : (isEdit ? '检查通过，修改已保存，可返回列表' : '检查通过，可提交审批')}
        </span>
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn" onClick={onPrev}>上一步</button>
          <button className="btn primary" disabled={busy || (blocked && !isEdit)} onClick={onDone}>
            {busy ? (isEdit ? '处理中…' : '提交中…') : isEdit ? '完成并返回列表' : '提交审批'}
          </button>
        </div>
      </div>
    </div>
  );
}
