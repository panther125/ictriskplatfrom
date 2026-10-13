import React, { useEffect, useState } from 'react';
import { api } from '../api/client.js';
import { MetricCard, SectionCard, StatusTag, formatNum } from '../components/Common.jsx';

const PRIORITY_LABEL = { HIGH: '紧急待审批', MEDIUM: '待审批', LOW: '待处理' };

export default function Dashboard() {
  const [d, setD] = useState(null);
  useEffect(() => {
    api.dashboard().then(setD).catch(console.error);
  }, []);
  if (!d) return <div className="empty">加载中…</div>;

  const metric = (k) => d.metrics.find((m) => m.metricKey === k);
  return (
    <div>
      <div className="page-header">
        <h1>总览工作台</h1>
        <div className="sub">配置态 / 总览 · 策略中心仪表盘</div>
      </div>
      <div className="grid grid-4" style={{ marginBottom: 16 }}>
        <MetricCard label="在线策略版本" value={metric('online_version_count')?.value ?? '—'} note={metric('online_version_count')?.comparison} />
        <MetricCard label="今日在线决策量" value={formatNum(metric('decision_count')?.value)} note={metric('decision_count')?.comparison} />
        <MetricCard label="决策P99耗时" value={(metric('p99_ms')?.value ?? '—') + 'ms'} note={metric('p99_ms')?.comparison} />
        <MetricCard label="兜底结论占比" value={(metric('fallback_pct')?.value ?? '—') + '%'} note={metric('fallback_pct')?.comparison} />
      </div>

      <div className="grid grid-2" style={{ marginBottom: 16 }}>
        <SectionCard title="待处理事项" sub="共10项·按紧急度排序">
          {d.todos.map((t, i) => (
            <div className="row" key={i}>
              <div>
                <div className="title">{t.title}</div>
                <div className="desc">{t.count} 项</div>
              </div>
              <div className="right">
                {<StatusTag text={PRIORITY_LABEL[t.priority] || '待处理'} />}
                <span className="mono">{t.count}</span>
              </div>
            </div>
          ))}
        </SectionCard>

        <div>
          <SectionCard title="最近发布与变更" sub="审计全量留痕">
            {d.recentReleases.map((r, i) => (
              <div className="row" key={i}>
                <div>
                  <div className="title">{r.title}</div>
                  <div className="desc">{r.operator} · {fmtTime(r.occurredAt)} · {r.detail}</div>
                </div>
                <StatusTag text={r.scopeType === 'STRATEGY' ? '已发布' : r.scopeType === 'VIEW' ? '熔断' : '已发布'} />
              </div>
            ))}
          </SectionCard>
          <SectionCard title="常用入口" className="block">
            <div className="grid grid-2" style={{ gap: 8 }}>
              <button className="btn primary">新建策略</button>
              <button className="btn">导入视图配置</button>
            </div>
          </SectionCard>
        </div>
      </div>

      <div className="grid grid-2">
        <SectionCard title="数据源与特征健康度" sub="近30分钟·自动刷新">
          {d.health.map((h, i) => (
            <div className="row" key={i}>
              <div>
                <div className="title mono">{h.targetCode}</div>
                <div className="desc">{h.metric}</div>
              </div>
              <StatusTag text={h.status === 'NORMAL' ? '正常' : h.status === 'CIRCUIT_BREAKER' ? '熔断' : h.status === 'SURGE' ? '突增' : h.status} />
            </div>
          ))}
        </SectionCard>

        <SectionCard title="规则命中 TOP" sub="近24小时·全部策略 · 风控调优的第一信号">
          {d.topRules.map((r, i) => (
            <div className="row" key={i}>
              <div>
                <div className="title mono">{r.ruleCode} {r.ruleName}</div>
              </div>
              <div className="right">
                <span className="mono">{formatNum(r.hitCount)}</span>
                <span className="mono" style={{ color: 'var(--reject)' }}>{r.hitPct}%</span>
              </div>
            </div>
          ))}
        </SectionCard>
      </div>
    </div>
  );
}

function fmtTime(t) {
  if (!t) return '';
  return String(t).replace('T', ' ').slice(0, 16);
}
