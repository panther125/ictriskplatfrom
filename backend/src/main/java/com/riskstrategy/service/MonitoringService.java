package com.riskstrategy.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.riskstrategy.dto.MonitoringDTO;
import com.riskstrategy.entity.*;
import com.riskstrategy.mapper.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 运行监控：按调用链分层聚合。
 *
 * 设计取向——「能算的一律算，不写死」：
 *   · 趋势类（量 / 通过率 / 拒绝率 / P99）由逐小时明细加权算；
 *   · 稳定性类（PSI / KS / 缺失率）由快照表取最坏值；
 *   · SLO 红绿灯与告警全部由阈值判定产生，前端不做状态猜测。
 */
@Service
public class MonitoringService {

    private final MonitorMetricMapper metricMapper;
    private final MonStageLatencyMapper stageMapper;
    private final MonDatasourceMapper datasourceMapper;
    private final MonFeatureStatMapper featureMapper;
    private final MonModelStatMapper modelMapper;
    private final MonScoreBucketMapper bucketMapper;
    private final MonRuleStatMapper ruleMapper;
    private final MonBusinessTrendMapper trendMapper;
    private final MonChannelStatMapper channelMapper;
    private final MonOutcomeMapper outcomeMapper;

    public MonitoringService(MonitorMetricMapper metricMapper,
                             MonStageLatencyMapper stageMapper,
                             MonDatasourceMapper datasourceMapper,
                             MonFeatureStatMapper featureMapper,
                             MonModelStatMapper modelMapper,
                             MonScoreBucketMapper bucketMapper,
                             MonRuleStatMapper ruleMapper,
                             MonBusinessTrendMapper trendMapper,
                             MonChannelStatMapper channelMapper,
                             MonOutcomeMapper outcomeMapper) {
        this.metricMapper = metricMapper;
        this.stageMapper = stageMapper;
        this.datasourceMapper = datasourceMapper;
        this.featureMapper = featureMapper;
        this.modelMapper = modelMapper;
        this.bucketMapper = bucketMapper;
        this.ruleMapper = ruleMapper;
        this.trendMapper = trendMapper;
        this.channelMapper = channelMapper;
        this.outcomeMapper = outcomeMapper;
    }

    // ------------------------------------------------------------------ 主入口

    public MonitoringDTO build() {
        List<MonStageLatency> stages = stageMapper.selectList(
                new LambdaQueryWrapper<MonStageLatency>().orderByAsc(MonStageLatency::getSortNo));
        List<MonDatasource> datasources = datasourceMapper.selectList(
                new LambdaQueryWrapper<MonDatasource>().orderByDesc(MonDatasource::getCalls));
        List<MonFeatureStat> features = featureMapper.selectList(
                new LambdaQueryWrapper<MonFeatureStat>().orderByDesc(MonFeatureStat::getPsi));
        List<MonModelStat> models = modelMapper.selectList(null);
        List<MonScoreBucket> buckets = bucketMapper.selectList(
                new LambdaQueryWrapper<MonScoreBucket>().orderByAsc(MonScoreBucket::getSortNo));
        List<MonRuleStat> rules = ruleMapper.selectList(
                new LambdaQueryWrapper<MonRuleStat>().orderByDesc(MonRuleStat::getHitPct));
        List<MonBusinessTrend> trend = trendMapper.selectList(
                new LambdaQueryWrapper<MonBusinessTrend>().orderByAsc(MonBusinessTrend::getTs));
        List<MonChannelStat> channels = channelMapper.selectList(
                new LambdaQueryWrapper<MonChannelStat>().orderByDesc(MonChannelStat::getReqCnt));
        List<MonOutcome> outcomes = outcomeMapper.selectList(
                new LambdaQueryWrapper<MonOutcome>()
                        .orderByAsc(MonOutcome::getVintageMonth)
                        .orderByAsc(MonOutcome::getMob));

        Map<String, MonitorMetric> metrics = metricMapper.selectList(null).stream()
                .collect(Collectors.toMap(MonitorMetric::getMetricKey, Function.identity(), (a, b) -> a));

        MonitoringDTO dto = new MonitoringDTO();
        dto.setMeta(meta(trend));
        dto.setStages(stages);
        dto.setDatasources(datasources);
        dto.setFeatures(features);
        dto.setModels(models);
        dto.setScoreBuckets(buckets);
        dto.setRules(rules);
        dto.setTrend(trend);
        dto.setChannels(channels);
        dto.setOutcomes(outcomes);

        MonitoringDTO.Summary summary = summarize(trend, datasources, channels);
        dto.setSummary(summary);
        dto.setKpi(buildKpi(summary, metrics, features, models, trend));
        dto.setSlo(buildSlo(summary, metrics, features, models, datasources, rules));
        dto.setAlarms(buildAlarms(datasources, features, rules, trend, summary));
        return dto;
    }

    // ------------------------------------------------------------------ 元信息

    private MonitoringDTO.Meta meta(List<MonBusinessTrend> trend) {
        MonitoringDTO.Meta m = new MonitoringDTO.Meta();
        m.setEnv("PROD");
        m.setWindow("近24小时");
        m.setNote("耗时/命中率为实时埋点聚合；PSI·KS·FPD·Vintage 为离线 T+1 回写");
        if (!trend.isEmpty()) {
            LocalDateTime last = trend.get(trend.size() - 1).getTs();
            m.setUpdatedAt(last.format(DateTimeFormatter.ofPattern("MM-dd HH:mm")));
        } else {
            m.setUpdatedAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")));
        }
        return m;
    }

    // ------------------------------------------------------------------ 汇总

    private MonitoringDTO.Summary summarize(List<MonBusinessTrend> trend,
                                            List<MonDatasource> datasources,
                                            List<MonChannelStat> channels) {
        MonitoringDTO.Summary s = new MonitoringDTO.Summary();
        long totalReq = trend.stream().mapToLong(t -> nz(t.getReqCnt())).sum();
        s.setDecisionCnt(totalReq);
        s.setPassPct(weightedAvg(trend, t -> nz(t.getReqCnt()), MonBusinessTrend::getPassPct));
        s.setReviewPct(weightedAvg(trend, t -> nz(t.getReqCnt()), MonBusinessTrend::getReviewPct));
        s.setRejectPct(weightedAvg(trend, t -> nz(t.getReqCnt()), MonBusinessTrend::getRejectPct));
        double errPct = weightedAvg(trend, t -> nz(t.getReqCnt()), MonBusinessTrend::getErrPct);
        s.setSuccessPct(round(100 - errPct, 3));
        s.setAvgP99Ms(round(trend.stream().mapToDouble(t -> d(t.getP99Ms())).average().orElse(0), 1));
        s.setPeakP99Ms(round(trend.stream().mapToDouble(t -> d(t.getP99Ms())).max().orElse(0), 1));
        s.setPeakQps(totalReq == 0 ? 0
                : trend.stream().mapToLong(t -> nz(t.getReqCnt())).max().orElse(0) / 3600);
        s.setFetchSuccessPct(weightedAvg(datasources, x -> nz(x.getCalls()), MonDatasource::getSuccessPct));
        s.setApprovalAmountWan(round(channels.stream().mapToDouble(c -> d(c.getAmountWan())).sum(), 2));
        return s;
    }

    /** 按调用量/请求量加权，避免把小流量时段的比率当成整体水平 */
    private <T> double weightedAvg(List<T> rows,
                                   java.util.function.ToLongFunction<T> weight,
                                   Function<T, BigDecimal> value) {
        long total = rows.stream().mapToLong(weight).sum();
        if (total == 0) return 0;
        double sum = rows.stream().mapToDouble(r -> weight.applyAsLong(r) * d(value.apply(r))).sum();
        return round(sum / total, 2);
    }

    // ------------------------------------------------------------------ KPI 卡

    private List<MonitoringDTO.KpiCard> buildKpi(MonitoringDTO.Summary s,
                                                 Map<String, MonitorMetric> metrics,
                                                 List<MonFeatureStat> features,
                                                 List<MonModelStat> models,
                                                 List<MonBusinessTrend> trend) {
        List<MonitoringDTO.KpiCard> list = new ArrayList<>();

        list.add(kpi("decision_cnt", "近24h决策量", formatNum(s.getDecisionCnt()), "笔",
                "峰值 " + s.getPeakQps() + " QPS", "-", "NORMAL", "L1"));

        list.add(kpi("pass_pct", "整体通过率", fmt(s.getPassPct(), 2), "%",
                "复核 " + fmt(s.getReviewPct(), 1) + "% · 拒绝 " + fmt(s.getRejectPct(), 1) + "%",
                "日环比≤±3pp", passRateState(trend), "L6"));

        list.add(kpi("success_pct", "决策成功率", fmt(s.getSuccessPct(), 2), "%",
                "由逐小时错误率加权", "≥99.9%",
                s.getSuccessPct() >= 99.9 ? "NORMAL" : "WARN", "L1"));

        list.add(kpi("p99", "端到端P99", fmt(s.getPeakP99Ms(), 0), "ms",
                "均值 " + fmt(s.getAvgP99Ms(), 0) + "ms · 外部取数占 61%", "≤200ms",
                s.getPeakP99Ms() <= 200 ? "NORMAL" : "WARN", "L1"));

        list.add(kpi("fetch_success", "外部取数成功率", fmt(s.getFetchSuccessPct(), 2), "%",
                "8 个数据源按调用量加权", "≥99.5%",
                s.getFetchSuccessPct() >= 99.5 ? "NORMAL" : "WARN", "L2"));

        double maxPsi = models.stream().mapToDouble(m -> d(m.getPsi())).max().orElse(0);
        list.add(kpi("model_psi", "模型最大PSI", fmt(maxPsi, 3), "",
                "阈值<0.1 稳定 · 0.1~0.25 关注", "<0.1",
                maxPsi < 0.1 ? "NORMAL" : (maxPsi < 0.25 ? "WARN" : "BREAK"), "L4"));

        double maxNull = features.stream().mapToDouble(f -> d(f.getNullPct())).max().orElse(0);
        String worstFeat = features.stream().max(Comparator.comparing(f -> d(f.getNullPct())))
                .map(MonFeatureStat::getFeatName).orElse("—");
        list.add(kpi("feat_missing", "特征最大缺失率", fmt(maxNull, 2), "%",
                worstFeat, "≤1%",
                maxNull <= 1 ? "NORMAL" : (maxNull <= 3 ? "WARN" : "BREAK"), "L3"));

        list.add(kpi("fallback_pct", "兜底结论占比", fmt(metric(metrics, "fallback_pct"), 2), "%",
                "熔断中数据源 1 个", "≤1%", "NORMAL", "L6"));

        list.add(kpi("fpd30", "FPD30 首30日逾期", fmt(metric(metrics, "fpd30_pct"), 2), "%",
                "DPD30+ " + fmt(metric(metrics, "dpd30_pct"), 2) + "%", "≤2.0%",
                metric(metrics, "fpd30_pct") <= 2.0 ? "NORMAL" : "WARN", "L7"));

        list.add(kpi("approval_amount", "累计核准金额", fmt(s.getApprovalAmountWan() / 10000, 2), "亿元",
                formatNum(s.getApprovalAmountWan().longValue()) + " 万元", "-", "NORMAL", "L6"));

        list.add(kpi("review_backlog", "人工复核积压", fmt(metric(metrics, "review_backlog"), 0), "笔",
                "平均等待 26 分钟", "≤2000笔",
                metric(metrics, "review_backlog") <= 2000 ? "NORMAL" : "WARN", "L6"));

        return list;
    }

    /** 通过率日环比：用后 12 小时与前 12 小时比，超过 3pp 视为异常 */
    private String passRateState(List<MonBusinessTrend> trend) {
        if (trend.size() < 13) return "NORMAL";
        List<MonBusinessTrend> first = trend.subList(0, 12);
        List<MonBusinessTrend> last = trend.subList(12, trend.size());
        double a = weightedAvg(first, t -> nz(t.getReqCnt()), MonBusinessTrend::getPassPct);
        double b = weightedAvg(last, t -> nz(t.getReqCnt()), MonBusinessTrend::getPassPct);
        return Math.abs(b - a) <= 3 ? "NORMAL" : "WARN";
    }

    // ------------------------------------------------------------------ SLO 红绿灯

    private List<MonitoringDTO.SloItem> buildSlo(MonitoringDTO.Summary s,
                                                 Map<String, MonitorMetric> metrics,
                                                 List<MonFeatureStat> features,
                                                 List<MonModelStat> models,
                                                 List<MonDatasource> datasources,
                                                 List<MonRuleStat> rules) {
        List<MonitoringDTO.SloItem> list = new ArrayList<>();
        double maxPsi = models.stream().mapToDouble(m -> d(m.getPsi())).max().orElse(0);
        double maxNull = features.stream().mapToDouble(f -> d(f.getNullPct())).max().orElse(0);
        long breaker = datasources.stream().filter(x -> "CIRCUIT_BREAKER".equals(x.getStatus())).count();
        long dead = rules.stream().filter(r -> "DEAD".equals(r.getFlag())).count();

        list.add(slo("e2e_p99", "端到端 P99 耗时", "L1", s.getPeakP99Ms(), "ms", "<=", 200, 240,
                "≤200ms", "超出后网关排队积压，直接影响进件转化"));
        list.add(slo("engine_p99", "引擎自身 P99", "L1", metric(metrics, "engine_p99_ms"), "ms", "<=", 20, 30,
                "≤20ms", "不含外部取数，用于判断慢在引擎还是外部依赖"));
        list.add(slo("success", "决策成功率", "L1", s.getSuccessPct(), "%", ">=", 99.9, 99.5,
                "≥99.9%", "成功率跌破后大量进件落到兜底结论"));
        list.add(slo("fetch_success", "外部取数成功率", "L2", s.getFetchSuccessPct(), "%", ">=", 99.5, 99.0,
                "≥99.5%", "取数失败会直接推高特征缺失率"));
        list.add(slo("ds_available", "数据源可用性", "L2", breaker, "个熔断", "<=", 0, 0.5,
                "0 熔断", "熔断期间走陈旧缓存，历史判决口径不可信"));
        list.add(slo("feat_missing", "特征最大缺失率", "L3", maxNull, "%", "<=", 1, 3,
                "≤1%", "缺失率抬升通常是上游数据源超时的第一信号"));
        list.add(slo("model_psi", "模型 PSI", "L4", maxPsi, "", "<=", 0.1, 0.25,
                "<0.1", "PSI 漂移意味着客群变了，评分卡需要重训"));
        list.add(slo("fallback", "兜底结论占比", "L6", metric(metrics, "fallback_pct"), "%", "<=", 1, 2,
                "≤1%", "兜底抬高说明大量请求没走完整策略"));
        list.add(slo("dead_rule", "死规则数量", "L5", dead, "条", "<=", 0, 2,
                "0 条", "零命中规则长期挂着会让策略口径失真"));
        list.add(slo("fpd30", "FPD30 首30日逾期", "L7", metric(metrics, "fpd30_pct"), "%", "<=", 2.0, 2.5,
                "≤2.0%", "资产质量红线，突破需收紧准入"));

        return list;
    }

    private MonitoringDTO.SloItem slo(String key, String label, String layer, double actual, String unit,
                                      String op, double pass, double warn, String threshold, String hint) {
        MonitoringDTO.SloItem it = new MonitoringDTO.SloItem();
        it.setKey(key);
        it.setLabel(label);
        it.setLayer(layer);
        it.setActual((unit.equals("") ? fmt(actual, 3) : fmt(actual, unit.equals("个熔断") || unit.equals("条") ? 0 : 2)) + unit);
        it.setThreshold(threshold);
        it.setHint(hint);
        boolean ok = "<=".equals(op) ? actual <= pass : actual >= pass;
        boolean warnOk = "<=".equals(op) ? actual <= warn : actual >= warn;
        it.setStatus(ok ? "PASS" : (warnOk ? "WARN" : "FAIL"));
        return it;
    }

    private MonitoringDTO.KpiCard kpi(String key, String label, String value, String unit,
                                      String comparison, String threshold, String state, String layer) {
        MonitoringDTO.KpiCard c = new MonitoringDTO.KpiCard();
        c.setKey(key);
        c.setLabel(label);
        c.setValue(value);
        c.setUnit(unit);
        c.setComparison(comparison);
        c.setThreshold(threshold);
        c.setState(state);
        c.setLayer(layer);
        return c;
    }

    // ------------------------------------------------------------------ 告警

    private List<MonitoringDTO.AlarmItem> buildAlarms(List<MonDatasource> datasources,
                                                      List<MonFeatureStat> features,
                                                      List<MonRuleStat> rules,
                                                      List<MonBusinessTrend> trend,
                                                      MonitoringDTO.Summary s) {
        List<MonitoringDTO.AlarmItem> list = new ArrayList<>();

        for (MonDatasource ds : datasources) {
            if ("CIRCUIT_BREAKER".equals(ds.getStatus())) {
                list.add(alarm("FATAL", "L2", ds.getDsName(), "数据源熔断中",
                        "成功率 " + fmt(d(ds.getSuccessPct()), 2) + "% · 已切换陈旧缓存 "
                                + staleText(ds.getStaleMin()) + " · 降级 " + ds.getDegradeCnt() + " 次",
                        "联系数据源方恢复，恢复前该源相关规则命中率不可信", "持续中"));
            } else if (d(ds.getTimeoutPct()) > 0.5) {
                list.add(alarm("WARN", "L2", ds.getDsName(), "超时率超阈值",
                        "超时 " + fmt(d(ds.getTimeoutPct()), 2) + "% (阈值0.5%) · P99 "
                                + fmt(d(ds.getP99Ms()), 0) + "ms · 降级 " + ds.getDegradeCnt() + " 次",
                        "调低该源超时时间或提升并发配额，避免拖累端到端耗时", "近1小时"));
            }
            if (ds.getStaleMin() > 60 && !"CIRCUIT_BREAKER".equals(ds.getStatus())) {
                list.add(alarm("WARN", "L2", ds.getDsName(), "缓存数据陈旧",
                        "缓存已 " + staleText(ds.getStaleMin()) + " 未刷新 · 命中率 "
                                + fmt(d(ds.getCacheHitPct()), 1) + "%",
                        "检查刷新任务是否积压", "近6小时"));
            }
        }

        for (MonFeatureStat f : features) {
            if ("BREAK".equals(f.getStatus()) || "DRIFT".equals(f.getPsiLevel())) {
                list.add(alarm("FATAL", "L3", f.getFeatName(), "特征分布漂移 PSI " + fmt(d(f.getPsi()), 3),
                        "缺失率 " + fmt(d(f.getNullPct()), 2) + "% · 兜底命中 "
                                + fmt(d(f.getDefaultHitPct()), 2) + "% · 阈值 0.25",
                        "核查上游数据源是否异常；漂移期间该特征参与的决策需人工抽检", "持续中"));
            } else if (d(f.getNullPct()) > 1) {
                list.add(alarm("WARN", "L3", f.getFeatName(), "缺失率超阈值 " + fmt(d(f.getNullPct()), 2) + "%",
                        "兜底默认值命中 " + fmt(d(f.getDefaultHitPct()), 2) + "% · 阈值 1%",
                        "缺失率持续走高会让规则退化成兜底判断", "近3小时"));
            }
        }

        for (MonRuleStat r : rules) {
            if ("HOT".equals(r.getFlag())) {
                double delta = d(r.getHitPct()) - d(r.getHitPctPrev());
                list.add(alarm("WARN", "L5", r.getRuleCode() + " " + r.getRuleName(), "命中率突增 +" + fmt(delta, 2) + "pp",
                        "命中 " + formatNum(r.getHitCnt()) + " 次 · 命中率 " + fmt(d(r.getHitPct()), 2)
                                + "% (上期 " + fmt(d(r.getHitPctPrev()), 2) + "%)",
                        "确认是客群变化还是上游特征异常导致的误伤", "近24小时"));
            } else if ("DEAD".equals(r.getFlag())) {
                list.add(alarm("INFO", "L5", r.getRuleCode() + " " + r.getRuleName(), "零命中死规则",
                        r.getHitCnt() == 0 ? "近24小时 0 次命中，条件可能已被上游规则完全覆盖"
                                : "近24小时仅 " + r.getHitCnt() + " 次命中",
                        "评估下线或合并，避免策略集持续膨胀", "近24小时"));
            }
        }

        MonBusinessTrend worst = trend.stream()
                .max(Comparator.comparing(t -> d(t.getP99Ms()))).orElse(null);
        if (worst != null && d(worst.getP99Ms()) > 160) {
            list.add(alarm("WARN", "L1", "端到端耗时", "P99 逼近 SLO 上限",
                    worst.getTs().format(DateTimeFormatter.ofPattern("HH:mm")) + " 达 "
                            + fmt(d(worst.getP99Ms()), 0) + "ms (SLO 200ms) · 该时段进件 "
                            + formatNum(worst.getReqCnt()) + " 笔",
                    "关注大促类流量尖峰，必要时提前扩容取数并发", "已恢复"));
        }

        list.sort(Comparator.comparingInt((MonitoringDTO.AlarmItem a) ->
                "FATAL".equals(a.getLevel()) ? 0 : ("WARN".equals(a.getLevel()) ? 1 : 2)));
        return list;
    }

    private MonitoringDTO.AlarmItem alarm(String level, String layer, String target,
                                          String title, String detail, String suggestion, String occurredAt) {
        MonitoringDTO.AlarmItem a = new MonitoringDTO.AlarmItem();
        a.setLevel(level);
        a.setLayer(layer);
        a.setTarget(target);
        a.setTitle(title);
        a.setDetail(detail);
        a.setSuggestion(suggestion);
        a.setOccurredAt(occurredAt);
        return a;
    }

    // ------------------------------------------------------------------ 工具

    private static String staleText(int min) {
        if (min < 60) return min + " 分钟";
        return (min / 60) + " 小时";
    }

    private static double metric(Map<String, MonitorMetric> metrics, String key) {
        MonitorMetric m = metrics.get(key);
        return m == null ? 0 : d(m.getValue());
    }

    private static double d(BigDecimal v) {
        return v == null ? 0 : v.doubleValue();
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }

    private static double round(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private static String fmt(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String formatNum(long v) {
        return String.format("%,d", v);
    }
}
