package com.riskstrategy.service;

import com.riskstrategy.dto.DegradeSourceDTO;
import com.riskstrategy.dto.TrackDTO;
import com.riskstrategy.dto.TrackReplayDTO;
import com.riskstrategy.dto.TrackStatsDTO;
import com.riskstrategy.entity.TrackRecord;
import com.riskstrategy.entity.TrackSamplingConfig;
import com.riskstrategy.entity.TrackStep;
import com.riskstrategy.entity.TrackVar;
import com.riskstrategy.mapper.TrackRecordMapper;
import com.riskstrategy.mapper.TrackSamplingConfigMapper;
import com.riskstrategy.mapper.TrackStepMapper;
import com.riskstrategy.mapper.TrackVarMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Track 链路追踪引擎。
 *
 * 每次策略调用都会：模拟执行完整决策链路 → 记录逐节点耗时与变量快照 →
 * 持久化为不可变的 Trace。链路数据用于「决策日志与回放」页面可视化。
 *
 * 决策口径（与 UI 设计稿一致）：
 *   overdue_cnt ≥ 3            → H 高风险 → REJECT/RISK_OVERDUE_HIGH(R007) · 短路跳过交叉树
 *   else risk_score ≥ 800      → REJECT/RISK_OVER_LIMIT(R015 高负债) · 走交叉树
 *   else                       → REVIEW/RISK_SCORE_MEDIUM(交叉决策树 B2)
 */
@Service
public class TrackService {

    private final TrackRecordMapper recordMapper;
    private final TrackStepMapper stepMapper;
    private final TrackVarMapper varMapper;
    private final TrackSamplingConfigMapper samplingConfigMapper;

    public TrackService(TrackRecordMapper recordMapper,
                        TrackStepMapper stepMapper,
                        TrackVarMapper varMapper,
                        TrackSamplingConfigMapper samplingConfigMapper) {
        this.recordMapper = recordMapper;
        this.stepMapper = stepMapper;
        this.varMapper = varMapper;
        this.samplingConfigMapper = samplingConfigMapper;
    }

    /**
     * 在线调用：执行并记录一条完整链路。
     */
    public TrackDTO invoke(Map<String, Object> params) {
        return run(params, "ONLINE", "PROD");
    }

    /**
     * 单笔试算（影子链路）：执行并记录。
     */
    public TrackDTO trial(Map<String, Object> params) {
        return run(params, "TRIAL", "PROD_SHADOW");
    }

    /**
     * 回放：用某条历史 Trace 的入参重跑，返回新 Trace 并对比原 Trace。
     */
    public TrackReplayDTO replay(Long id) {
        TrackRecord original = recordMapper.selectById(id);
        if (original == null) throw new RuntimeException("Trace 不存在: " + id);
        Map<String, Object> input = parseInput(original.getInputJson());
        TrackDTO current = run(input, "REPLAY", original.getEnv());

        double before = original.getTotalElapsedMs().doubleValue();
        double after = current.getRecord().getTotalElapsedMs().doubleValue();
        double pct = before == 0 ? 0 : ((after - before) / before * 100);
        pct = new BigDecimal(pct).setScale(1, RoundingMode.HALF_UP).doubleValue();

        Map<String, Object> compared = Map.<String, Object>of(
                "conclusionBefore", original.getConclusion(),
                "conclusionAfter", current.getRecord().getConclusion(),
                "sameConclusion", original.getConclusion().equals(current.getRecord().getConclusion()),
                "elapsedBeforeMs", before,
                "elapsedAfterMs", after,
                "deltaPct", pct
        );
        return new TrackReplayDTO(current, original, compared);
    }

    public List<TrackRecord> recent() {
        return recordMapper.selectTop50ByCreatedAtDesc();
    }

    public List<TrackRecord> search(String traceId, Long strategyId, String conclusion, String version) {
        String t = (traceId == null || traceId.isBlank()) ? null : traceId.trim();
        String c = (conclusion == null || conclusion.isBlank()) ? null : conclusion.trim();
        String v = (version == null || version.isBlank()) ? null : version.trim();
        return recordMapper.search(t, strategyId, c, v);
    }

    public TrackDTO detail(Long id) {
        TrackRecord record = recordMapper.selectById(id);
        if (record == null) throw new RuntimeException("Trace 不存在: " + id);
        return assemble(record);
    }

    // ---- gap: stats ----
    public TrackStatsDTO getStats(String range) {
        int minutes = parseRangeMinutes(range);
        TrackStatsDTO dto = new TrackStatsDTO();
        dto.setTotal(recordMapper.countByTimeRange(minutes));
        dto.setDegraded(recordMapper.countDegradedByTimeRange(minutes));
        dto.setSlowRequests(recordMapper.countSlowByTimeRange(minutes, 200));
        dto.setExceptions(recordMapper.countExceptionsByTimeRange(minutes));
        return dto;
    }

    // ---- gap: sampling config ----
    public TrackSamplingConfig getSamplingConfig() {
        List<TrackSamplingConfig> list = samplingConfigMapper.selectList(null);
        return list.isEmpty() ? null : list.get(0);
    }

    public TrackSamplingConfig updateSamplingConfig(TrackSamplingConfig config) {
        TrackSamplingConfig existing = getSamplingConfig();
        if (existing != null) {
            if (config.getRatioSampling() != null) existing.setRatioSampling(config.getRatioSampling());
            if (config.getErrorAutoCapture() != null) existing.setErrorAutoCapture(config.getErrorAutoCapture());
            if (config.getSlowThresholdMs() != null) existing.setSlowThresholdMs(config.getSlowThresholdMs());
            if (config.getRetention() != null) existing.setRetention(config.getRetention());
            existing.setUpdatedAt(java.time.LocalDateTime.now());
            samplingConfigMapper.updateById(existing);
            return existing;
        }
        config.setId(null);
        config.setUpdatedAt(java.time.LocalDateTime.now());
        samplingConfigMapper.insert(config);
        return config;
    }

    // ---- gap: degrade sources ----
    public List<DegradeSourceDTO> getDegradeSources(String range) {
        int minutes = parseRangeMinutes(range);
        List<Map<String, Object>> rows = recordMapper.selectDegradeSources(minutes, 10);
        return rows.stream()
                .map(r -> new DegradeSourceDTO(String.valueOf(r.get("source")), ((Number) r.get("degradeCount")).intValue()))
                .collect(java.util.stream.Collectors.toList());
    }

    private int parseRangeMinutes(String range) {
        if (range == null || range.isBlank()) return 60;
        return switch (range.toLowerCase()) {
            case "5m" -> 5;
            case "15m" -> 15;
            case "30m" -> 30;
            case "1h" -> 60;
            case "6h" -> 360;
            case "24h", "1d" -> 1440;
            case "7d" -> 10080;
            default -> 60;
        };
    }

    // ------------------------------------------------------------------
    // 引擎实现
    // ------------------------------------------------------------------
    private TrackDTO run(Map<String, Object> rawParams, String source, String env) {
        Map<String, Object> input = normalize(rawParams);
        int overdueCnt = asInt(input.get("overdue_cnt"), 3);
        int riskScore = asInt(input.get("risk_score"), 700);
        String level = overdueCnt >= 3 ? "H" : (overdueCnt >= 1 ? "M" : "L");

        boolean highRisk = "H".equals(level) && overdueCnt >= 3;
        String conclusion, reasonCode, hitRule;
        List<String> hitPath;
        if (highRisk) {
            conclusion = "REJECT"; reasonCode = "RISK_OVERDUE_HIGH"; hitRule = "R007 逾期准入";
            hitPath = List.of("n_001", "n_002", "n_003", "n_004", "n_005", "n_007", "n_008");
        } else if (riskScore >= 800) {
            conclusion = "REJECT"; reasonCode = "RISK_OVER_LIMIT"; hitRule = "R015 高负债";
            hitPath = List.of("n_001", "n_002", "n_003", "n_004", "n_006", "n_007", "n_008");
        } else {
            conclusion = "REVIEW"; reasonCode = "RISK_SCORE_MEDIUM"; hitRule = "交叉决策树 B2";
            hitPath = List.of("n_001", "n_002", "n_003", "n_004", "n_006", "n_007", "n_008");
        }

        // 逐节点执行，采集耗时
        List<TrackStep> steps = executeSteps(hitPath, highRisk, level);

        // 汇总类别耗时
        Map<String, Double> byCategory = new LinkedHashMap<>();
        double dataMs = 0, featMs = 0, decisionMs = 0, actionMs = 0;
        for (TrackStep s : steps) {
            double ms = s.getElapsedMs().doubleValue();
            switch (s.getCategory()) {
                case "取数" -> dataMs += ms;
                case "特征" -> featMs += ms;
                case "决策" -> decisionMs += ms;
                case "动作" -> actionMs += ms;
                default -> {}
            }
        }
        double engineOverhead = rand(18, 26);   // 引擎与序列化开销
        double total = dataMs + featMs + decisionMs + actionMs + engineOverhead;
        double engineSelf = engineOverhead + stepMs(steps, "引擎");

        byCategory.put("取数", dataMs);
        byCategory.put("特征", featMs);
        byCategory.put("决策与动作", decisionMs + actionMs);
        byCategory.put("引擎与序列化", engineOverhead);

        // 组装变量快照
        Map<String, Map<String, String>> vars = new LinkedHashMap<>();
        putVar(vars, "param.*", "param.user_id", str(input.get("user_id")), 1);
        putVar(vars, "param.*", "param.apply_amount", str(input.get("apply_amount")), 1);
        putVar(vars, "param.*", "param.channel", str(input.get("channel")), 1);
        putVar(vars, "param.*", "param.risk_score", str(input.get("risk_score")), 1);
        putVar(vars, "view.*", "view.risk_user_info.overdue_cnt", String.valueOf(overdueCnt), 2);
        putVar(vars, "view.*", "view.risk_user_info.mobile", "138****", 2);
        putVar(vars, "view.*", "view.risk_user_info.credit_limit", "50000", 2);
        putVar(vars, "view.*", "view.loan_order_list.apply_amount", str(input.get("apply_amount")), 3);
        putVar(vars, "feat.*", "feat.user_overdue_level", level, 4);
        putVar(vars, "feat.*", "feat.user_apply_cnt_7d", "2", 5);
        putVar(vars, "node.*", "node.n_005.result", conclusion, 7);
        putVar(vars, "node.*", "node.n_005.reasonCode", reasonCode, 7);
        putVar(vars, "node.*", "node.n_005.hit", hitRule, 7);

        // 持久化
        TrackRecord record = new TrackRecord();
        record.setTraceId("tr_" + UUID.randomUUID().toString().substring(0, 8));
        record.setStrategyId(1L);
        record.setStrategyName("还款能力准入策略");
        record.setVersion("v12");
        record.setEnv(env);
        record.setSource(source);
        record.setInputJson(toJson(input));
        record.setConclusion(conclusion);
        record.setReasonCode(reasonCode);
        record.setHitRule(hitRule);
        record.setHitPath(String.join("→", hitPath));
        record.setTotalElapsedMs(BigDecimal.valueOf(total).setScale(1, RoundingMode.HALF_UP));
        record.setEngineElapsedMs(BigDecimal.valueOf(engineSelf).setScale(1, RoundingMode.HALF_UP));
        record.setDegraded(0);
        record.setCreatedAt(LocalDateTime.now());
        recordMapper.insert(record);
        Long trackId = record.getId();

        for (TrackStep s : steps) { s.setTrackId(trackId); }
        stepMapper.insertBatch(steps);
        List<TrackVar> varRows = new ArrayList<>();
        vars.forEach((ns, m) -> m.forEach((k, v) -> {
            TrackVar tv = new TrackVar();
            tv.setTrackId(trackId); tv.setNamespace(ns); tv.setVarName(k); tv.setVarValue(v);
            tv.setStepNo(varStepNo(k));
            varRows.add(tv);
        }));
        varMapper.insertBatch(varRows);

        TrackDTO dto = assemble(record);
        dto.setVariables(vars);
        dto.setInput(input);
        dto.setHitPath(hitPath);
        dto.setResponse(Map.of(
                "conclusion", conclusion,
                "reasonCode", reasonCode,
                "hitRule", hitRule,
                "hitBundle", "sb_v12_r38",
                "traceId", record.getTraceId(),
                "elapsedMs", record.getTotalElapsedMs(),
                "degraded", false
        ));
        dto.setDegraded(false);
        // 类别耗时 → 带百分比
        dto.getTimingBreakdown().clear();
        byCategory.forEach((k, v) -> {
            double pct = total == 0 ? 0 : v / total * 100;
            dto.getTimingBreakdown().put(k, Map.<String, Object>of(
                    "ms", new BigDecimal(v).setScale(1, RoundingMode.HALF_UP),
                    "pct", new BigDecimal(pct).setScale(1, RoundingMode.HALF_UP)));
        });
        return dto;
    }

    /** 根据命中路径生成执行步骤（含类别与耗时，带轻微抖动以区分每次调用） */
    private List<TrackStep> executeSteps(List<String> hitPath, boolean highRisk, String level) {
        List<TrackStep> steps = new ArrayList<>();
        int no = 0;
        steps.add(step(++no, "n_001", "START", "引擎", "START·事件接单·event = LOAN_APPLY", rand(0.2, 0.4)));
        steps.add(step(++no, "n_002", "DATA", "取数", "取数·用户信息·view.risk_user_info·12变量", rand(30, 35)));
        steps.add(step(++no, "n_003", "DATA", "取数", "取数·订单列表·view.loan_order_list·6变量", rand(19, 24)));
        steps.add(step(++no, "n_004", "FEATURE", "特征", "特征·逾期等级·feat.user_overdue_level = " + level, rand(1.6, 2.4)));
        steps.add(step(++no, "n_004", "FEATURE", "特征", "特征·申请次数7d·feat.user_apply_cnt_7d = 2", rand(1.4, 2.2)));
        steps.add(step(++no, "n_005", "BRANCH", "决策", highRisk ? "分支·风险分档→命中条件①高风险" : "分支·风险分档→命中条件②其他情况", rand(0.3, 0.6)));
        if (!highRisk) {
            steps.add(step(++no, "n_006", "DECISION", "决策", "交叉决策树·4×5·命中 B2 格", rand(1.0, 1.8)));
        }
        steps.add(step(++no, "n_007", "ACTION", "动作", highRisk ? "动作·TERMINATE 短路·跳过交叉决策树" : "动作·TERMINATE 组装结论", rand(0.05, 0.2)));
        steps.add(step(++no, "n_008", "END", "引擎", "结束·组装输出·生成Trace与决策日志", rand(1.0, 1.4)));
        return steps;
    }

    private TrackStep step(int no, String nodeId, String type, String category, String title, double ms) {
        TrackStep s = new TrackStep();
        s.setStepNo(no); s.setNodeId(nodeId); s.setNodeType(type); s.setCategory(category);
        s.setStepTitle(title);
        s.setElapsedMs(BigDecimal.valueOf(ms).setScale(1, RoundingMode.HALF_UP));
        return s;
    }

    private TrackDTO assemble(TrackRecord record) {
        TrackDTO dto = new TrackDTO();
        dto.setRecord(record);
        List<TrackStep> steps = stepMapper.selectByTrackIdOrderByStepNo(record.getId());
        dto.setSteps(steps);

        Map<String, Map<String, String>> vars = new LinkedHashMap<>();
        for (TrackVar v : varMapper.selectByTrackIdOrderByStepNo(record.getId())) {
            vars.computeIfAbsent(v.getNamespace(), k -> new LinkedHashMap<>()).put(v.getVarName(), v.getVarValue());
        }
        dto.setVariables(vars);

        Map<String, Double> byCat = new LinkedHashMap<>();
        for (TrackStep s : steps) {
            byCat.merge(s.getCategory(), s.getElapsedMs().doubleValue(), Double::sum);
        }
        double total = record.getTotalElapsedMs().doubleValue();
        byCat.forEach((k, v) -> {
            double pct = total == 0 ? 0 : v / total * 100;
            dto.getTimingBreakdown().put(k, Map.<String, Object>of(
                    "ms", new BigDecimal(v).setScale(1, RoundingMode.HALF_UP),
                    "pct", new BigDecimal(pct).setScale(1, RoundingMode.HALF_UP)));
        });
        dto.setInput(parseInput(record.getInputJson()));
        dto.setHitPath(record.getHitPath() == null ? List.of() : List.of(record.getHitPath().split("→")));
        dto.setResponse(Map.of(
                "conclusion", record.getConclusion(),
                "reasonCode", record.getReasonCode(),
                "hitRule", record.getHitRule(),
                "hitBundle", "sb_v12_r38",
                "traceId", record.getTraceId(),
                "elapsedMs", record.getTotalElapsedMs(),
                "degraded", record.getDegraded() != null && record.getDegraded() == 1
        ));
        dto.setDegraded(record.getDegraded() != null && record.getDegraded() == 1);
        return dto;
    }

    // ---------------- helpers ----------------
    private Map<String, Object> normalize(Map<String, Object> raw) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("user_id", raw.getOrDefault("user_id", "U100000"));
        in.put("apply_amount", raw.getOrDefault("apply_amount", 58000));
        in.put("channel", raw.getOrDefault("channel", "APP_IOS"));
        in.put("risk_score", raw.getOrDefault("risk_score", 700));
        in.put("overdue_cnt", raw.getOrDefault("overdue_cnt", 3));
        return in;
    }

    private Map<String, Object> parseInput(String json) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return m;
        String s = json.trim().replace("{", "").replace("}", "");
        for (String pair : s.split(",")) {
            String[] kv = pair.split(":", 2);
            if (kv.length == 2) m.put(kv[0].replace("\"", "").trim(), kv[1].replace("\"", "").trim());
        }
        return m;
    }

    private int asInt(Object o, int def) {
        if (o == null) return def;
        try { return (int) Double.parseDouble(String.valueOf(o)); }
        catch (Exception e) { return def; }
    }

    private double rand(double min, double max) {
        double v = ThreadLocalRandom.current().nextDouble() * (max - min) + min;
        return new BigDecimal(v).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private double stepMs(List<TrackStep> steps, String category) {
        return steps.stream().filter(s -> category.equals(s.getCategory()))
                .mapToDouble(s -> s.getElapsedMs().doubleValue()).sum();
    }

    private void putVar(Map<String, Map<String, String>> vars, String ns, String k, String v, int stepNo) {
        vars.computeIfAbsent(ns, x -> new LinkedHashMap<>()).put(k, v == null ? "" : v);
    }

    private int varStepNo(String name) {
        if (name.startsWith("param")) return 1;
        if (name.startsWith("view.risk_user_info")) return 2;
        if (name.startsWith("view.loan_order_list")) return 3;
        if (name.startsWith("feat.user_overdue_level")) return 4;
        if (name.startsWith("feat.user_apply_cnt_7d")) return 5;
        return 7;
    }

    private String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private String toJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        m.forEach((k, v) -> sb.append('"').append(k).append("\":\"").append(v).append("\","));
        if (sb.length() > 1) sb.setLength(sb.length() - 1);
        return sb.append('}').toString();
    }
}
