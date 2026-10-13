import React, { useEffect, useRef, useState } from 'react';
import { api } from '../api/client.js';

/**
 * CSV / PMML 视图的文件上传组件（替代手填路径/配置）。
 * 上传后由后端解析并把文件内容内嵌进视图配置（scriptContent JSON），不再依赖服务器文件；
 * 组件负责展示解析结果，入参/出参由页面根据解析结果自动填入。
 */

const ACCEPT = {
  CSV: '.csv,text/csv',
  PMML: '.pmml,.xml,application/xml,text/xml'
};

const MAX_BYTES = 20 * 1024 * 1024;

const FILE_HINT = {
  CSV: '支持 .csv，自动识别编码（UTF-8 / GB18030 / UTF-16）与分隔符',
  PMML: '支持 .pmml / .xml（PMML 3.x – 4.4）'
};

function fmtSize(n) {
  if (n == null) return '';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(2)} MB`;
}

const stripStoredPrefix = (name) => name.replace(/^[0-9a-f]{8}_/i, '');

/** scriptContent JSON → 文件卡片信息（编辑回填 / 外部同步；无预览数据） */
function fromScript(text) {
  if (!text || !text.trim()) return { file: null, legacy: false };
  let o;
  try { o = JSON.parse(text); } catch { return { file: null, legacy: true }; }
  if (!o || typeof o !== 'object' || Array.isArray(o)) return { file: null, legacy: true };
  const hasCsv = o.delimiter !== undefined || o.encoding !== undefined || o.hasHeader !== undefined;
  const hasPmml = !!(o.modelName || o.algorithm || o.functionName);
  const meta = {
    sizeBytes: o.sizeBytes ?? null,
    csv: hasCsv ? { delimiter: o.delimiter, encoding: o.encoding, hasHeader: o.hasHeader } : null,
    pmml: hasPmml ? { modelName: o.modelName, algorithm: o.algorithm, functionName: o.functionName, inputs: o.inputs || {} } : null,
    fields: null,
    inputs: null,
    warnings: [],
    fresh: false
  };
  // 新格式：文件内容内嵌在视图配置中（content），不依赖服务器文件
  if (typeof o.content === 'string' && o.content) {
    return {
      legacy: false,
      file: {
        ...meta,
        embedded: true,
        storedPath: null,
        fileName: o.fileName || '上传文件',
        contentChars: o.content.length
      }
    };
  }
  // 旧格式：仅存服务器路径（file）；文件可能已被清理，建议重新上传
  if (typeof o.file !== 'string' || !o.file) return { file: null, legacy: true };
  return {
    legacy: false,
    file: {
      ...meta,
      embedded: false,
      storedPath: o.file,
      fileName: stripStoredPrefix(String(o.file).split('/').pop()),
      contentChars: null
    }
  };
}

/** 查看 JSON 用的预览文本：内嵌文件内容以省略提示替代，避免整段大文本渲染到页面 */
function jsonPreview(text) {
  try {
    const o = JSON.parse(text);
    if (o && typeof o === 'object' && typeof o.content === 'string' && o.content.length > 120) {
      const chars = o.content.length.toLocaleString('zh-CN');
      return {
        text: JSON.stringify({ ...o, content: `…（文件内容已内嵌，共 ${chars} 字符，此处省略显示）` }, null, 2),
        note: `已省略内嵌文件内容（${chars} 字符）`
      };
    }
  } catch { /* 非 JSON：原样截断展示 */ }
  return { text: text.length > 4000 ? text.slice(0, 4000) + '\n…（内容较长，已截断显示）' : text, note: '' };
}

export default function FileSourceForm({ sourceType, value, onChange, onParsed }) {
  const [state, setState] = useState(() => fromScript(value));
  const [uploading, setUploading] = useState(false);
  const [err, setErr] = useState('');
  const [dragging, setDragging] = useState(false);
  const [showJson, setShowJson] = useState(false);
  const inputRef = useRef(null);
  const lastOut = useRef(value);

  // 外部内容变化（编辑回填、切换类型）时同步文件卡片
  useEffect(() => {
    if (value === lastOut.current) return;
    lastOut.current = value;
    const next = fromScript(value);
    const cur = state.file;
    // 同一文件的配置微调（如新增 / 移除过滤入参列）：保留本次上传的解析结果与数据预览
    if (cur?.fresh && next.file && cur.fileName === next.file.fileName) {
      setState({
        legacy: false,
        file: {
          ...next.file,
          sizeBytes: cur.sizeBytes,
          csv: next.file.csv ? { ...cur.csv, delimiter: next.file.csv.delimiter, encoding: next.file.csv.encoding, hasHeader: next.file.csv.hasHeader } : null,
          pmml: next.file.pmml ? { ...cur.pmml, inputs: next.file.pmml.inputs } : null,
          fields: cur.fields,
          inputs: cur.inputs,
          warnings: cur.warnings,
          fresh: true
        }
      });
      return;
    }
    setState(next);
    setErr('');
    setShowJson(false);
  }, [value]);

  const pick = async (file) => {
    if (!file) return;
    const okExt = sourceType === 'CSV' ? /\.csv$/i.test(file.name) : /\.(pmml|xml)$/i.test(file.name);
    if (!okExt) {
      setErr(sourceType === 'CSV' ? '仅支持 .csv 文件' : '仅支持 .pmml / .xml 文件');
      return;
    }
    if (file.size > MAX_BYTES) {
      setErr('文件过大（上限 20MB），请压缩或拆分后重新上传');
      return;
    }
    setUploading(true);
    setErr('');
    try {
      const res = await api.views.upload(sourceType, file);
      let scriptText = res.scriptContent;
      let scriptCfg = null;
      try { scriptCfg = JSON.parse(scriptText); } catch { scriptCfg = null; }
      const scriptInputs = scriptCfg?.inputs || {};
      const contentChars = typeof scriptCfg?.content === 'string' ? scriptCfg.content.length : 0;
      const warnings = [...(res.warnings || [])];
      // CSV：重新上传时保留仍存在于新表头的过滤入参列
      if (sourceType === 'CSV') {
        let prev = null;
        try { prev = JSON.parse(value); } catch { prev = null; }
        const prevCols = Array.isArray(prev?.inputColumns) ? prev.inputColumns : [];
        if (prevCols.length && scriptCfg && typeof scriptCfg === 'object') {
          const names = new Set((res.fields || []).map(f => f.fieldName));
          const kept = prevCols.filter(c => names.has(c));
          const dropped = prevCols.filter(c => !names.has(c));
          if (dropped.length) warnings.push('以下过滤入参列不在新表头中，已移除：' + dropped.join('、'));
          scriptCfg.inputColumns = kept;
          scriptText = JSON.stringify(scriptCfg, null, 2);
        }
      }
      lastOut.current = scriptText;
      setState({
        legacy: false,
        file: {
          embedded: true,
          storedPath: null,
          fileName: res.fileName || stripStoredPrefix(file.name),
          sizeBytes: res.sizeBytes,
          contentChars,
          csv: res.csv || null,
          pmml: res.pmml ? { ...res.pmml, inputs: scriptInputs } : null,
          fields: res.fields || [],
          inputs: res.inputs || [],
          warnings,
          fresh: true
        }
      });
      onChange(scriptText);
      onParsed && onParsed(res);
    } catch (e) {
      setErr(e.message);
    } finally {
      setUploading(false);
    }
  };

  const removeFile = () => {
    lastOut.current = '';
    setState({ file: null, legacy: false });
    setErr('');
    setShowJson(false);
    onChange('');
    onParsed && onParsed(null);
  };

  const openPicker = () => { if (!uploading) inputRef.current?.click(); };

  const onDrop = (e) => {
    e.preventDefault();
    setDragging(false);
    if (uploading) return;
    const f = e.dataTransfer?.files?.[0];
    if (f) pick(f);
  };

  const f = state.file;
  const jsonView = showJson ? jsonPreview(value) : null;

  return (
    <div>
      <input
        ref={inputRef}
        type="file"
        accept={ACCEPT[sourceType]}
        style={{ display: 'none' }}
        onChange={(e) => { const file = e.target.files?.[0]; e.target.value = ''; if (file) pick(file); }}
      />

      {err && (
        <div className="warn-bar" style={{ marginBottom: 10, fontSize: 12.5, color: 'var(--reject)', background: 'rgba(190,58,43,.08)', border: '1px solid rgba(190,58,43,.25)' }}>
          {err}
        </div>
      )}

      {!f && state.legacy && (
        <div className="warn-bar" style={{ marginBottom: 10, fontSize: 12.5 }}>
          当前脚本为旧格式文本，上传文件后将被替换为文件配置（原文本不再保留）。
        </div>
      )}

      {!f ? (
        <div
          onClick={openPicker}
          onDragOver={(e) => { e.preventDefault(); if (!uploading) setDragging(true); }}
          onDragLeave={() => setDragging(false)}
          onDrop={onDrop}
          style={{
            border: `1.5px dashed ${dragging ? 'var(--view)' : 'var(--stroke-strong)'}`,
            background: dragging ? 'rgba(217,132,32,.06)' : '#FFFCF8',
            borderRadius: 10,
            padding: '30px 16px',
            textAlign: 'center',
            cursor: uploading ? 'wait' : 'pointer',
            transition: 'border-color .15s, background .15s'
          }}
        >
          {uploading ? (
            <div style={{ fontSize: 13.5, color: '#5C4F41' }}>文件上传解析中…</div>
          ) : (
            <>
              <div style={{ fontSize: 14, fontWeight: 600, color: '#241C15' }}>点击选择文件，或将文件拖拽到此处</div>
              <div style={{ fontSize: 12.5, color: 'var(--ink-3)', marginTop: 6 }}>{FILE_HINT[sourceType]} · 单个文件 ≤ 20MB</div>
              <div style={{ fontSize: 12.5, color: 'var(--ink-3)', marginTop: 2 }}>上传后自动解析并填入入参 / 出参；文件内容随视图配置一并保存，不会因服务器清理文件而丢失</div>
            </>
          )}
        </div>
      ) : (
        <>
          {/* 文件卡片 */}
          <div style={{ border: '1px solid var(--stroke-weak)', borderRadius: 10, padding: '10px 14px', background: '#FFFCF8' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
              <div style={{ minWidth: 0 }}>
                <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                  <span className="tag" style={{ fontSize: 12 }}>{sourceType}</span>
                  <b style={{ fontSize: 13.5 }}>{f.fileName}</b>
                  {f.sizeBytes != null && <span style={{ fontSize: 12.5, color: 'var(--ink-3)' }}>{fmtSize(f.sizeBytes)}</span>}
                  {f.fresh && <span className="tag" style={{ fontSize: 12, background: 'rgba(26,127,90,.12)', color: 'var(--pass)', border: 'none' }}>✓ 已解析</span>}
                  {f.embedded && !f.fresh && <span className="tag" style={{ fontSize: 12, background: 'rgba(26,127,90,.12)', color: 'var(--pass)', border: 'none' }}>内容已内嵌</span>}
                </div>
                {f.embedded ? (
                  <div style={{ fontSize: 12, color: 'var(--ink-3)', marginTop: 4 }}>
                    文件内容随视图配置一并保存{(f.contentChars || 0) > 0 && `（${f.contentChars.toLocaleString('zh-CN')} 字符）`}，不依赖服务器文件
                  </div>
                ) : (
                  <div className="mono" style={{ fontSize: 12, color: 'var(--ink-3)', marginTop: 4, wordBreak: 'break-all' }}>{f.storedPath}</div>
                )}
              </div>
              <div style={{ display: 'flex', gap: 8, flexShrink: 0 }}>
                <button className="btn sm" onClick={() => setShowJson(!showJson)}>{showJson ? '收起 JSON' : '查看 JSON'}</button>
                <button className="btn sm" disabled={uploading} onClick={openPicker}>{uploading ? '上传中…' : '重新上传'}</button>
                <button className="btn sm danger" disabled={uploading} onClick={removeFile}>移除</button>
              </div>
            </div>

            {/* 文件元信息 */}
            {sourceType === 'CSV' && f.csv && (
              <div style={{ fontSize: 12.5, color: '#5C4F41', marginTop: 8, display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                {f.csv.rowCount != null && <span>数据 <b>{f.csv.rowCount}</b> 行 × <b>{f.csv.columnCount}</b> 列</span>}
                <span>分隔符 <span className="mono">{f.csv.delimiter === '\t' ? '\\t' : (f.csv.delimiter || ',')}</span></span>
                <span>编码 {f.csv.encoding || 'UTF-8'}</span>
                <span>表头 {f.csv.hasHeader ? '是' : '否'}</span>
              </div>
            )}
            {sourceType === 'PMML' && f.pmml && (
              <div style={{ fontSize: 12.5, color: '#5C4F41', marginTop: 8, display: 'flex', gap: 12, flexWrap: 'wrap' }}>
                {f.pmml.modelName && <span>模型 <span className="mono">{f.pmml.modelName}</span></span>}
                {f.pmml.algorithm && <span>算法 {f.pmml.algorithm}</span>}
                {f.pmml.functionName && <span>功能 {f.pmml.functionName}</span>}
              </div>
            )}
          </div>

          {!f.embedded && !f.fresh && (
            <div className="warn-bar" style={{ marginTop: 8, fontSize: 12.5 }}>
              该视图仍以服务器文件路径保存（旧格式），文件可能已被清理；点「重新上传」后文件内容将内嵌到视图配置，不再依赖服务器文件。
            </div>
          )}

          {/* CSV 数据预览（仅本次上传） */}
          {sourceType === 'CSV' && f.fresh && f.csv?.previewRows?.length > 0 && (
            <div style={{ marginTop: 10 }}>
              <CsvPreview csv={f.csv} />
            </div>
          )}

          {/* PMML 入参映射 */}
          {sourceType === 'PMML' && f.pmml && Object.keys(f.pmml.inputs || {}).length > 0 && (
            <div style={{ marginTop: 8, fontSize: 12.5, color: '#5C4F41', lineHeight: 2 }}>
              <b>模型入参映射</b>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 4 }}>
                {Object.entries(f.pmml.inputs).map(([mf, v]) => (
                  <span key={mf} className="mono" style={{ fontSize: 12, background: 'rgba(232,116,15,.08)', border: '1px solid rgba(232,116,15,.2)', borderRadius: 6, padding: '2px 8px' }}>
                    {mf} → {v}
                  </span>
                ))}
              </div>
            </div>
          )}

          {/* 解析结论 */}
          {f.fresh && (
            <div style={{ marginTop: 8, fontSize: 12.5, color: 'var(--pass)', background: 'rgba(26,127,90,.08)', border: '1px solid rgba(26,127,90,.25)', borderRadius: 8, padding: '6px 12px', lineHeight: 1.8 }}>
              {sourceType === 'CSV' && (
                <>✓ 解析完成：已按表头自动填入 <b>{f.fields?.length || 0}</b> 个出参（类型按列值推断，可在下方调整）</>
              )}
              {sourceType === 'PMML' && (
                <>✓ 解析完成：{f.inputs?.length > 0 && <>已生成 <b>{f.inputs.length}</b> 个入参，</>}已填入 <b>{f.fields?.length || 0}</b> 个出参（可在验证时用测试入参在线试算打分）</>
              )}
            </div>
          )}

          {f.fresh && f.warnings?.length > 0 && (
            <div className="warn-bar" style={{ marginTop: 8, fontSize: 12.5, lineHeight: 1.8 }}>
              {f.warnings.map((w, i) => <div key={i}>· {w}</div>)}
            </div>
          )}

          {showJson && jsonView && (
            <div style={{ marginTop: 10 }}>
              <div style={{ fontSize: 12, color: 'var(--ink-3)', marginBottom: 4 }}>
                文件配置由上传自动生成并随视图保存；如需更换文件请点「重新上传」，无需手工编辑。
                {jsonView.note && <span style={{ color: '#B6A997' }}>　{jsonView.note}</span>}
              </div>
              <pre className="code-block" style={{ margin: 0, maxHeight: 200, overflow: 'auto', fontSize: 12.5 }}>{jsonView.text}</pre>
            </div>
          )}
        </>
      )}
    </div>
  );
}

function CsvPreview({ csv }) {
  const rows = csv.previewRows || [];
  const head = csv.hasHeader ? (rows[0] || []) : [];
  const body = csv.hasHeader ? rows.slice(1) : rows;
  const colCount = Math.max(csv.columnCount || 0, head.length, body[0]?.length || 0);
  const cell = (v) => (v === null || v === undefined || v === '' ? <span style={{ color: '#B6A997' }}>·</span> : String(v));

  return (
    <div>
      <div style={{ fontSize: 12.5, fontWeight: 600, color: '#5C4F41', marginBottom: 6 }}>
        数据预览 <span style={{ fontWeight: 400, color: 'var(--ink-3)' }}>（前 {body.length} 行 · 共 {csv.rowCount} 行）</span>
      </div>
      <div style={{ overflowX: 'auto', border: '1px solid var(--stroke-weak)', borderRadius: 8 }}>
        <table className="table" style={{ margin: 0, tableLayout: 'fixed', minWidth: Math.max(480, colCount * 110) }}>
          <thead>
            <tr>
              {Array.from({ length: colCount }).map((_, i) => (
                <th key={i} style={{ fontSize: 12.5 }}>{head[i] || `列${i + 1}`}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {body.map((r, ri) => (
              <tr key={ri}>
                {Array.from({ length: colCount }).map((_, ci) => (
                  <td key={ci} style={{ height: 32 }}>
                    <span className="mono" style={{ fontSize: 12.5, display: 'inline-block', maxWidth: 150, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', verticalAlign: 'bottom' }} title={r[ci] ?? ''}>
                      {cell(r[ci])}
                    </span>
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
