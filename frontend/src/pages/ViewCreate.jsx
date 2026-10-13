import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../api/client.js';
import { Modal } from '../components/Common.jsx';
import HttpRequestForm from '../components/HttpRequestForm.jsx';
import FileSourceForm from '../components/FileSourceForm.jsx';

const TYPES = ['MySQL', 'HTTP', 'CSV', 'PMML'];
const READ_MODES = [
  { v: 'SINGLE', label: '单行记录' },
  { v: 'LIST', label: '列表' },
  { v: 'SCALAR', label: '标量' }
];
const DATA_TYPES = ['STRING', 'INTEGER', 'LONG', 'DECIMAL', 'BOOLEAN', 'DATETIME', 'ENUM', 'JSON'];

/** 解析脚本中以 $ 开头的变量：支持 $name 与 ${name}，标识符规则 [a-zA-Z_][a-zA-Z0-9_]* */
function parseInputVars(script) {
  const re = /\$\{([a-zA-Z_][a-zA-Z0-9_]*)\}|\$([a-zA-Z_][a-zA-Z0-9_]*)/g;
  const names = [];
  const seen = new Set();
  let m;
  while ((m = re.exec(script || ''))) {
    const name = m[1] || m[2];
    if (!seen.has(name)) { seen.add(name); names.push(name); }
  }
  return names;
}

/**
 * 视图入参变量列表：
 * CSV 视图的入参来自 scriptContent JSON 的 inputColumns（用户从文件表头选择作为过滤条件的列），
 * 其余类型解析脚本中的 $变量名 占位符。
 */
function varsOf(sourceType, text) {
  if (sourceType === 'CSV') {
    try {
      const o = JSON.parse(text || '');
      return Array.isArray(o?.inputColumns)
        ? o.inputColumns.filter(c => typeof c === 'string' && c.trim())
        : [];
    } catch { return []; }
  }
  return parseInputVars(text);
}

const SCRIPT_META = {
  MySQL: {
    label: 'SQL 查询脚本',
    hint: '在 SQL 中使用 $变量名（或 ${变量名}）引用入参，例如 $user_id；系统会自动解析为视图入参。',
    placeholder: `SELECT user_id,
       overdue_cnt,
       overdue_days
FROM   risk_user_info
WHERE  user_id = $user_id
  AND  biz_date = $biz_date
LIMIT  1`,
    example: `SELECT user_id,
       overdue_cnt,
       overdue_days
FROM   risk_user_info
WHERE  user_id = $user_id
  AND  biz_date = $biz_date
LIMIT  1`
  },
  HTTP: {
    label: 'HTTP 请求配置',
    hint: '按下方表单填写请求方式、URL、参数与失败行为，无需手写 JSON；URL 与参数值中可用 $变量名 引用视图入参（自动生成入参列表）。',
    placeholder: `{
  "method": "GET",
  "url": "https://api.example.com/risk/blacklist?mobile=$mobile",
  "timeout": { "connectMs": 300, "readMs": 800 }
}`,
    example: `{
  "method": "GET",
  "url": "https://api.example.com/risk/blacklist?mobile=$mobile",
  "headers": { "Accept": "application/json" },
  "timeout": { "connectMs": 300, "readMs": 800 },
  "successCriteria": { "httpStatus": [200], "businessCodePath": "$.code", "businessCodeValue": "0000" },
  "failurePolicy": { "transport": "DEFAULT_VALUE", "business": "NULL", "parse": "ALERT_NULL" }
}`
  },
  CSV: {
    label: 'CSV 数据文件',
    hint: '上传 CSV 文件：自动识别编码（UTF-8 / GB18030 / UTF-16）与分隔符，按表头生成出参并推断列类型；文件内容随视图配置一并保存，验证时按内嵌内容核对。'
  },
  PMML: {
    label: 'PMML 模型文件',
    hint: '上传 PMML 模型文件：自动解析模型字段，模型输入映射为视图入参（如 $credit_score），模型输出生成出参；文件内容随视图配置一并保存。'
  }
};

const emptyOutput = () => ({ fieldName: '', description: '', fieldType: 'STRING', defaultValue: '' });

export default function ViewCreate() {
  const navigate = useNavigate();
  const { viewId } = useParams();
  const isEdit = !!viewId;

  const [form, setForm] = useState({
    code: '', name: '', description: '',
    sourceType: 'MySQL', readMode: 'SINGLE',
    owner: '王敏', businessDomain: ''
  });
  const [script, setScript] = useState('');
  // 入参元信息（由脚本解析出的变量名驱动，用户可补充类型/必填/默认值）
  const [inputMeta, setInputMeta] = useState({});
  // 出参：用户手动添加
  const [outputs, setOutputs] = useState([]);
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState('');
  const [loading, setLoading] = useState(isEdit);
  // 已发布上线的视图是线上生效口径，进入编辑页也只读展示
  const [readOnly, setReadOnly] = useState(false);
  // CSV 过滤入参：从文件表头选择待添加的列
  const [csvPick, setCsvPick] = useState('');

  // 脚本验证（试跑）
  const [validateOpen, setValidateOpen] = useState(false);
  const [testValues, setTestValues] = useState({});
  const [validating, setValidating] = useState(false);
  const [validateResult, setValidateResult] = useState(null);

  // 编辑模式：回填视图详情
  useEffect(() => {
    if (!isEdit) return;
    api.views.detail(viewId).then(d => {
      const v = d.view || {};
      setForm({
        code: v.code || '',
        name: v.name || '',
        description: v.description || '',
        sourceType: v.sourceType || 'MySQL',
        readMode: v.readMode || 'SINGLE',
        owner: v.owner || '王敏',
        businessDomain: v.businessDomain || ''
      });
      const scriptText = v.scriptContent || '';
      setScript(scriptText);
      // 入参元信息：优先使用后端返回的 inputs，未返回时按脚本解析补默认值
      const names = varsOf(v.sourceType || 'MySQL', scriptText);
      const metaMap = {};
      names.forEach(n => { metaMap[n] = { varType: 'STRING', required: true, defaultValue: '' }; });
      (d.inputs || []).forEach(inp => {
        metaMap[inp.varCode] = {
          varType: inp.varType || 'STRING',
          required: inp.required ?? true,
          defaultValue: inp.defaultValue || ''
        };
      });
      setInputMeta(metaMap);
      // 出参：复用字段清单（category=OUTPUT 的视图字段）；_new=false 标记为库中已有行
      setOutputs((d.fields || []).map(f => ({
        fieldName: f.fieldName || '',
        description: f.description || '',
        fieldType: f.fieldType || 'STRING',
        defaultValue: f.defaultValue || '',
        _new: false
      })));
      setReadOnly((v.status || '') === 'PUBLISHED');
    }).catch(e => setErr(e.message))
      .finally(() => setLoading(false));
  }, [viewId]);

  const setField = (k) => (e) => { setErr(''); setForm({ ...form, [k]: e.target.value }); };

  const inputNames = useMemo(() => varsOf(form.sourceType, script), [script, form.sourceType]);

  // 脚本变化时同步入参：保留已填元信息，新增新变量，移除已删除变量
  const onScriptChange = (val) => {
    setScript(val);
    setErr('');
    const names = varsOf(form.sourceType, val);
    setInputMeta(prev => {
      const next = {};
      names.forEach(n => {
        next[n] = prev[n] || { varType: 'STRING', required: true, defaultValue: '' };
      });
      return next;
    });
  };

  const setInputMetaField = (name, key, value) => {
    setInputMeta(prev => ({ ...prev, [name]: { ...prev[name], [key]: value } }));
  };

  const addOutput = () => setOutputs([...outputs, { ...emptyOutput(), ...(isEdit ? { _new: true } : {}) }]);
  const setOutput = (i, key, value) => {
    setOutputs(prev => prev.map((o, idx) => idx === i ? { ...o, [key]: value } : o));
  };
  const removeOutput = (i) => setOutputs(prev => prev.filter((_, idx) => idx !== i));

  // ---- CSV 过滤入参：从文件表头（即出参列）选择添加 / 移除 ----
  const csvColumns = useMemo(
    () => (form.sourceType === 'CSV' ? outputs.map(o => o.fieldName.trim()).filter(Boolean) : []),
    [outputs, form.sourceType]
  );
  const csvAvailableColumns = useMemo(() => {
    const used = new Set(inputNames.map(n => n.toLowerCase()));
    return csvColumns.filter(c => !used.has(c.toLowerCase()));
  }, [csvColumns, inputNames]);

  const addCsvInput = () => {
    const col = csvPick;
    if (!col) return;
    let o = null;
    try { o = JSON.parse(script); } catch { return; }
    if (!o || typeof o !== 'object') return;
    const cols = Array.isArray(o.inputColumns) ? [...o.inputColumns] : [];
    if (!cols.some(c => c.toLowerCase() === col.toLowerCase())) cols.push(col);
    o.inputColumns = cols;
    setCsvPick('');
    onScriptChange(JSON.stringify(o, null, 2));
    // 类型默认取列推断类型（用户已手动改过类型则不覆盖）
    const t = outputs.find(x => x.fieldName === col)?.fieldType || 'STRING';
    setInputMeta(prev => {
      const cur = prev[col] || { required: true, defaultValue: '' };
      return { ...prev, [col]: { ...cur, varType: (!cur.varType || cur.varType === 'STRING') ? t : cur.varType } };
    });
  };

  const removeCsvInput = (name) => {
    let o = null;
    try { o = JSON.parse(script); } catch { return; }
    if (!o || typeof o !== 'object') return;
    o.inputColumns = (Array.isArray(o.inputColumns) ? o.inputColumns : []).filter(c => c !== name);
    onScriptChange(JSON.stringify(o, null, 2));
  };

  // 文件上传解析完成后：自动填入出参 / 入参元信息
  const handleFileParsed = (res) => {
    if (!res) return; // 移除文件：仅清空脚本，出参保留由用户自行处理
    if (res.fields?.length) {
      setOutputs(prev => {
        if (!isEdit) {
          // 新建：以文件解析结果为准全量覆盖
          return res.fields.map(f => ({
            fieldName: f.fieldName,
            description: f.description || '',
            fieldType: f.fieldType || 'STRING',
            defaultValue: ''
          }));
        }
        // 编辑：保留已有出参，仅追加解析出的新字段
        const existing = new Set(prev.map(o => o.fieldName.trim().toLowerCase()));
        const next = [...prev];
        res.fields.forEach(f => {
          if (!existing.has(f.fieldName.trim().toLowerCase())) {
            next.push({
              fieldName: f.fieldName,
              description: f.description || '',
              fieldType: f.fieldType || 'STRING',
              defaultValue: '',
              _new: true
            });
          }
        });
        return next;
      });
    }
    if (res.inputs?.length) {
      setInputMeta(prev => {
        const next = { ...prev };
        res.inputs.forEach(inp => {
          const cur = next[inp.varCode];
          next[inp.varCode] = cur
            ? { ...cur, varType: (!cur.varType || cur.varType === 'STRING') ? (inp.varType || 'STRING') : cur.varType }
            : { varType: inp.varType || 'STRING', required: true, defaultValue: '' };
        });
        return next;
      });
    }
    // CSV：按新表头刷新保留下来的过滤入参类型
    if (res.csv && res.fields?.length) {
      const typeByCol = new Map(res.fields.map(f => [f.fieldName, f.fieldType]));
      setInputMeta(prev => {
        const next = { ...prev };
        Object.keys(next).forEach(name => {
          const t = typeByCol.get(name);
          if (t && (!next[name].varType || next[name].varType === 'STRING')) {
            next[name] = { ...next[name], varType: t };
          }
        });
        return next;
      });
    }
  };

  const meta = SCRIPT_META[form.sourceType];
  const isFileType = form.sourceType === 'CSV' || form.sourceType === 'PMML';

  const openValidate = () => {
    setErr('');
    if (!script.trim()) { setErr(isFileType ? '请先上传文件再验证' : `请先填写${meta.label}再验证`); window.scrollTo({ top: 0, behavior: 'smooth' }); return; }
    if (outputs.length === 0) { setErr('请至少添加 1 个出参后再验证'); window.scrollTo({ top: 0, behavior: 'smooth' }); return; }
    if (outputs.some(o => !o.fieldName.trim())) { setErr('存在未填写名称的出参，请补充后再验证'); window.scrollTo({ top: 0, behavior: 'smooth' }); return; }
    // 测试值默认取入参登记的默认值
    setTestValues(prev => {
      const next = { ...prev };
      inputNames.forEach(n => {
        if (next[n] === undefined) next[n] = inputMeta[n]?.defaultValue || '';
      });
      return next;
    });
    setValidateResult(null);
    setValidateOpen(true);
  };

  const setTestValue = (name, value) => {
    setTestValues(prev => ({ ...prev, [name]: value }));
    // 修改入参后清空上次结果，避免误读
    if (validateResult) setValidateResult(null);
  };

  const runValidate = async () => {
    // 必填入参校验（CSV 入参作为过滤条件，PMML 入参用于模型打分）
    const missing = inputNames.find(n => (inputMeta[n]?.required ?? true) && `${testValues[n] ?? ''}`.trim() === '');
    if (missing) { setValidateResult({ success: false, error: `必填入参 ${missing} 未提供测试值` }); return; }
    setValidating(true);
    setValidateResult(null);
    const payload = {
      viewId: isEdit ? Number(viewId) : null,
      sourceType: form.sourceType,
      readMode: form.readMode,
      scriptContent: script,
      inputs: inputNames.map(name => ({
        varCode: name,
        varType: inputMeta[name]?.varType || 'STRING',
        required: inputMeta[name]?.required ?? true,
        value: testValues[name] === '' ? null : testValues[name]
      })),
      outputs: outputs.map((o, i) => ({
        fieldName: o.fieldName.trim(),
        fieldType: o.fieldType,
        defaultValue: o.defaultValue === '' ? null : o.defaultValue,
        sort: i
      }))
    };
    try {
      const res = await api.views.validate(payload);
      setValidateResult({
        success: res?.success ?? true,
        elapsedMs: res?.elapsedMs,
        results: res?.results || [],
        resolvedScript: res?.resolvedScript || '',
        httpStatus: res?.httpStatus,
        requestLine: res?.requestLine || '',
        responsePreview: res?.responsePreview || '',
        policyApplied: res?.policyApplied || 'NONE',
        warnings: res?.warnings || [],
        message: res?.message || '',
        error: res?.success === false ? (res.error || '验证未通过') : ''
      });
    } catch (e) {
      setValidateResult({ success: false, error: e.message });
    } finally {
      setValidating(false);
    }
  };

  const validate = () => {
    if (!form.code.trim()) return '请填写视图编码';
    if (!/^[a-z][a-z0-9_]{1,63}$/.test(form.code.trim())) return '视图编码只能包含小写字母、数字和下划线，且以字母开头（2-64 位）';
    if (!form.name.trim()) return '请填写视图名称';
    if (!script.trim()) return isFileType ? '请先上传数据文件' : `请填写${meta.label}`;
    if (isFileType) {
      let o = null;
      try { o = JSON.parse(script); } catch { /* 旧格式文本 */ }
      if (!o || typeof o !== 'object' || !o.file) return '当前内容不是有效的文件配置，请重新上传文件';
    }
    if (form.sourceType === 'HTTP') {
      try {
        const o = JSON.parse(script);
        if (!o?.url || !String(o.url).trim()) return '请填写请求 URL';
        if (Number(o?.retry?.count || 0) > 0 && o.retry.idempotent !== true) {
          return '已配置重试次数但未勾选「该接口幂等」，请勾选或把重试次数改为 0';
        }
      } catch {
        // 高级模式下允许自由格式，交由保存时的后端校验
      }
    }
    if (outputs.length === 0) return '请至少添加 1 个出参';
    const names = outputs.map(o => o.fieldName.trim());
    if (names.some(n => !n)) return '存在未填写名称的出参，请补充或删除';
    if (new Set(names).size !== names.length) return '出参名称存在重复，请保持唯一';
    return '';
  };

  const submit = async () => {
    if (readOnly) {
      setErr('该视图已发布上线，线上口径不可编辑；如需修改请先在视图列表将其下线');
      window.scrollTo({ top: 0, behavior: 'smooth' });
      return;
    }
    const msg = validate();
    if (msg) { setErr(msg); window.scrollTo({ top: 0, behavior: 'smooth' }); return; }
    setSaving(true); setErr('');
    // 出参载荷（创建与编辑通用）
    const outputPayload = outputs.map((o, i) => ({
      fieldName: o.fieldName.trim(),
      fieldType: o.fieldType,
      description: o.description.trim(),
      defaultValue: o.defaultValue === '' ? null : o.defaultValue,
      sort: i
    }));
    try {
      if (isEdit) {
        // 编辑：PUT 扁平视图实体（基本信息 + 脚本；入参由后端按脚本重建）
        await api.views.update(viewId, {
          code: form.code,
          name: form.name,
          description: form.description,
          sourceType: form.sourceType,
          readMode: form.readMode,
          owner: form.owner,
          businessDomain: form.businessDomain,
          scriptContent: script
        });
        // 出参：同名更新，新名称追加（后端 upsert）
        await api.views.updateFields(viewId, outputPayload);
        // 入参：变量名已随脚本重建，这里保存各变量的默认值等元信息
        if (inputNames.length > 0) {
          await api.views.updateInputs(viewId, inputNames.map(name => ({
            varCode: name,
            varType: inputMeta[name]?.varType || 'STRING',
            required: inputMeta[name]?.required ?? true,
            defaultValue: (inputMeta[name]?.defaultValue || '').trim() === '' ? null : inputMeta[name].defaultValue
          })));
        }
        navigate('/views', { state: { updatedViewId: Number(viewId) } });
      } else {
        const payload = {
          ...form,
          scriptContent: script,
          inputs: inputNames.map(name => ({
            varCode: name,
            varType: inputMeta[name]?.varType || 'STRING',
            required: inputMeta[name]?.required ?? true,
            defaultValue: inputMeta[name]?.defaultValue || null
          })),
          outputs: outputPayload,
          version: 'v1',
          status: 'DRAFT',
          refCount: 0,
          fieldCount: outputs.length,
          piiCount: 0
        };
        const created = await api.views.create(payload);
        navigate('/views', { state: { createdViewId: created?.id } });
      }
    } catch (e) {
      setErr(e.message);
      window.scrollTo({ top: 0, behavior: 'smooth' });
    } finally {
      setSaving(false);
    }
  };

  if (loading) return <div className="empty">视图加载中…</div>;

  return (
    <div>
      <div className="page-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end' }}>
        <div>
          <h1>{isEdit ? (readOnly ? '查看视图' : '编辑视图') : '新建视图'}</h1>
          <div className="sub">
            {isEdit
              ? <>{readOnly ? '已发布上线 · 只读' : '编辑视图'} · <span className="mono">{form.code}</span></>
              : <>数据接入 / 视图管理 / 新建视图 · 保存后为草稿状态</>}
          </div>
        </div>
        <button className="btn sm" onClick={() => navigate('/views')}>← 返回列表</button>
      </div>

      {readOnly && (
        <div className="warn-bar" style={{ marginBottom: 16, justifyContent: 'space-between', alignItems: 'center' }}>
          <span>
            <b style={{ color: 'var(--review)' }}>已发布上线 · 只读</b>
            　视图 <span className="mono">{form.code}</span> 已发布，线上口径已定版，因此本页不可编辑（只读态下无法在本页试跑验证）。
            如需核对取数，请回视图列表点「验证」试跑；如需修改，请先在视图列表点「下线」转为草稿，编辑后再重新发布。
          </span>
          <button className="btn sm" onClick={() => navigate('/views')}>去视图列表</button>
        </div>
      )}

      {err && (
        <div className="warn-bar" style={{ marginBottom: 16, color: 'var(--reject)', background: 'rgba(190,58,43,.08)', border: '1px solid rgba(190,58,43,.25)' }}>
          {isEdit ? '保存失败：' : '创建失败：'}{err.replace(/^(创建|保存)失败：?/, '')}
        </div>
      )}

      {/* 已发布上线的视图为只读：用 fieldset 原生禁用内部全部表单控件（含脚本表单与出参/入参表） */}
      <fieldset disabled={readOnly} style={{ border: 0, margin: 0, padding: 0, minInlineSize: 0, opacity: readOnly ? 0.92 : 1 }}>
      {/* 基本信息 */}
      <div className="card">
        <div className="card-title"><span>基本信息</span></div>
        <div className="view-form-grid" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 16px' }}>
          <div className="field">
            <label>视图编码 <span style={{ color: 'var(--reject)' }}>*</span></label>
            <input className="input mono" value={form.code} disabled={isEdit} onChange={setField('code')} placeholder="如 risk_user_info_view" style={isEdit ? { background: '#F2E9DC', color: 'var(--ink-2)', cursor: 'not-allowed' } : undefined} />
            {isEdit && <div style={{ fontSize: 12, color: 'var(--ink-3)', marginTop: 4 }}>编码为视图唯一标识，创建后不可修改</div>}
          </div>
          <div className="field">
            <label>视图名称 <span style={{ color: 'var(--reject)' }}>*</span></label>
            <input className="input" value={form.name} onChange={setField('name')} placeholder="如 风控用户信息视图" />
          </div>
          <div className="field">
            <label>数据源类型 <span style={{ color: 'var(--reject)' }}>*</span></label>
            <select className="input" value={form.sourceType} onChange={setField('sourceType')}>
              {TYPES.map(t => <option key={t} value={t}>{t}</option>)}
            </select>
          </div>
          <div className="field">
            <label>取数模式</label>
            <select className="input" value={form.readMode} onChange={setField('readMode')}>
              {READ_MODES.map(m => <option key={m.v} value={m.v}>{m.label}（{m.v}）</option>)}
            </select>
          </div>
          <div className="field">
            <label>责任人</label>
            <input className="input" value={form.owner} onChange={setField('owner')} />
          </div>
          <div className="field">
            <label>业务域</label>
            <input className="input" value={form.businessDomain} onChange={setField('businessDomain')} placeholder="如 user / loan" />
          </div>
        </div>
        <div className="field" style={{ marginBottom: 0 }}>
          <label>口径描述</label>
          <textarea className="input" rows={2} value={form.description} onChange={setField('description')} placeholder="说明该视图提供的数据口径与使用范围" style={{ fontFamily: 'var(--font-sans)', fontSize: 13.5, minHeight: 'auto' }} />
        </div>
      </div>

      {/* 取数脚本 */}
      <div className="card" style={{ marginTop: 16 }}>
        <div className="card-title">
          <span>{meta.label} <span style={{ color: 'var(--reject)' }}>*</span></span>
          <div style={{ display: 'flex', gap: 8 }}>
            {meta.example && <button className="btn sm" onClick={() => onScriptChange(meta.example)}>填入示例</button>}
            <button className="btn sm primary" onClick={openValidate}>▶ 验证</button>
          </div>
        </div>
        <div className="info-bar" style={{ marginBottom: 10, fontSize: 12.5 }}>
          {meta.hint}
        </div>
        {form.sourceType === 'HTTP' ? (
          <HttpRequestForm value={script} onChange={onScriptChange} inputVars={inputNames} />
        ) : isFileType ? (
          <FileSourceForm sourceType={form.sourceType} value={script} onChange={onScriptChange} onParsed={handleFileParsed} />
        ) : (
          <textarea
            className="textarea"
            style={{ width: '100%', minHeight: 220, padding: 12, boxSizing: 'border-box' }}
            spellCheck={false}
            value={script}
            onChange={(e) => onScriptChange(e.target.value)}
            placeholder={meta.placeholder}
          />
        )}
        {!isFileType && (
          <div style={{ fontSize: 12.5, color: 'var(--ink-3)', marginTop: 8 }}>
            已识别 <b style={{ color: 'var(--feature)', fontFamily: 'var(--font-mono)' }}>{inputNames.length}</b> 个入参变量
            {inputNames.length > 0 && <>：<span className="mono">{inputNames.join('、')}</span></>}
          </div>
        )}
      </div>

      {/* 入参（自动解析） */}
      <div className="card" style={{ marginTop: 16 }}>
        <div className="card-title">
          <span>入参 · 自动解析</span>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <span className="sub">{isFileType
              ? (form.sourceType === 'PMML'
                ? '变量由模型文件解析生成（模型字段 → $变量），可补充默认值'
                : '从文件表头选择过滤列作为入参，验证时按入参值过滤 CSV 数据行')
              : (isEdit
                ? '变量名随脚本自动同步；默认值可修改并保存（类型 / 必填保持创建时配置）'
                : '变量名由脚本中的 $ 引用自动生成，可补充类型 / 必填 / 默认值')}</span>
            {form.sourceType === 'CSV' && (
              <>
                <select
                  className="input"
                  style={{ fontSize: 12.5, padding: '5px 8px', width: 190 }}
                  value={csvPick}
                  onChange={(e) => setCsvPick(e.target.value)}
                  disabled={csvAvailableColumns.length === 0}
                >
                  <option value="">
                    {csvColumns.length === 0 ? '先上传 CSV 文件' : csvAvailableColumns.length === 0 ? '表头列均已添加' : '从文件表头选择列…'}
                  </option>
                  {csvAvailableColumns.map(c => <option key={c} value={c}>{c}</option>)}
                </select>
                <button className="btn sm primary" disabled={!csvPick} onClick={addCsvInput}>+ 添加过滤入参</button>
              </>
            )}
          </div>
        </div>
        {inputNames.length === 0 ? (
          <div className="empty">{isFileType
            ? (form.sourceType === 'CSV'
              ? '暂无过滤入参：上传文件后从右上角选择表头列添加（如 user_id）；验证时按入参值过滤数据行'
              : '模型文件解析后将自动生成入参')
            : <>在脚本中写入 <span className="mono" style={{ color: 'var(--feature)' }}>$变量名</span> 后，这里会自动出现入参</>}</div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
          <table className="table" style={{ tableLayout: 'fixed', minWidth: form.sourceType === 'CSV' ? 700 : 620 }}>
            <thead>
              <tr>
                <th style={{ width: '24%' }}>入参名</th>
                <th style={{ width: '18%' }}>类型</th>
                <th style={{ width: '16%' }}>是否必填</th>
                <th>默认值（选填）</th>
                {form.sourceType === 'CSV' && <th style={{ width: 72 }}>操作</th>}
              </tr>
            </thead>
            <tbody>
              {inputNames.map(name => {
                const m = inputMeta[name] || { varType: 'STRING', required: true, defaultValue: '' };
                return (
                  <tr key={name}>
                    <td>
                      <span className="mono" style={{ fontSize: 13.5, color: '#241C15' }}>{name}</span>
                      <span className="tag" style={{ marginLeft: 8, fontSize: 12 }}>{form.sourceType === 'CSV' ? '过滤列' : '自动'}</span>
                    </td>
                    <td>
                      <select className="input" style={{ fontSize: 12.5, padding: '5px 8px' }} disabled={isEdit} value={m.varType} onChange={(e) => setInputMetaField(name, 'varType', e.target.value)}>
                        {DATA_TYPES.map(t => <option key={t}>{t}</option>)}
                      </select>
                    </td>
                    <td>
                      <select className="input" style={{ fontSize: 12.5, padding: '5px 8px' }} disabled={isEdit} value={m.required ? '1' : '0'} onChange={(e) => setInputMetaField(name, 'required', e.target.value === '1')}>
                        <option value="1">必填</option>
                        <option value="0">选填</option>
                      </select>
                    </td>
                    <td>
                      <input className="input mono" style={{ fontSize: 12.5, padding: '5px 8px' }} value={m.defaultValue} onChange={(e) => setInputMetaField(name, 'defaultValue', e.target.value)} placeholder={form.sourceType === 'CSV' ? '验证过滤的默认值' : '未传参时的兜底值'} />
                    </td>
                    {form.sourceType === 'CSV' && (
                      <td><button className="btn sm danger" onClick={() => removeCsvInput(name)}>移除</button></td>
                    )}
                  </tr>
                );
              })}
            </tbody>
          </table>
          </div>
        )}
      </div>

      {/* 出参（手动登记） */}
      <div className="card" style={{ marginTop: 16 }}>
        <div className="card-title">
          <span>出参 · {isFileType ? '自动解析（可调整）' : (isEdit ? '登记（编辑）' : '手动登记')}</span>
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <span className="sub">{isFileType
              ? '由上传文件自动解析生成，可调整类型 / 描述 / 默认值，也可补充新出参'
              : (isEdit ? '可修改类型 / 描述 / 默认值，并可添加新出参（已有出参不可改名 / 删除）' : '声明脚本返回字段，供下游特征引用')}</span>
            <button className="btn sm primary" onClick={addOutput}>+ 添加出参</button>
          </div>
        </div>
        {outputs.length === 0 ? (
          <div className="empty">{isFileType
            ? '尚未上传文件：上传后出参将自动填入此清单'
            : (isEdit
              ? '暂无出参，点击右上角「+ 添加出参」登记脚本返回字段'
              : '暂无出参，点击右上角「+ 添加出参」登记脚本返回字段（至少 1 个）')}</div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
          <table className="table" style={{ tableLayout: 'fixed', minWidth: 680 }}>
            <thead>
              <tr>
                <th style={{ width: '22%' }}>出参名 <span style={{ color: 'var(--reject)' }}>*</span></th>
                <th style={{ width: '22%' }}>描述</th>
                <th style={{ width: '16%' }}>类型</th>
                <th style={{ width: '30%' }}>默认值</th>
                <th style={{ width: 64 }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {outputs.map((o, i) => (
                <tr key={i}>
                  <td><input className="input mono" style={{ fontSize: 12.5, padding: '5px 8px' }} value={o.fieldName} disabled={isEdit && !o._new} onChange={(e) => setOutput(i, 'fieldName', e.target.value)} placeholder={form.sourceType === 'HTTP' ? '响应字段名，或 $ 开头取值路径（如 $、$.data）' : '如 overdue_cnt'} {...(isEdit && !o._new ? { title: '已有出参不支持改名，新增出参请点「+ 添加出参」' } : {})} /></td>
                  <td><input className="input" style={{ fontSize: 12.5, padding: '5px 8px' }} value={o.description} onChange={(e) => setOutput(i, 'description', e.target.value)} placeholder="如 近90天逾期次数" /></td>
                  <td>
                    <select className="input" style={{ fontSize: 12.5, padding: '5px 8px' }} value={o.fieldType} onChange={(e) => setOutput(i, 'fieldType', e.target.value)}>
                      {DATA_TYPES.map(t => <option key={t}>{t}</option>)}
                    </select>
                  </td>
                  <td><input className="input mono" style={{ fontSize: 12.5, padding: '5px 8px' }} value={o.defaultValue} onChange={(e) => setOutput(i, 'defaultValue', e.target.value)} placeholder="取数失败/为空时的兜底值，如 0" /></td>
                  <td>{(!isEdit || o._new) && <button className="btn sm danger" onClick={() => removeOutput(i)}>删除</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
        )}
      </div>
      </fieldset>

      {/* 操作区 */}
      <div className="wizard-foot end" style={{ marginBottom: 24 }}>
        <button className="btn" disabled={saving} onClick={() => navigate('/views')}>{readOnly ? '← 返回列表' : '取消'}</button>
        {!readOnly && (
          <button className="btn primary" disabled={saving} onClick={submit}>{saving ? '保存中…' : isEdit ? '保存修改' : '保存草稿'}</button>
        )}
      </div>

      {/* 脚本验证弹窗 */}
      <Modal
        open={validateOpen}
        title="脚本验证"
        sub={form.sourceType === 'HTTP'
          ? `按当前配置向目标接口真实发起 1 次请求（单次、不重试、不落库）· ${form.sourceType} · ${form.readMode}`
          : form.sourceType === 'CSV'
            ? `读取已上传 CSV，按入参过滤数据行并核对出参（不落库）· ${form.sourceType} · ${form.readMode}`
            : isFileType
              ? `读取已上传 PMML 模型，用测试入参在线打分并核对出参（不落库）· ${form.sourceType} · ${form.readMode}`
              : `用测试入参实际执行${meta.label}（不落库）· ${form.sourceType} · ${form.readMode}`}
        onClose={() => !validating && setValidateOpen(false)}
        width={680}
        footer={(
          <>
            <button className="btn" disabled={validating} onClick={() => setValidateOpen(false)}>关闭</button>
            <button className="btn primary" disabled={validating} onClick={runValidate}>{validating ? '执行中…' : validateResult ? '重新执行' : '执行验证'}</button>
          </>
        )}
      >
        {/* 测试入参 */}
        <>
          <div style={{ fontSize: 13.5, fontWeight: 600, color: '#5C4F41', marginBottom: 8 }}>
            测试入参{form.sourceType === 'CSV' ? '（作为过滤条件）' : ''}
          </div>
          {inputNames.length === 0 ? (
            <div className="empty" style={{ padding: '12px 0' }}>
              {form.sourceType === 'CSV'
                ? '未配置过滤入参：将直接读取文件首行数据'
                : form.sourceType === 'PMML'
                  ? '未识别到模型入参（$变量）：仅解析核对出参'
                  : '脚本中未使用 $ 变量，将直接执行验证'}
            </div>
          ) : (
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px 16px', marginBottom: 4 }}>
              {inputNames.map(name => {
                const m = inputMeta[name] || { varType: 'STRING', required: true };
                return (
                  <div className="field" key={name} style={{ marginBottom: 0 }}>
                    <label style={{ fontSize: 12.5 }}>
                      <span className="mono">{name}</span>
                      <span className="tag" style={{ marginLeft: 6, fontSize: 12 }}>{m.varType}</span>
                      {m.required && <span style={{ color: 'var(--reject)', marginLeft: 4 }}>*</span>}
                    </label>
                    <input
                      className="input mono"
                      style={{ fontSize: 13.5 }}
                      value={testValues[name] ?? ''}
                      onChange={(e) => setTestValue(name, e.target.value)}
                      placeholder={m.defaultValue ? `默认值：${m.defaultValue}` : '请输入测试值'}
                    />
                  </div>
                );
              })}
            </div>
          )}
          {form.sourceType === 'CSV' && inputNames.length > 0 && (
            <div className="info-bar" style={{ fontSize: 12.5, marginTop: 4 }}>
              仅返回与全部入参值一致的数据行；出参取首个匹配行，未匹配时按空值 / 默认值处理。
            </div>
          )}
          {form.sourceType === 'PMML' && inputNames.length > 0 && (
            <div className="info-bar" style={{ fontSize: 12.5, marginTop: 4 }}>
              按模型字段测试值在线打分（支持 RegressionModel 线性回归与 SimpleMathModel 表达式模型）；衍生转换类输出（如 feature=transformedValue）暂不计算，由运行时引擎按模型转换。
            </div>
          )}
        </>

        {form.sourceType === 'HTTP' && !validateResult && (
          <div className="info-bar" style={{ fontSize: 12.5, marginTop: 4 }}>
            执行验证会真的调用你填写的接口（受连接/读取超时限制，最多 1 次请求）。请确认目标环境允许测试流量，勿指向生产写入类接口。
          </div>
        )}

        {/* 结果区 */}
        {validateResult && (
          <div style={{ marginTop: 16 }}>
            <div style={{ fontSize: 13.5, fontWeight: 600, color: '#5C4F41', marginBottom: 8 }}>执行结果</div>
            {validateResult.success ? (
              <>
                <div style={{ display: 'flex', gap: 12, alignItems: 'center', background: 'rgba(26,127,90,.08)', border: '1px solid rgba(26,127,90,.25)', borderRadius: 8, padding: '8px 12px', fontSize: 13.5, color: 'var(--pass)', marginBottom: 12, flexWrap: 'wrap' }}>
                  <b>✓ 验证通过</b>
                  {validateResult.elapsedMs != null && <span className="mono" style={{ color: '#5C4F41' }}>{validateResult.elapsedMs} ms</span>}
                  {validateResult.httpStatus != null && (
                    <span className="tag" style={{ fontSize: 12 }}>HTTP {validateResult.httpStatus}</span>
                  )}
                  <span style={{ color: '#5C4F41' }}>返回 {validateResult.results.length} 个出参</span>
                </div>

                {validateResult.warnings?.length > 0 && (
                  <div className="warn-bar" style={{ fontSize: 12.5, marginBottom: 12, lineHeight: 1.8 }}>
                    {validateResult.warnings.map((w, i) => <div key={i}>· {w}</div>)}
                  </div>
                )}

                <div style={{ overflowX: 'auto' }}>
                  <table className="table" style={{ tableLayout: 'fixed', minWidth: 560 }}>
                    <thead>
                      <tr>
                        <th style={{ width: '30%' }}>出参名</th>
                        <th style={{ width: '18%' }}>类型</th>
                        <th>返回值</th>
                        <th style={{ width: 90 }}>来源</th>
                      </tr>
                    </thead>
                    <tbody>
                      {validateResult.results.map((r, i) => (
                        <tr key={i}>
                          <td><span className="mono" style={{ fontSize: 13.5 }}>{r.fieldName}</span></td>
                          <td><span className="tag" style={{ fontSize: 12 }}>{r.fieldType || '-'}</span></td>
                          <td className="mono" style={{ fontSize: 13.5, wordBreak: 'break-all' }}>
                            {r.value === null || r.value === undefined || r.value === '' ? <span style={{ color: '#B6A997' }}>NULL</span> : String(r.value)}
                            {r.jsonPath && <div style={{ fontSize: 12, color: 'var(--ink-3)', marginTop: 2 }}>{r.jsonPath}</div>}
                          </td>
                          <td>{r.defaultValueUsed ? <span className="tag" style={{ fontSize: 12, background: 'rgba(217,132,32,.12)', color: 'var(--view)', border: 'none' }}>默认值</span> : <span style={{ fontSize: 12.5, color: 'var(--ink-2)' }}>实际取数</span>}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                {validateResult.resolvedScript && (
                  <>
                    <div style={{ fontSize: 12.5, fontWeight: 600, color: '#5C4F41', margin: '12px 0 6px' }}>
                      {form.sourceType === 'HTTP' ? '实际发出的请求（凭据已掩码）' : isFileType ? '文件解析摘要' : '参数绑定后语句'}
                    </div>
                    <pre className="code-block" style={{ margin: 0, maxHeight: 160, overflow: 'auto', fontSize: 12.5 }}>{validateResult.resolvedScript}</pre>
                  </>
                )}
                {validateResult.responsePreview && (
                  <>
                    <div style={{ fontSize: 12.5, fontWeight: 600, color: '#5C4F41', margin: '12px 0 6px' }}>
                      {isFileType ? (form.sourceType === 'CSV' ? '数据预览（前 5 行）' : '模型信息') : '响应体预览'}
                    </div>
                    <pre className="code-block" style={{ margin: 0, maxHeight: 160, overflow: 'auto', fontSize: 12.5 }}>{validateResult.responsePreview}</pre>
                  </>
                )}
              </>
            ) : (
              <div style={{ display: 'grid', gap: 10 }}>
                <div style={{ background: 'rgba(190,58,43,.08)', border: '1px solid rgba(190,58,43,.25)', borderRadius: 8, padding: '10px 12px', fontSize: 13.5, color: 'var(--reject)', lineHeight: 1.7, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                  ✗ 验证未通过{'\n'}{validateResult.error}
                </div>
                {(validateResult.requestLine || validateResult.resolvedScript) && (
                  <>
                    <div style={{ fontSize: 12.5, fontWeight: 600, color: '#5C4F41' }}>{isFileType ? '文件解析摘要' : '实际发出的请求'}</div>
                    <pre className="code-block" style={{ margin: 0, maxHeight: 140, overflow: 'auto', fontSize: 12.5 }}>{validateResult.resolvedScript || validateResult.requestLine}</pre>
                  </>
                )}
                {validateResult.responsePreview && (
                  <>
                    <div style={{ fontSize: 12.5, fontWeight: 600, color: '#5C4F41' }}>
                      {isFileType ? (form.sourceType === 'CSV' ? '数据预览（前 5 行）' : '模型信息') : `响应体预览${validateResult.httpStatus != null ? `（HTTP ${validateResult.httpStatus}）` : ''}`}
                    </div>
                    <pre className="code-block" style={{ margin: 0, maxHeight: 140, overflow: 'auto', fontSize: 12.5 }}>{validateResult.responsePreview}</pre>
                  </>
                )}
              </div>
            )}
          </div>
        )}
      </Modal>
    </div>
  );
}
