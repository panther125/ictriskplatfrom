import React, { useEffect, useState } from 'react';
import { api } from '../api/client.js';
import { StatusTag, Modal, FormField } from './Common.jsx';

/* 三种流量分配方式 */
const MODES = [
  { key: 'RANDOM', label: '随机切流', desc: '不区分用户，按放量比例把流量随机打散到该版本' },
  { key: 'PERCENT', label: '百分比切流', desc: '按固定比例承接流量，可限定渠道与客群标签' },
  { key: 'WHITELIST', label: '白名单切流', desc: '仅名单内用户命中该版本，用于定点验证与灰度观察' }
];

/* 可参与切流的版本状态：已发布 / 已下线（草稿与待审不可参与） */
const isEligibleStatus = (st) => st === 'PUBLISHED' || st === 'OFFLINE';
const statusRank = (st) => (st === 'PUBLISHED' ? 0 : st === 'OFFLINE' ? 1 : 2);

const PILL_BASE = {
  display: 'inline-flex', alignItems: 'center', gap: 6, height: 24,
  padding: '0 12px', borderRadius: 999, fontWeight: 600, fontVariantNumeric: 'tabular-nums'
};
const PILL_OK = { ...PILL_BASE, color: 'var(--pass)', background: 'rgba(26,127,90,.10)' };
const PILL_ERR = { ...PILL_BASE, color: 'var(--reject)', background: 'rgba(190,58,43,.09)' };

const buildRows = (vers, focusVersionId) => vers
  .map(v => ({
    versionId: v.id,
    versionNo: v.versionNo,
    rev: v.rev,
    status: v.status,
    statusText: v.statusText,
    eligible: isEligibleStatus(v.status),
    pct: isEligibleStatus(v.status) ? (v.trafficPct ?? 0) : '',
    whitelistUsers: v.whitelistUsers ?? 0,
    fallback: !!v.fallback,
    focus: v.id === focusVersionId
  }))
  .sort((a, b) => statusRank(a.status) - statusRank(b.status) || b.versionId - a.versionId);

/* 决策级切流弹窗：给一个策略（决策）下的多个版本分配流量份额；策略管理页与版本管理页共用 */
export default function StrategyTrafficModal({ open, strategy, versions, focusVersionId, onClose, onSaved }) {
  const [split, setSplit] = useState({ mode: 'PERCENT', channel: '', userTag: '', rows: [] });
  const [saving, setSaving] = useState(false);
  const [saveErr, setSaveErr] = useState('');

  useEffect(() => {
    if (!open) return;
    const vers = versions || [];
    const online = vers.filter(v => v.status === 'PUBLISHED');
    const ref = online.find(v => v.trafficMode) || online[0];
    setSaveErr('');
    setSplit({
      mode: ref?.trafficMode || 'PERCENT',
      channel: ref?.channel || '',
      userTag: ref?.userTag || '',
      rows: buildRows(vers, focusVersionId)
    });
  }, [open, versions, focusVersionId]);

  const updateRow = (versionId, patch) => {
    setSplit(s => ({ ...s, rows: s.rows.map(r => (r.versionId === versionId ? { ...r, ...patch } : r)) }));
  };

  const setFallbackRow = (versionId) => {
    setSplit(s => ({ ...s, rows: s.rows.map(r => ({ ...r, fallback: r.versionId === versionId })) }));
  };

  /* 弹窗的实时口径：合计 / 兜底 / 份额区间 */
  const eligibleRows = split.rows.filter(r => r.eligible);
  const isMulti = split.mode !== 'WHITELIST';
  const pctTotal = eligibleRows.reduce((a, r) => a + (Number(r.pct) || 0), 0);
  const fallbackRow = eligibleRows.find(r => r.fallback) || null;
  const whitelistTotal = eligibleRows.filter(r => !r.fallback).reduce((a, r) => a + (Number(r.whitelistUsers) || 0), 0);
  const splitValid = isMulti ? pctTotal === 100 : (!!fallbackRow && whitelistTotal > 0);
  const hashRanges = {};
  if (split.mode === 'PERCENT') {
    let acc = 0;
    eligibleRows.forEach(r => {
      const p = Number(r.pct) || 0;
      if (p > 0) { hashRanges[r.versionId] = `hash ${acc} – ${acc + p - 1}`; acc += p; }
      else hashRanges[r.versionId] = '—';
    });
  }

  const saveSplit = async () => {
    if (!strategy || !splitValid || saving) return;
    setSaving(true); setSaveErr('');
    try {
      await api.strategies.updateStrategyTraffic(strategy.id, {
        mode: split.mode,
        channel: split.channel || null,
        userTag: split.userTag || null,
        shares: eligibleRows.map(r => (isMulti
          ? { versionId: r.versionId, trafficPct: Number(r.pct) || 0, whitelistUsers: 0, fallback: false }
          : { versionId: r.versionId, trafficPct: r.fallback ? 100 : 0, whitelistUsers: Number(r.whitelistUsers) || 0, fallback: !!r.fallback }))
      });
      onClose?.();
      onSaved?.();
    } catch (e) {
      setSaveErr(e.message || '保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal
      open={open}
      title={strategy ? `切流配置 · ${strategy.name}` : '切流配置'}
      sub={strategy ? `PUT /api/strategies/${strategy.id}/traffic` : ''}
      onClose={() => !saving && onClose?.()}
      width={660}
      footer={(
        <>
          <button className="btn" disabled={saving} onClick={() => onClose?.()}>取消</button>
          <button className="btn primary" disabled={saving || !splitValid} onClick={saveSplit}>
            {saving ? '保存中…' : '保存切流配置'}
          </button>
        </>
      )}
    >
      <div style={{ background: '#FFF9F2', border: '1px solid var(--stroke-weak)', borderRadius: 10, padding: '10px 14px', fontSize: 12.5, color: 'var(--ink-2)', lineHeight: 1.9, marginBottom: 16 }}>
        {isMulti
          ? <>该决策下有 <b>{split.rows.length} 个版本</b>。「已发布 / 已下线」的版本可分配流量，合计需等于 <b style={{ color: 'var(--decision)' }}>100%</b>；份额填 0 的在线版本保存后自动下线，已下线版本填 &gt;0 份额即重新上线。</>
          : <>该决策下有 <b>{split.rows.length} 个版本</b>。白名单用户固定命中对应版本，未命中白名单的流量全部走「兜底版本」（单选）。</>}
      </div>

      <div className="field">
        <label>切流方式</label>
        <div className="seg" style={{ display: 'flex', width: '100%' }}>
          {MODES.map(m => (
            <button
              key={m.key}
              className={split.mode === m.key ? 'active' : ''}
              style={{ flex: 1 }}
              onClick={() => setSplit(s => ({ ...s, mode: m.key }))}
            >{m.label}</button>
          ))}
        </div>
        <div className="hint" style={{ marginTop: 8 }}>
          {split.mode === 'PERCENT'
            ? '按 hash(bizKey) % 100 落在哪个份额区间分配：同一用户固定命中同一版本，不抖动。'
            : MODES.find(m => m.key === split.mode)?.desc}
        </div>
      </div>

      <div className="field" style={{ marginBottom: 8 }}>
        <label>{isMulti ? '版本份额' : '版本白名单'}<span className="req">*</span></label>
      </div>
      <table className="table" style={{ fontSize: 12.5 }}>
        <thead>
          <tr>
            <th>版本</th>
            <th style={{ width: 96 }}>状态</th>
            {isMulti ? (
              <>
                <th>{split.mode === 'PERCENT' ? '份额区间' : '承接说明'}</th>
                <th style={{ width: 118, textAlign: 'right' }}>放量比例 %</th>
              </>
            ) : (
              <>
                <th style={{ width: 118, textAlign: 'right' }}>白名单人数</th>
                <th style={{ width: 64, textAlign: 'center' }}>兜底</th>
              </>
            )}
          </tr>
        </thead>
        <tbody>
          {split.rows.map(r => (
            <tr
              key={r.versionId}
              style={{
                background: r.eligible ? (r.focus ? '#FFF4E6' : undefined) : '#FAF7F2',
                opacity: r.eligible ? 1 : 0.55
              }}
            >
              <td>
                <b className="mono" style={{ fontSize: 13 }}>{r.versionNo}</b>
                {r.rev != null && <span className="hint mono"> rev {r.rev}</span>}
              </td>
              <td><StatusTag text={r.statusText} /></td>
              {!r.eligible ? (
                <td colSpan={2} className="hint" style={{ textAlign: 'right' }}>未发布，不可参与切流 —— 发布后回到本弹窗分配流量</td>
              ) : isMulti ? (
                <>
                  <td className="mono hint">{split.mode === 'PERCENT' ? (hashRanges[r.versionId] || '—') : '按份额随机'}</td>
                  <td style={{ textAlign: 'right' }}>
                    <input
                      className="input mono"
                      type="number" min={0} max={100}
                      style={{ width: 84, height: 32, textAlign: 'right' }}
                      value={r.pct}
                      onChange={e => updateRow(r.versionId, { pct: e.target.value })}
                    />
                  </td>
                </>
              ) : (
                <>
                  <td style={{ textAlign: 'right' }}>
                    <input
                      className="input mono"
                      type="number" min={0}
                      style={{ width: 84, height: 32, textAlign: 'right' }}
                      value={r.whitelistUsers}
                      onChange={e => updateRow(r.versionId, { whitelistUsers: e.target.value })}
                    />
                  </td>
                  <td style={{ textAlign: 'center' }}>
                    <input
                      type="radio" name="splitFallback"
                      checked={!!r.fallback}
                      style={{ accentColor: 'var(--decision)' }}
                      onChange={() => setFallbackRow(r.versionId)}
                    />
                  </td>
                </>
              )}
            </tr>
          ))}
        </tbody>
      </table>

      <div style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: 10, marginTop: 10, fontSize: 12.5 }}>
        <span className="hint">
          {isMulti
            ? `当前 ${eligibleRows.filter(r => (Number(r.pct) || 0) > 0).length} 个版本承接流量（上限 5 个）`
            : (fallbackRow && whitelistTotal > 0
              ? `${whitelistTotal} 名白名单用户，其余流量走 ${fallbackRow.versionNo}`
              : '白名单用户固定命中对应版本，其余流量走兜底版本')}
        </span>
        {isMulti
          ? (splitValid
            ? <span style={PILL_OK}>✓ 合计 100%</span>
            : <span style={PILL_ERR}>✕ 合计 {pctTotal}%，{pctTotal > 100 ? `超出 ${pctTotal - 100}%` : `还差 ${100 - pctTotal}%`}</span>)
          : (splitValid
            ? <span style={PILL_OK}>✓ 兜底：{fallbackRow.versionNo}</span>
            : <span style={PILL_ERR}>✕ {!fallbackRow ? '未选择兜底版本' : '至少为一个版本配置白名单人数'}</span>)}
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginTop: 16 }}>
        <FormField label="限定渠道">
          <input
            className="input"
            value={split.channel}
            onChange={e => setSplit(s => ({ ...s, channel: e.target.value }))}
            placeholder="不限则留空，如 APP_IOS"
          />
        </FormField>
        <FormField label="限定客群标签">
          <input
            className="input"
            value={split.userTag}
            onChange={e => setSplit(s => ({ ...s, userTag: e.target.value }))}
            placeholder="不限则留空，如 VIP"
          />
        </FormField>
      </div>

      {saveErr && <div className="field-err"><span>!</span><span>{saveErr}</span></div>}
    </Modal>
  );
}
