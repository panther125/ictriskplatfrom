import React, { useEffect, useRef, useState } from 'react';

/**
 * HTTP 视图的请求配置表单（替代手写 JSON）。
 * 对外仍是 scriptContent 字符串：表单 ⇄ JSON 双向转换，
 * JSON 中保留 $变量名 占位符，因此视图入参的自动解析逻辑无需改动。
 */

const METHODS = ['GET', 'POST', 'PUT'];
const BODY_METHODS = ['POST', 'PUT'];
const CONTENT_TYPES = ['application/json', 'application/x-www-form-urlencoded', 'text/plain'];

const AUTH_TYPES = [
  { v: 'NONE', label: '无鉴权' },
  { v: 'BASIC', label: 'Basic 账号密码' },
  { v: 'BEARER', label: 'Bearer Token' },
  { v: 'AK_SK_HMAC', label: 'AK / SK 签名' },
  { v: 'CUSTOM_SIGN', label: '自定义签名脚本' }
];

const FAIL_OPTIONS = [
  { v: 'DEFAULT_VALUE', label: '使用降级默认值' },
  { v: 'NULL', label: '视为无数据（null）' },
  { v: 'ALERT_NULL', label: '告警并置为 null' },
  { v: 'ABORT', label: '中断决策并拒绝' }
];

let seq = 0;
const row = (k = '', v = '') => ({ id: ++seq, key: k, value: v, enabled: true });

const DEFAULT_CFG = {
  method: 'GET',
  url: '',
  contentType: 'application/json',
  query: [],
  headers: [row('Accept', 'application/json')],
  body: [],
  authType: 'NONE',
  auth: {},
  connectTimeoutMs: 300,
  readTimeoutMs: 800,
  retryCount: 0,
  idempotent: false,
  successStatus: '200,204',
  successCodePath: '',
  successCodeValue: '',
  transportFail: 'DEFAULT_VALUE',
  businessFail: 'NULL',
  parseFail: 'ALERT_NULL',
  raw: ''
};

const objToRows = (o) => Object.entries(o || {}).map(([k, v]) => row(k, String(v ?? '')));

const rowsToObj = (rows) => {
  const out = {};
  (rows || []).forEach(r => {
    const k = (r.key || '').trim();
    if (k && r.enabled !== false) out[k] = r.value ?? '';
  });
  return out;
};

const omit = (o, keys = []) => {
  const out = {};
  Object.entries(o || {}).forEach(([k, v]) => { if (!keys.includes(k)) out[k] = v; });
  return out;
};

const dropEmpty = (o) => {
  const out = {};
  Object.entries(o || {}).forEach(([k, v]) => {
    if (v === '' || v === null || v === undefined) return;
    if (typeof v === 'object' && !Array.isArray(v) && Object.keys(v).length === 0) return;
    out[k] = v;
  });
  return out;
};

const numOr = (v, d) => (Number.isFinite(Number(v)) ? Number(v) : d);

/** scriptContent（JSON 文本）→ 表单数据；非 JSON 时保留原文，避免丢失用户手写内容 */
export function parseHttpScript(text) {
  if (!text || !text.trim()) return null;
  let o;
  try { o = JSON.parse(text); } catch { return { ...DEFAULT_CFG, raw: text }; }
  if (!o || typeof o !== 'object' || Array.isArray(o)) return { ...DEFAULT_CFG, raw: text };

  const method = METHODS.includes(String(o.method || '').toUpperCase()) ? String(o.method).toUpperCase() : 'GET';
  const headers = omit(o.headers, ['Content-Type']);
  const authObj = typeof o.auth === 'object' && o.auth ? o.auth : {};
  const criteria = o.successCriteria || {};
  const policy = o.failurePolicy || {};
  const retry = o.retry || {};
  const timeout = o.timeout || {};

  return {
    method,
    url: typeof o.url === 'string' ? o.url : '',
    contentType: o.contentType || o.headers?.['Content-Type'] || 'application/json',
    query: objToRows(o.query || o.params),
    headers: objToRows(headers).length ? objToRows(headers) : [],
    body: objToRows(o.body),
    authType: String((typeof o.auth === 'string' ? o.auth : authObj.type) || 'NONE').toUpperCase(),
    auth: omit(authObj, ['type']),
    connectTimeoutMs: numOr(timeout.connectMs ?? o.connectTimeoutMs, 300),
    readTimeoutMs: numOr(timeout.readMs ?? o.timeoutMs ?? o.readTimeoutMs, 800),
    retryCount: numOr(retry.count ?? o.retryCount, 0),
    idempotent: !!(retry.idempotent ?? o.idempotent),
    successStatus: Array.isArray(criteria.httpStatus)
      ? criteria.httpStatus.join(',')
      : String(criteria.httpStatus ?? '200,204'),
    successCodePath: criteria.businessCodePath ?? o.successCodePath ?? '',
    successCodeValue: criteria.businessCodeValue ?? o.successCodeValue ?? '',
    transportFail: policy.transport ?? 'DEFAULT_VALUE',
    businessFail: policy.business ?? 'NULL',
    parseFail: policy.parse ?? 'ALERT_NULL',
    raw: ''
  };
}

/** 表单数据 → scriptContent（JSON 文本） */
export function buildHttpScript(cfg) {
  if (cfg.raw) return cfg.raw;
  const useBody = BODY_METHODS.includes(cfg.method);
  const json = {
    method: cfg.method,
    url: cfg.url.trim(),
    ...(Object.keys(rowsToObj(cfg.query)).length ? { query: rowsToObj(cfg.query) } : {}),
    headers: dropEmpty({ ...rowsToObj(cfg.headers), ...(useBody ? { 'Content-Type': cfg.contentType } : {}) }),
    ...(useBody && Object.keys(rowsToObj(cfg.body)).length ? { body: rowsToObj(cfg.body) } : {}),
    ...(cfg.authType !== 'NONE' ? { auth: dropEmpty({ type: cfg.authType, ...cfg.auth }) } : {}),
    timeout: { connectMs: numOr(cfg.connectTimeoutMs, 300), readMs: numOr(cfg.readTimeoutMs, 800) },
    ...(numOr(cfg.retryCount, 0) > 0 ? { retry: { count: numOr(cfg.retryCount, 0), idempotent: !!cfg.idempotent } } : {}),
    successCriteria: dropEmpty({
      httpStatus: String(cfg.successStatus || '200,204').split(',').map(s => s.trim()).filter(Boolean).map(Number).filter(n => !Number.isNaN(n)),
      businessCodePath: cfg.successCodePath,
      businessCodeValue: cfg.successCodeValue
    }),
    failurePolicy: { transport: cfg.transportFail, business: cfg.businessFail, parse: cfg.parseFail }
  };
  return JSON.stringify(json, null, 2);
}

export default function HttpRequestForm({ value, onChange, inputVars = [] }) {
  const [cfg, setCfg] = useState(() => parseHttpScript(value) || DEFAULT_CFG);
  const [advanced, setAdvanced] = useState(false);
  const lastOut = useRef(value);
  const firstRun = useRef(true);

  // 外部内容变化（如「填入示例」、编辑回填）时同步回表单
  useEffect(() => {
    if (firstRun.current) { firstRun.current = false; return; }
    if (value === lastOut.current) return;
    setCfg(parseHttpScript(value) || DEFAULT_CFG);
    setAdvanced(!!(value && !safeParse(value)));
  }, [value]);

  const emit = (next) => {
    const out = buildHttpScript(next);
    lastOut.current = out;
    setCfg(next);
    onChange(out);
  };

  const patch = (k, v) => emit({ ...cfg, [k]: v, raw: '' });

  const setRows = (k, rows) => emit({ ...cfg, [k]: rows, raw: '' });
  const patchRow = (key, id, field, v) =>
    setRows(key, cfg[key].map(r => (r.id === id ? { ...r, [field]: v } : r)));
  const addRow = (key) => setRows(key, [...cfg[key], row()]);
  const removeRow = (key, id) => setRows(key, cfg[key].filter(r => r.id !== id));

  const patchAuth = (k, v) => emit({ ...cfg, auth: { ...cfg.auth, [k]: v }, raw: '' });

  const insertVar = (name) => {
    const base = cfg.url.trim();
    const sep = base.includes('?') ? '&' : '?';
    patch('url', `${base}${sep}${name}=$${name}`);
  };

  const useBody = BODY_METHODS.includes(cfg.method);
  const varListId = 'http-form-vars';

  if (cfg.raw && !advanced) {
    return (
      <div>
        <div className="warn-bar" style={{ marginBottom: 10, fontSize: 12.5 }}>
          当前内容不是标准请求配置 JSON，已保留原文不做转换。可直接编辑原文，或在表单模式下重建（覆盖后原文不再保留）。
        </div>
        <div style={{ display: 'flex', gap: 8, marginBottom: 10 }}>
          <button className="btn sm primary" onClick={() => { setCfg({ ...DEFAULT_CFG, url: '' }); }}>
            切换为表单填写
          </button>
        </div>
        <textarea
          className="textarea mono"
          style={{ width: '100%', minHeight: 200, padding: 12, boxSizing: 'border-box' }}
          spellCheck={false}
          value={cfg.raw}
          onChange={(e) => emit({ ...cfg, raw: e.target.value })}
        />
      </div>
    );
  }

  return (
    <div>
      <datalist id={varListId}>
        {inputVars.map(v => <option key={v} value={`$${v}`} />)}
      </datalist>

      {/* 请求行 */}
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 10 }}>
        <div className="seg" style={{ flexShrink: 0 }}>
          {METHODS.map(m => (
            <button key={m} className={m === cfg.method ? 'active' : ''} onClick={() => patch('method', m)}>{m}</button>
          ))}
        </div>
        <input
          className="input mono"
          style={{ flex: 1 }}
          value={cfg.url}
          onChange={(e) => patch('url', e.target.value)}
          placeholder="https://api.example.com/risk/blacklist?mobile=$mobile"
        />
        <button className="btn sm" onClick={() => setAdvanced(!advanced)}>
          {advanced ? '返回表单' : '查看 JSON'}
        </button>
      </div>

      {inputVars.length > 0 && (
        <div style={{ fontSize: 12.5, color: 'var(--ink-3)', marginBottom: 12, display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
          <span>可用入参：</span>
          {inputVars.map(v => (
            <button key={v} className="btn sm" style={{ padding: '2px 8px' }} onClick={() => insertVar(v)}>+ ${v}</button>
          ))}
          <span style={{ marginLeft: 4 }}>点击即拼接到 URL</span>
        </div>
      )}

      {advanced ? (
        <textarea
          className="textarea mono"
          style={{ width: '100%', minHeight: 260, padding: 12, boxSizing: 'border-box' }}
          spellCheck={false}
          value={value}
          onChange={(e) => onChange(e.target.value)}
        />
      ) : (
        <>
          <KvTable
            title="Query 参数"
            sub="键值会拼接到 URL，值可写 $变量 引用入参"
            listId={varListId}
            rows={cfg.query}
            onPatch={(id, f, v) => patchRow('query', id, f, v)}
            onAdd={() => addRow('query')}
            onRemove={(id) => removeRow('query', id)}
            emptyHint="无需额外参数时留空即可"
          />

          <KvTable
            title="请求头 Headers"
            sub="值支持占位符与内置函数，如 ${timestamp()}"
            listId={varListId}
            rows={cfg.headers}
            onPatch={(id, f, v) => patchRow('headers', id, f, v)}
            onAdd={() => addRow('headers')}
            onRemove={(id) => removeRow('headers', id)}
            emptyHint="无自定义请求头"
          />

          {useBody && (
            <div style={{ marginTop: 14 }}>
              <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 8 }}>
                <b style={{ fontSize: 13.5 }}>请求体 Body</b>
                <div className="seg" style={{ marginLeft: 8 }}>
                  {CONTENT_TYPES.map(ct => (
                    <button key={ct} className={ct === cfg.contentType ? 'active' : ''} onClick={() => patch('contentType', ct)}>{ct}</button>
                  ))}
                </div>
              </div>
              <KvTable
                title=""
                listId={varListId}
                rows={cfg.body}
                onPatch={(id, f, v) => patchRow('body', id, f, v)}
                onAdd={() => addRow('body')}
                onRemove={(id) => removeRow('body', id)}
                emptyHint="无请求体字段"
              />
            </div>
          )}

          {/* 鉴权 */}
          <section style={{ marginTop: 14 }}>
            <b style={{ fontSize: 13.5 }}>鉴权方式</b>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: '10px 16px', marginTop: 8 }}>
              <div className="field" style={{ marginBottom: 0 }}>
                <label>类型</label>
                <select className="input" value={cfg.authType} onChange={(e) => patch('authType', e.target.value)}>
                  {AUTH_TYPES.map(a => <option key={a.v} value={a.v}>{a.label}</option>)}
                </select>
              </div>
              {cfg.authType === 'BEARER' && (
                <AuthInput label="Token" value={cfg.auth.token} onChange={(v) => patchAuth('token', v)} masked />
              )}
              {cfg.authType === 'BASIC' && (
                <>
                  <AuthInput label="用户名" value={cfg.auth.username} onChange={(v) => patchAuth('username', v)} />
                  <AuthInput label="密码" value={cfg.auth.password} onChange={(v) => patchAuth('password', v)} masked />
                </>
              )}
              {cfg.authType === 'AK_SK_HMAC' && (
                <>
                  <AuthInput label="AccessKey" value={cfg.auth.accessKey} onChange={(v) => patchAuth('accessKey', v)} />
                  <AuthInput label="SecretKey" value={cfg.auth.secretKey} onChange={(v) => patchAuth('secretKey', v)} masked />
                  <AuthInput label="摘要算法" value={cfg.auth.algorithm} onChange={(v) => patchAuth('algorithm', v)} placeholder="HmacSHA256" />
                </>
              )}
              {cfg.authType === 'CUSTOM_SIGN' && (
                <AuthInput label="签名表达式" value={cfg.auth.script} onChange={(v) => patchAuth('script', v)} placeholder="如 md5(appId + $mobile + secret)" />
              )}
            </div>
            {cfg.authType !== 'NONE' && (
              <div style={{ fontSize: 12, color: 'var(--ink-3)', marginTop: 6 }}>
                凭据在服务端加密存储，列表中永久掩码展示，不提供明文查看
              </div>
            )}
          </section>

          {/* 超时与重试 */}
          <section style={{ marginTop: 16 }}>
            <b style={{ fontSize: 13.5 }}>超时与重试</b>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '10px 16px', marginTop: 8 }}>
              <div className="field" style={{ marginBottom: 0 }}>
                <label>连接超时（ms）</label>
                <input className="input" type="number" min={0} value={cfg.connectTimeoutMs} onChange={(e) => patch('connectTimeoutMs', e.target.value)} />
              </div>
              <div className="field" style={{ marginBottom: 0 }}>
                <label>读取超时（ms）</label>
                <input className="input" type="number" min={0} value={cfg.readTimeoutMs} onChange={(e) => patch('readTimeoutMs', e.target.value)} />
              </div>
              <div className="field" style={{ marginBottom: 0 }}>
                <label>重试次数</label>
                <input className="input" type="number" min={0} max={5} value={cfg.retryCount} onChange={(e) => patch('retryCount', e.target.value)} />
              </div>
            </div>
            {numOr(cfg.retryCount, 0) > 0 && (
              <div style={{ marginTop: 8 }}>
                <label style={{ display: 'flex', gap: 6, alignItems: 'center', fontSize: 13.5 }}>
                  <input type="checkbox" checked={cfg.idempotent} onChange={(e) => patch('idempotent', e.target.checked)} />
                  我确认该接口幂等（仅幂等查询才允许重试）
                </label>
                {!cfg.idempotent && (
                  <div className="warn-bar" style={{ marginTop: 6, fontSize: 12.5 }}>
                    未勾选幂等却配置了重试次数，保存时将被拦截
                  </div>
                )}
              </div>
            )}
          </section>

          {/* 成功判定 */}
          <section style={{ marginTop: 16 }}>
            <b style={{ fontSize: 13.5 }}>成功判定</b>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '10px 16px', marginTop: 8 }}>
              <div className="field" style={{ marginBottom: 0 }}>
                <label>HTTP 状态码白名单</label>
                <input className="input" value={cfg.successStatus} onChange={(e) => patch('successStatus', e.target.value)} placeholder="200,204" />
              </div>
              <div className="field" style={{ marginBottom: 0 }}>
                <label>业务码 JSONPath</label>
                <input className="input mono" value={cfg.successCodePath} onChange={(e) => patch('successCodePath', e.target.value)} placeholder="$.code" />
              </div>
              <div className="field" style={{ marginBottom: 0 }}>
                <label>期望业务码</label>
                <input className="input mono" value={cfg.successCodeValue} onChange={(e) => patch('successCodeValue', e.target.value)} placeholder="0000" />
              </div>
            </div>
          </section>

          {/* 失败语义 */}
          <section style={{ marginTop: 16 }}>
            <b style={{ fontSize: 13.5 }}>失败语义</b>
            <div className="info-bar" style={{ fontSize: 12.5, marginTop: 6, marginBottom: 8 }}>
              三类失败必须分别选择行为：「查无此人」不等于系统故障，混用会造成放行或误拒
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '10px 16px' }}>
              <FailSelect label="传输失败（超时 / 5xx）" value={cfg.transportFail} onChange={(v) => patch('transportFail', v)} />
              <FailSelect label="业务失败（查无此人 / 过期）" value={cfg.businessFail} onChange={(v) => patch('businessFail', v)} />
              <FailSelect label="解析失败（字段缺失 / 类型错）" value={cfg.parseFail} onChange={(v) => patch('parseFail', v)} />
            </div>
          </section>
        </>
      )}
    </div>
  );
}

function safeParse(text) {
  try { JSON.parse(text); return true; } catch { return false; }
}

function AuthInput({ label, value, onChange, masked, placeholder }) {
  return (
    <div className="field" style={{ marginBottom: 0 }}>
      <label>{label}</label>
      <input
        className="input"
        type={masked ? 'password' : 'text'}
        value={value || ''}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
      />
    </div>
  );
}

function FailSelect({ label, value, onChange }) {
  return (
    <div className="field" style={{ marginBottom: 0 }}>
      <label>{label}</label>
      <select className="input" value={value} onChange={(e) => onChange(e.target.value)}>
        {FAIL_OPTIONS.map(o => <option key={o.v} value={o.v}>{o.label}</option>)}
      </select>
    </div>
  );
}

function KvTable({ title, sub, listId, rows, onPatch, onAdd, onRemove, emptyHint }) {
  return (
    <div style={{ marginTop: title ? 14 : 0 }}>
      {title && (
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, marginBottom: 6 }}>
          <b style={{ fontSize: 13.5 }}>{title}</b>
          {sub && <span style={{ fontSize: 12, color: 'var(--ink-3)' }}>{sub}</span>}
        </div>
      )}
      {rows.length === 0 ? (
        <div className="empty" style={{ padding: '10px 12px' }}>{emptyHint}</div>
      ) : (
        <table className="table" style={{ tableLayout: 'fixed' }}>
          <thead>
            <tr>
              <th style={{ width: 32 }}>启用</th>
              <th>名称</th>
              <th>值 / 表达式</th>
              <th style={{ width: 48 }}>操作</th>
            </tr>
          </thead>
          <tbody>
            {rows.map(r => (
              <tr key={r.id}>
                <td style={{ textAlign: 'center' }}>
                  <input type="checkbox" checked={r.enabled !== false} onChange={(e) => onPatch(r.id, 'enabled', e.target.checked)} />
                </td>
                <td><input className="input mono" value={r.key} onChange={(e) => onPatch(r.id, 'key', e.target.value)} placeholder="mobile" /></td>
                <td><input className="input mono" list={listId} value={r.value} onChange={(e) => onPatch(r.id, 'value', e.target.value)} placeholder="$mobile" /></td>
                <td><button className="btn sm" onClick={() => onRemove(r.id)}>删除</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <button className="btn sm" style={{ marginTop: 6 }} onClick={onAdd}>+ 添加一行</button>
    </div>
  );
}
