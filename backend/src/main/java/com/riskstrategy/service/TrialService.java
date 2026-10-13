package com.riskstrategy.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskstrategy.common.StrategyStatus;
import com.riskstrategy.dto.FeatureTrialDTO;
import com.riskstrategy.dto.ResolvedInputDTO;
import com.riskstrategy.dto.ShadowCompareDTO;
import com.riskstrategy.dto.TrialCaseDTO;
import com.riskstrategy.dto.TrialCaseResultDTO;
import com.riskstrategy.dto.TrialDTO;
import com.riskstrategy.dto.TrialRoutingDTO;
import com.riskstrategy.dto.TrialStrategyOptionDTO;
import com.riskstrategy.engine.ConditionEvaluator;
import com.riskstrategy.entity.DecisionNode;
import com.riskstrategy.entity.Feature;
import com.riskstrategy.entity.Strategy;
import com.riskstrategy.entity.StrategyVersion;
import com.riskstrategy.entity.TrackRecord;
import com.riskstrategy.entity.TrackStep;
import com.riskstrategy.entity.TrackVar;
import com.riskstrategy.entity.TrialCase;
import com.riskstrategy.entity.TrialExecutionStep;
import com.riskstrategy.entity.TrialRecord;
import com.riskstrategy.mapper.DecisionNodeMapper;
import com.riskstrategy.mapper.FeatureMapper;
import com.riskstrategy.mapper.StrategyMapper;
import com.riskstrategy.mapper.StrategyVersionMapper;
import com.riskstrategy.mapper.TrackRecordMapper;
import com.riskstrategy.mapper.TrackStepMapper;
import com.riskstrategy.mapper.TrackVarMapper;
import com.riskstrategy.mapper.TrialCaseMapper;
import com.riskstrategy.mapper.TrialExecutionStepMapper;
import com.riskstrategy.mapper.TrialRecordMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 试算中心。
 *
 * <p>试算必须能<b>选定策略</b>跑：请求里带 {@code strategy_code}（推荐，如 loan_apply）或
 * {@code strategy_id}，可选 {@code version_id} / {@code version_no} 指定版本；不指定版本时
 * 自动挑选该策略下「配置最完整」的版本（登记了决策图 > 状态更靠线上 > rev 更新）。
 *
 * <p>结论不是写死的：优先按目标版本登记的 {@code decision_node} 逐条真实求值（见
 * {@link ConditionEvaluator}），命中哪个节点就用该节点的 conclusion / reason_code；
 * 版本没有可求值节点时回落到默认准入规则集，并在返回报文里标记 {@code execSource=DEFAULT_RULES}，
 * 让使用者清楚"这条结论是编排图给的，还是兜底规则给的"。
 */
@Service
public class TrialService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 控制参数：参与调度但不进变量表，避免污染条件求值的命名空间 */
    private static final List<String> CONTROL_KEYS = List.of(
            "strategy_id", "strategyId", "strategy_code", "strategyCode",
            "version_id", "versionId", "version_no", "versionNo", "version", "debug");

    /** 步骤耗时模板：固定值而非随机，保证同一输入的试算结果可复现、用例比对不抖动 */
    private static final double[] STEP_MS = {0.3, 32.4, 21.7, 2.1, 1.8, 0.4, 1.2, 0.1, 1.2, 0.6, 0.9, 0.5};

    /** 决策表达式里的命名空间引用：param./c. = 策略入参，feat./f. = 特征引用（与「特征改名」扫描口径一致） */
    private static final Pattern EXPR_REF =
            Pattern.compile("(?<![A-Za-z0-9_.@])(param|c|feat|f)\\.([A-Za-z_][A-Za-z0-9_]*)");

    /** FEATURE 节点表达式最左侧标识符：裸写的特征编码（如 user_overdue_level = H） */
    private static final Pattern LEADING_IDENT = Pattern.compile("^\\s*([A-Za-z_][A-Za-z0-9_]*)");

    private static final Set<String> NAMESPACE_HEADS = Set.of("param", "c", "feat", "f");

    private final TrialRecordMapper recordMapper;
    private final TrialExecutionStepMapper stepMapper;
    private final TrialCaseMapper caseMapper;
    private final StrategyMapper strategyMapper;
    private final StrategyVersionMapper versionMapper;
    private final DecisionNodeMapper nodeMapper;
    private final FeatureService featureService;
    private final FeatureMapper featureMapper;
    private final TrafficRouter trafficRouter;
    /** 试算同步落全链路 trace：让「试出来的结果」能在决策日志里逐节点回看 */
    private final TrackRecordMapper trackRecordMapper;
    private final TrackStepMapper trackStepMapper;
    private final TrackVarMapper trackVarMapper;

    public TrialService(TrialRecordMapper recordMapper,
                        TrialExecutionStepMapper stepMapper,
                        TrialCaseMapper caseMapper,
                        StrategyMapper strategyMapper,
                        StrategyVersionMapper versionMapper,
                        DecisionNodeMapper nodeMapper,
                        FeatureService featureService,
                        FeatureMapper featureMapper,
                        TrafficRouter trafficRouter,
                        TrackRecordMapper trackRecordMapper,
                        TrackStepMapper trackStepMapper,
                        TrackVarMapper trackVarMapper) {
        this.recordMapper = recordMapper;
        this.stepMapper = stepMapper;
        this.caseMapper = caseMapper;
        this.strategyMapper = strategyMapper;
        this.versionMapper = versionMapper;
        this.nodeMapper = nodeMapper;
        this.featureService = featureService;
        this.featureMapper = featureMapper;
        this.trafficRouter = trafficRouter;
        this.trackRecordMapper = trackRecordMapper;
        this.trackStepMapper = trackStepMapper;
        this.trackVarMapper = trackVarMapper;
    }

    /* ==================================================================== */
    /*  试算执行                                                             */
    /* ==================================================================== */

    public TrialDTO execute(Map<String, Object> params) {
        Map<String, Object> p = params == null ? new LinkedHashMap<>() : params;

        Strategy strategy = resolveStrategy(p);
        // 未指定版本 = 自动：先按切流配置判定命中版本，再运行它；手动指定版本则跳过判定（routing 为 null）
        TrialRoutingDTO routing = null;
        StrategyVersion version;
        if (longOf(p, "version_id", "versionId") == null && str(p, "version_no", "versionNo", "version") == null) {
            routing = trafficRouter.route(strategy.getId(), p);
            version = routing.getVersionId() == null ? null : versionMapper.selectById(routing.getVersionId());
            if (version == null) version = resolveVersion(strategy, p);
        } else {
            version = resolveVersion(strategy, p);
        }
        List<DecisionNode> nodes = version == null
                ? List.of() : nodeMapper.selectByVersionIdOrderById(version.getId());
        Map<String, Object> vars = buildVars(p);
        // 决策图引用到的特征真实执行，出参回填 feat.*（引擎失败才保留内置派生兜底）
        List<Map<String, Object>> featureRuns = computeFeatures(nodes, p, vars);

        // ---- 真实求值：按 priority 升序遍历可求值的决策节点，命中即短路 ----
        DecisionNode hit = null;
        List<DecisionNode> decisions = nodes.stream()
                .filter(n -> notBlank(n.getConditionExpr()) && notBlank(n.getConclusion()))
                .sorted(Comparator.comparingInt(n -> n.getPriority() == null ? Integer.MAX_VALUE : n.getPriority()))
                .collect(Collectors.toList());
        for (DecisionNode n : decisions) {
            if (ConditionEvaluator.isTrue(n.getConditionExpr(), vars)) {
                hit = n;
                break;
            }
        }

        String conclusion;
        String reasonCode;
        String hitRule;
        String execSource;
        if (hit != null) {
            conclusion = hit.getConclusion();
            reasonCode = hit.getReasonCode() == null ? "GRAPH_HIT" : hit.getReasonCode();
            hitRule = hit.getNodeId() + " " + (hit.getTitle() == null ? "" : hit.getTitle()).trim();
            execSource = "GRAPH";
        } else {
            DefaultRule r = defaultRule(p);
            conclusion = r.conclusion;
            reasonCode = r.reasonCode;
            hitRule = r.hitRule;
            execSource = nodes.isEmpty() ? "DEFAULT_RULES" : "DEFAULT_RULES_NO_MATCH";
        }

        // ---- 落库 ----
        List<String> stepTitles = nodes.isEmpty()
                ? fallbackStepTitles(vars, conclusion, reasonCode)
                : graphStepTitles(nodes, vars, hit);

        TrialRecord record = new TrialRecord();
        record.setTraceId("tr_" + UUID.randomUUID().toString().substring(0, 8));
        record.setStrategyId(strategy.getId());
        String versionNo = version != null ? version.getVersionNo() : str(p, "version_no", "versionNo", "version");
        record.setVersion(versionNo == null ? "—" : versionNo);
        record.setInputJson(toJson(p));
        record.setEnv("PROD_SHADOW");
        record.setDegraded(0);
        record.setConclusion(conclusion);
        record.setReasonCode(reasonCode);
        record.setHitRule(hitRule);

        double totalMs = 0;
        for (int i = 0; i < stepTitles.size(); i++) totalMs += STEP_MS[Math.min(i, STEP_MS.length - 1)];
        // 总耗时 = 节点模板耗时合计 + 特征引擎真实执行耗时合计
        for (Map<String, Object> run : featureRuns) {
            if (Boolean.TRUE.equals(run.get("success"))) totalMs += numOf(run.get("execElapsedMs"), run.get("elapsedMs"));
        }
        record.setElapsedMs(BigDecimal.valueOf(totalMs).setScale(1, RoundingMode.HALF_UP));
        recordMapper.insert(record);

        List<TrialExecutionStep> steps = new ArrayList<>();
        for (int i = 0; i < stepTitles.size(); i++) {
            steps.add(step(i + 1, stepTitles.get(i), STEP_MS[Math.min(i, STEP_MS.length - 1)], record));
        }

        TrialDTO dto = new TrialDTO(record, steps);
        dto.setRouting(routing);
        dto.setFeatureRuns(featureRuns);
        dto.setNodeDetails(buildNodeDetails(nodes, vars, hit, featureRuns));

        // ---- 变量快照：按命名空间分组，与表达式里的写法一一对应 ----
        Map<String, Object> viewVars = new LinkedHashMap<>();
        Map<String, Object> featVars = new LinkedHashMap<>();
        Map<String, Object> paramVars = new LinkedHashMap<>();
        vars.forEach((k, v) -> {
            if (k.startsWith("view.")) viewVars.put(k, v);
            else if (k.startsWith("feat.")) featVars.put(k, v);
            else if (k.startsWith("param.")) paramVars.put(k, v);
        });
        Map<String, Object> nodeVars = new LinkedHashMap<>();
        if (hit != null) {
            nodeVars.put("node." + hit.getNodeId() + ".conclusion", conclusion);
            nodeVars.put("node." + hit.getNodeId() + ".reasonCode", reasonCode);
        } else {
            nodeVars.put("node.defaultRules.conclusion", conclusion);
        }
        dto.getVariableSnapshot().put("view.* 视图变量", viewVars);
        dto.getVariableSnapshot().put("feat.* 特征变量", featVars);
        dto.getVariableSnapshot().put("param.* 决策入参", paramVars);
        dto.getVariableSnapshot().put("node.* 节点输出", nodeVars);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("strategyId", strategy.getId());
        resp.put("strategyCode", strategy.getCode());
        resp.put("strategyName", strategy.getName());
        resp.put("versionId", version == null ? null : version.getId());
        resp.put("version", record.getVersion());
        resp.put("execSource", execSource);
        resp.put("hitNode", hit == null ? null : hit.getNodeId());
        resp.put("conclusion", conclusion);
        resp.put("reasonCode", reasonCode);
        resp.put("hitBundle", version == null ? null : version.getBundle());
        resp.put("traceId", record.getTraceId());
        resp.put("elapsedMs", record.getElapsedMs());
        resp.put("degraded", false);
        dto.setResponse(resp);

        // ---- 同步落一条全链路 trace：与试算记录共用 traceId，可在决策日志里逐节点回看 ----
        try {
            Long trackId = writeTrace(record, strategy, version, nodes, decisions, hit, vars,
                    featureRuns, conclusion, reasonCode, hitRule, p);
            dto.setTrackId(trackId);
            dto.setTraceId(record.getTraceId());
        } catch (RuntimeException ex) {
            // 留痕失败不能让试算本身失败：结果照常返回，只是少了可回溯的链路
            dto.setTrackId(null);
            dto.setTraceId(record.getTraceId());
        }
        return dto;
    }

    /* ==================================================================== */
    /*  全链路留痕：试算结果写成一条可回放的 trace                            */
    /* ==================================================================== */

    /**
     * 把这一次试算的真实执行过程写成一条 {@code source=TRIAL} 的 trace：
     * START → 特征计算（逐特征，耗时取引擎真实耗时）→ 决策节点（按优先级，命中即短路）→ ACTION → END，
     * 并把变量快照按 {@code param.* / view.* / feat.* / node.*} 落进 track_var。
     *
     * <p>traceId 与试算记录一致，使用者拿它去决策日志页检索即可看到同一条链路。
     */
    private Long writeTrace(TrialRecord trial, Strategy strategy, StrategyVersion version,
                            List<DecisionNode> nodes, List<DecisionNode> decisions, DecisionNode hit,
                            Map<String, Object> vars, List<Map<String, Object>> featureRuns,
                            String conclusion, String reasonCode, String hitRule, Map<String, Object> p) {
        List<TrackStep> steps = new ArrayList<>();
        int no = 0;
        String vNo = version != null && notBlank(version.getVersionNo())
                ? version.getVersionNo() : String.valueOf(trial.getVersion());

        steps.add(tStep(++no, "n_start", "START", "引擎",
                "START·试算接单·" + strategy.getCode() + " · " + vNo + " · " + nodes.size() + " 节点", 0.3));

        // ---- 特征段：耗时取引擎真实执行耗时 ----
        int firstFeatStep = 1;
        double featMs = 0;
        for (Map<String, Object> run : featureRuns) {
            String code = String.valueOf(run.get("code"));
            double ms = numOf(run.get("execElapsedMs"), run.get("elapsedMs"));
            featMs += ms;
            if (firstFeatStep == 1) firstFeatStep = no + 1;
            boolean ok = Boolean.TRUE.equals(run.get("success"));
            String title = ok
                    ? "特征·" + code + " = " + textOf(vars.get("feat." + code))
                    + "（" + textOf(run.get("engine")) + "引擎真实执行）"
                    : "特征·" + code + "·未计算成功：" + textOf(run.get("error")) + "（按内置派生兜底）";
            steps.add(tStep(++no, "feat_" + code, "FEATURE", "特征", title, ms));
        }

        // ---- 决策段：按优先级求值，命中即短路（后续节点标 SKIPPED，与引擎语义一致） ----
        int hitStepNo = 0;
        boolean hitDone = false;
        if (decisions.isEmpty()) {
            hitStepNo = no + 1;
            steps.add(tStep(++no, "n_default", "DECISION", "决策",
                    "决策·默认准入规则集·" + hitRule + " → " + conclusion, 0.4));
        }
        for (DecisionNode n : decisions) {
            boolean isHit = hit != null && Objects.equals(n.getId(), hit.getId());
            String result;
            double ms;
            if (hitDone) {
                result = "SKIPPED（前序已命中，短路跳过）";
                ms = 0;
            } else if (isHit) {
                result = "TRUE·命中并短路";
                ms = 0.4;
                hitDone = true;
            } else {
                result = "FALSE";
                ms = 0.4;
            }
            int stepNo = ++no;
            if (isHit) hitStepNo = stepNo;
            steps.add(tStep(stepNo, n.getNodeId(), "DECISION", "决策",
                    "决策·" + n.getNodeId() + " " + (n.getTitle() == null ? "" : n.getTitle().trim())
                            + "·条件 " + n.getConditionExpr() + " → " + result, ms));
        }

        steps.add(tStep(++no, hit == null ? "n_default" : hit.getNodeId(), "ACTION", "动作",
                "动作·组装结论 → " + conclusion + " · " + reasonCode, 0.2));
        int actionStepNo = no;
        steps.add(tStep(++no, "n_end", "END", "引擎", "结束·生成 Trace 与决策日志", 1.0));

        // ---- 落库 ----
        double totalMs = trial.getElapsedMs() == null ? 0 : trial.getElapsedMs().doubleValue();
        double engineMs = totalMs - featMs;
        if (engineMs < 0.5) engineMs = 0.5;

        TrackRecord t = new TrackRecord();
        t.setTraceId(trial.getTraceId());
        t.setStrategyId(strategy.getId());
        t.setStrategyName(strategy.getName());
        t.setVersion(vNo);
        t.setEnv("PROD_SHADOW");
        t.setSource("TRIAL");
        t.setInputJson(trial.getInputJson());
        t.setConclusion(conclusion);
        t.setReasonCode(reasonCode);
        t.setHitRule(hitRule);
        t.setHitPath(steps.stream().map(TrackStep::getNodeId).collect(Collectors.joining("→")));
        t.setTotalElapsedMs(BigDecimal.valueOf(totalMs).setScale(1, RoundingMode.HALF_UP));
        t.setEngineElapsedMs(BigDecimal.valueOf(engineMs).setScale(1, RoundingMode.HALF_UP));
        t.setDegraded(0);
        t.setChannel(str(p, "channel"));
        t.setBusinessKey(str(p, "user_id", "business_key", "businessKey"));
        t.setSamplingReason("试算中心·全链路留痕");
        t.setCreatedAt(LocalDateTime.now());
        trackRecordMapper.insert(t);
        Long trackId = t.getId();

        for (TrackStep s : steps) s.setTrackId(trackId);
        trackStepMapper.insertBatch(steps);

        List<TrackVar> varRows = new ArrayList<>();
        int featStep = firstFeatStep == 1 ? 2 : firstFeatStep;
        int nodeStep = hitStepNo == 0 ? actionStepNo : hitStepNo;
        vars.forEach((k, v) -> {
            if (v == null) return;
            String ns;
            int stepNo;
            if (k.startsWith("param.")) { ns = "param.*"; stepNo = 1; }
            else if (k.startsWith("view.")) { ns = "view.*"; stepNo = 1; }
            else if (k.startsWith("feat.")) { ns = "feat.*"; stepNo = featStep; }
            else if (k.startsWith("node.")) { ns = "node.*"; stepNo = nodeStep; }
            else return;
            TrackVar tv = new TrackVar();
            tv.setTrackId(trackId);
            tv.setNamespace(ns);
            tv.setVarName(k);
            tv.setVarValue(String.valueOf(v));
            tv.setStepNo(stepNo);
            varRows.add(tv);
        });
        if (!varRows.isEmpty()) trackVarMapper.insertBatch(varRows);
        return trackId;
    }

    private static TrackStep tStep(int no, String nodeId, String type, String category, String title, double ms) {
        TrackStep s = new TrackStep();
        s.setStepNo(no);
        s.setNodeId(nodeId);
        s.setNodeType(type);
        s.setCategory(category);
        s.setStepTitle(title);
        s.setElapsedMs(BigDecimal.valueOf(ms).setScale(1, RoundingMode.HALF_UP));
        return s;
    }

    /* ==================================================================== */
    /*  解析入参：按决策图引用自动生成试算输入模板                            */
    /* ==================================================================== */

    /**
     * 扫描所选版本决策图的引用变量清单（{@code ref_vars_json} 里的 param.* / feat.*）与条件表达式兜底，
     * 解析出「策略自定义入参」与「被引用特征的入参」（复用 {@link FeatureService#resolvedInputsOf}，
     * 含 @@ 链展开与「入参定义」里的用户调整），合成试算输入 JSON 模板。
     *
     * <p>纯查询：不触发试算、不写任何表。特征编码查找兼容历史写法：先按去前缀查找，找不到再用全名查找；
     * 已下线特征（offline 只置 status=OFFLINE 并把 deleted 置为自身 id）也能查到并解析入参，但会进 warnings。
     *
     * <p>目标版本未编排决策图时（如默认态命中未编排的线上版本），输入模板回退按「配置最完整带图版本」
     * 的引用解析并挂 ⚠——试算执行语义不变（仍跑命中版本的默认规则集）。
     */
    public Map<String, Object> resolveInputs(Map<String, Object> params) {
        Map<String, Object> p = params == null ? new LinkedHashMap<>() : params;
        Strategy strategy = resolveStrategy(p);
        // 未指定版本 = 自动：与 execute 同一判定算法，解析「本轮将命中版本」的入参；
        // body 里可嵌套 params（当前试算入参）供判定读取 user_id / channel / user_tag
        TrialRoutingDTO routing = null;
        StrategyVersion version;
        if (longOf(p, "version_id", "versionId") == null && str(p, "version_no", "versionNo", "version") == null) {
            routing = trafficRouter.route(strategy.getId(), subParamsOf(p));
            version = routing.getVersionId() == null ? null : versionMapper.selectById(routing.getVersionId());
            if (version == null) version = resolveVersion(strategy, p);
        } else {
            version = resolveVersion(strategy, p);
        }
        List<DecisionNode> nodes = version == null
                ? List.of() : nodeMapper.selectByVersionIdOrderById(version.getId());
        boolean emptyGraph = nodes.isEmpty();
        List<String> warnings = new ArrayList<>();

        // 目标版本未编排决策图：输入模板回退按「配置最完整带图版本」的引用解析——
        // 否则默认态（如自动命中未编排的线上版本）会解析出空模板，按钮失去意义；执行语义不变。
        if (emptyGraph) {
            StrategyVersion graphVersion = pickGraphVersion(strategy, version);
            if (graphVersion != null) {
                nodes = nodeMapper.selectByVersionIdOrderById(graphVersion.getId());
                warnings.add("版本 " + (version == null ? "—" : version.getVersionNo())
                        + " 未编排决策图（试算按默认规则集执行）；输入模板已按带图版本 "
                        + graphVersion.getVersionNo() + " 的引用解析。");
            } else {
                warnings.add("该策略各版本均未编排决策图，无法解析引用入参（试算按默认规则集执行）。");
            }
        }

        RefScan scan = scanRefs(nodes);
        Map<String, LinkedHashSet<String>> paramRefs = scan.paramRefs;
        Map<String, LinkedHashSet<String>> featRefs = scan.featRefs;
        Map<String, String> featRaw = scan.featRaw;

        // ---- 特征入参解析 ----
        List<Map<String, Object>> features = new ArrayList<>();
        Map<String, Object> featValues = new LinkedHashMap<>();
        int featureInputCount = 0;

        for (Map.Entry<String, LinkedHashSet<String>> e : featRefs.entrySet()) {
            String code = e.getKey();
            Feature f = lookUpFeature(code, featRaw.getOrDefault(code, code));
            if (f == null) {
                warnings.add("引用特征「" + code + "」不存在或已删除（引用节点：" + String.join("、", e.getValue()) + "），请在编排页修正");
                continue;
            }
            List<ResolvedInputDTO> inputs = new ArrayList<>();
            try {
                Map<String, Object> resolved = featureService.resolvedInputsOf(f);
                Object rawInputs = resolved.get("inputs");
                if (rawInputs instanceof List<?> list) {
                    for (Object o : list) if (o instanceof ResolvedInputDTO d) inputs.add(d);
                }
            } catch (RuntimeException ex) {
                warnings.add("特征「" + code + "」入参解析失败：" + ex.getMessage());
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", code);
            item.put("featureId", f.getId());
            item.put("name", f.getName());
            item.put("status", f.getStatus());
            item.put("refNodes", List.copyOf(e.getValue()));
            item.put("inputs", inputs);
            features.add(item);
            featureInputCount += inputs.size();
            for (ResolvedInputDTO in : inputs) {
                String key = flatName(in.getName());
                if (!key.isEmpty()) featValues.putIfAbsent(key, templateValue(in));
            }
            if ("OFFLINE".equalsIgnoreCase(String.valueOf(f.getStatus()))) {
                warnings.add("特征「" + code + "」已下线，试算时不会计算它（按缺失值策略处理）");
            }
        }

        // ---- 模板：策略入参先占位（无类型信息给空串），特征入参的默认值覆盖同名键 ----
        Map<String, Object> template = new LinkedHashMap<>();
        for (String name : paramRefs.keySet()) template.put(name, "");
        template.putAll(featValues);

        List<Map<String, Object>> paramList = new ArrayList<>();
        for (Map.Entry<String, LinkedHashSet<String>> e : paramRefs.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", e.getKey());
            item.put("refNodes", List.copyOf(e.getValue()));
            paramList.add(item);
        }

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("paramCount", paramRefs.size());
        counts.put("featureCount", features.size());
        counts.put("featureInputCount", featureInputCount);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("strategyId", strategy.getId());
        out.put("strategyCode", strategy.getCode());
        out.put("strategyName", strategy.getName());
        out.put("versionId", version == null ? null : version.getId());
        out.put("version", version == null ? null : version.getVersionNo());
        out.put("nodeCount", nodes.size());
        out.put("params", paramList);
        out.put("features", features);
        out.put("template", template);
        out.put("counts", counts);
        out.put("warnings", warnings);
        out.put("emptyGraph", emptyGraph);
        out.put("routing", routing);
        return out;
    }

    private static void addRef(Map<String, LinkedHashSet<String>> refs, String name, String label) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return;
        refs.computeIfAbsent(n, k -> new LinkedHashSet<>()).add(label);
    }

    private static void addFeatRef(Map<String, LinkedHashSet<String>> refs, Map<String, String> raws,
                                   String name, String raw, String label) {
        addRef(refs, name, label);
        String n = name == null ? "" : name.trim();
        if (!n.isEmpty()) raws.putIfAbsent(n, raw);
    }

    /** 先按在线特征查找（去前缀 → 全名），都没找到时兜底查含已下线的（同样两种写法） */
    private Feature lookUpFeature(String code, String raw) {
        Feature f = featureMapper.selectByCode(code);
        if (f == null && !raw.equals(code)) f = featureMapper.selectByCode(raw);
        if (f == null) f = featureMapper.selectByCodeIncludeOffline(code);
        if (f == null && !raw.equals(code)) f = featureMapper.selectByCodeIncludeOffline(raw);
        return f;
    }

    private static String nodeLabel(DecisionNode n) {
        String title = n.getTitle() == null ? "" : n.getTitle().trim();
        return title.isEmpty() ? String.valueOf(n.getNodeId()) : n.getNodeId() + " " + title;
    }

    private List<String> refVarsOf(DecisionNode n) {
        String json = n.getRefVarsJson();
        if (!notBlank(json)) return List.of();
        try {
            List<String> list = JSON.readValue(json, new TypeReference<List<String>>() {});
            return list == null ? List.of() : list;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 特征入参名拍平：决策入参来源的「param.xxx」在试算 JSON 里就是 xxx（buildVars 会自动补 param. 前缀） */
    private static String flatName(String name) {
        String n = name == null ? "" : name.trim();
        return n.startsWith("param.") ? n.substring(6) : n;
    }

    /** 模板占位值：默认值 → 建议值 → 按类型占位（数值 0 / 布尔 false / 其余空串） */
    private static Object templateValue(ResolvedInputDTO in) {
        String type = String.valueOf(in.getType() == null ? "" : in.getType()).toUpperCase(Locale.ROOT);
        boolean numeric = type.contains("INT") || type.contains("DECIMAL") || type.contains("NUMBER")
                || type.contains("LONG") || type.contains("DOUBLE") || type.contains("FLOAT");
        boolean bool = type.contains("BOOL");
        String v = notBlank(in.getDefaultValue()) ? in.getDefaultValue().trim()
                : (in.getSampleValue() == null ? "" : in.getSampleValue().trim());
        if (!v.isEmpty()) {
            if (numeric) {
                try {
                    return new BigDecimal(v);
                } catch (NumberFormatException ignore) {
                    return v;
                }
            }
            if (bool) return "true".equalsIgnoreCase(v) || "1".equals(v);
            return v;
        }
        if (numeric) return BigDecimal.ZERO;
        if (bool) return false;
        return "";
    }

    /* ==================================================================== */
    /*  特征真实计算（决策图引用 → 四引擎真实执行 → 回填 feat.*）            */
    /* ==================================================================== */

    /**
     * 扫描决策图引用到的特征并真实执行（复用特征模块 trial 链路），出参按「引用名口径」
     * 回填 feat.* 参与条件求值；特征缺失 / 已下线 / 引擎失败才保留内置派生兜底并挂 ⚠。
     * 明细结构见 docs/trial-traffic-route.md §4.3。
     */
    private List<Map<String, Object>> computeFeatures(List<DecisionNode> nodes, Map<String, Object> p,
                                                      Map<String, Object> vars) {
        List<Map<String, Object>> runs = new ArrayList<>();
        if (nodes.isEmpty()) return runs;

        RefScan scan = scanRefs(nodes);
        for (Map.Entry<String, LinkedHashSet<String>> e : scan.featRefs.entrySet()) {
            String code = e.getKey();
            List<String> warns = new ArrayList<>();
            Map<String, Object> run = new LinkedHashMap<>();
            run.put("code", code);
            run.put("refNodes", List.copyOf(e.getValue()));
            run.put("success", false);
            run.put("fallback", true);
            run.put("warnings", warns);

            Feature f = lookUpFeature(code, scan.featRaw.getOrDefault(code, code));
            if (f == null) {
                run.put("error", "特征不存在或已删除");
                warns.add("引用特征「" + code + "」不存在或已删除（引用节点：" + String.join("、", e.getValue())
                        + "），按内置派生兜底");
                runs.add(run);
                continue;
            }
            run.put("featureId", f.getId());
            run.put("name", f.getName());
            run.put("engine", notBlank(f.getComputeType()) ? f.getComputeType().trim() : null);
            if ("OFFLINE".equalsIgnoreCase(String.valueOf(f.getStatus()))) {
                run.put("error", "特征已下线，不参与计算");
                warns.add("特征「" + code + "」已下线，试算不计算它（按内置派生兜底）");
                runs.add(run);
                continue;
            }

            try {
                FeatureTrialDTO sub = featureService.trial(f.getId(), flatValues(p));
                if (sub.getEngine() != null) run.put("engine", sub.getEngine());
                run.put("elapsedMs", sub.getElapsedMs());
                run.put("execElapsedMs", sub.getExecElapsedMs());
                run.put("refValues", sub.getRefValues());
                run.put("exprPreview", sub.getExprPreview());
                run.put("outputs", outputsOf(sub));
                run.put("valuesUsed", valuesUsedOf(sub, p));
                if (sub.getWarnings() != null) warns.addAll(sub.getWarnings());
                if (Boolean.TRUE.equals(sub.getSuccess())) {
                    run.put("success", true);
                    run.put("fallback", false);
                    Object primary = primaryValue(sub);
                    if (primary != null) {
                        vars.put("feat." + code, primary);
                        // 裸写引用（如 user_overdue_level = H）与 feat. 写法保持一致
                        if (vars.containsKey(code)) vars.put(code, primary);
                    }
                    List<Map<String, Object>> outs = sub.getOutputResults();
                    if (outs != null) {
                        for (Map<String, Object> o : outs) {
                            if (Boolean.TRUE.equals(o.get("missing"))) continue;
                            String outName = String.valueOf(o.get("name"));
                            if (notBlank(outName) && !outName.equals(code)) {
                                vars.put("feat." + outName, o.get("value"));
                            }
                        }
                    }
                } else {
                    run.put("error", sub.getError());
                    warns.add("特征「" + code + "」引擎未执行成功：" + textOf(sub.getError()) + "，按内置派生兜底");
                }
            } catch (RuntimeException ex) {
                run.put("error", ex.getMessage());
                warns.add("特征「" + code + "」计算异常：" + textOf(ex.getMessage()) + "，按内置派生兜底");
            }
            runs.add(run);
        }
        return runs;
    }

    /** 特征入参实际取值：入参名 + 试算值 + 是否提供（前端特征卡「入参取值」行） */
    private static List<Map<String, Object>> valuesUsedOf(FeatureTrialDTO sub, Map<String, Object> p) {
        List<Map<String, Object>> items = new ArrayList<>();
        List<ResolvedInputDTO> resolved = sub.getResolvedInputs();
        if (resolved == null) return items;
        Map<String, Object> values = flatValues(p);
        for (ResolvedInputDTO in : resolved) {
            Object v = values.get(in.getName());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", in.getName());
            item.put("value", v);
            item.put("provided", v != null && notBlank(String.valueOf(v)));
            items.add(item);
        }
        return items;
    }

    /** 出参明细：优先取「出参定义」逐项结果，没有定义时用衍生单值合成一项 */
    private static List<Map<String, Object>> outputsOf(FeatureTrialDTO sub) {
        List<Map<String, Object>> outs = sub.getOutputResults();
        if (outs != null && !outs.isEmpty()) return outs;
        List<Map<String, Object>> synthesized = new ArrayList<>();
        if (sub.getDerivedValue() != null) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", sub.getFeatureCode() == null ? "result" : sub.getFeatureCode());
            item.put("type", sub.getDerivedValueType());
            item.put("value", sub.getDerivedValue());
            item.put("missing", false);
            synthesized.add(item);
        }
        return synthesized;
    }

    /** 主值口径：出参定义里第一个有值的出参 → 衍生单值 → 结构化输出里的第一个非空值 */
    private static Object primaryValue(FeatureTrialDTO sub) {
        List<Map<String, Object>> outs = sub.getOutputResults();
        if (outs != null) {
            for (Map<String, Object> o : outs) {
                if (!Boolean.TRUE.equals(o.get("missing")) && o.get("value") != null) return o.get("value");
            }
        }
        if (sub.getDerivedValue() != null) return sub.getDerivedValue();
        if (sub.getOutputs() instanceof Map<?, ?> m) {
            for (Object v : m.values()) {
                if (v != null) return v;
            }
        }
        return null;
    }

    /** 特征入参取值表：试算入参平铺 + param. 前缀双写（与 FeatureService 查找约定一致） */
    private static Map<String, Object> flatValues(Map<String, Object> p) {
        Map<String, Object> values = new LinkedHashMap<>();
        p.forEach((k, v) -> {
            if (v == null || CONTROL_KEYS.contains(k)) return;
            values.put(k, v);
            values.put("param." + k, v);
        });
        return values;
    }

    /* ==================================================================== */
    /*  逐节点计算明细                                                       */
    /* ==================================================================== */

    /** 逐节点明细：表达式 / 读取变量 / 结果 / 耗时（结构见 docs/trial-traffic-route.md §5.1） */
    private List<Map<String, Object>> buildNodeDetails(List<DecisionNode> nodes, Map<String, Object> vars,
                                                       DecisionNode hit, List<Map<String, Object>> featureRuns) {
        Map<String, String> featNotes = new LinkedHashMap<>();
        for (Map<String, Object> run : featureRuns) {
            String code = String.valueOf(run.get("code"));
            Object v = vars.get("feat." + code);
            if (v == null) continue;
            featNotes.put(code, "feat." + code + " = " + v
                    + (Boolean.TRUE.equals(run.get("fallback")) ? "（内置派生兜底）" : "（真实特征计算）"));
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            DecisionNode n = nodes.get(i);
            String type = n.getNodeType() == null ? "STEP" : n.getNodeType().toUpperCase(Locale.ROOT);
            String expr = notBlank(n.getConditionExpr()) ? n.getConditionExpr() : null;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("nodeId", n.getNodeId());
            item.put("title", n.getTitle() == null ? "" : n.getTitle().trim());
            item.put("nodeType", type);
            item.put("priority", n.getPriority());
            item.put("expr", expr);
            item.put("varsRead", varsRead(n, vars));
            item.put("elapsedMs", STEP_MS[Math.min(i, STEP_MS.length - 1)]);

            String result;
            String resultText;
            String detail;
            switch (type) {
                case "DECISION" -> {
                    if (n == hit) {
                        result = "HIT";
                        resultText = "命中·短路";
                        detail = (n.getConclusion() == null ? "—" : n.getConclusion()) + " · "
                                + (n.getReasonCode() == null ? "—" : n.getReasonCode());
                    } else {
                        ConditionEvaluator.Result r = expr == null ? null : ConditionEvaluator.evaluate(expr, vars);
                        if (r == ConditionEvaluator.Result.TRUE) {
                            result = "COND_TRUE";
                            resultText = "满足·已短路";
                            detail = "条件满足·已被更高优先级节点短路";
                        } else if (r == ConditionEvaluator.Result.FALSE) {
                            result = "COND_FALSE";
                            resultText = "未命中";
                            detail = "条件不满足·跳过";
                        } else {
                            result = "EVAL_FAIL";
                            resultText = "未求值";
                            detail = "表达式未能求值·跳过";
                        }
                    }
                }
                case "FEATURE" -> {
                    ConditionEvaluator.Result r = expr == null ? null : ConditionEvaluator.evaluate(expr, vars);
                    result = r == ConditionEvaluator.Result.TRUE ? "COND_TRUE"
                            : r == ConditionEvaluator.Result.FALSE ? "COND_FALSE" : "INFO";
                    resultText = r == ConditionEvaluator.Result.TRUE ? "满足"
                            : r == ConditionEvaluator.Result.FALSE ? "未满足" : "信息";
                    String mark = r == ConditionEvaluator.Result.TRUE ? "满足"
                            : r == ConditionEvaluator.Result.FALSE ? "未满足" : "未求值";
                    String note = "";
                    for (String ref : refVarsOf(n)) {
                        String t = ref.trim();
                        String c = t.startsWith("feat.") ? t.substring(5) : t.startsWith("f.") ? t.substring(2) : null;
                        if (c != null && featNotes.containsKey(c)) {
                            note = " · " + featNotes.get(c);
                            break;
                        }
                    }
                    detail = (expr == null ? "特征计算" : expr + " → " + mark) + note;
                }
                case "START" -> { result = "INFO"; resultText = "信息"; detail = expr == null ? "事件接单" : expr; }
                case "DATA" -> { result = "INFO"; resultText = "信息"; detail = expr == null ? "取数" : expr; }
                case "BRANCH" -> {
                    result = "INFO";
                    resultText = "信息";
                    detail = expr == null ? "按条件分流" : expr + "（未登记可求值表达式）";
                }
                case "CROSS" -> { result = "INFO"; resultText = "信息"; detail = "交叉决策树·按行列切片求值"; }
                case "ACTION" -> { result = "INFO"; resultText = "信息"; detail = "短路终止·跳过后续节点"; }
                case "END" -> { result = "OUTPUT"; resultText = "输出"; detail = "组装输出·生成 Trace 与决策日志"; }
                default -> { result = "INFO"; resultText = "信息"; detail = expr == null ? "执行" : expr; }
            }
            item.put("result", result);
            item.put("resultText", resultText);
            item.put("detail", detail);
            out.add(item);
        }
        return out;
    }

    /** 节点读取变量：ref_vars_json 逐项取值；视图根引用按前缀展开最多 3 个子变量；取不到标 missing */
    private List<Map<String, Object>> varsRead(DecisionNode n, Map<String, Object> vars) {
        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String ref : refVarsOf(n)) {
            String name = ref.trim();
            if (name.isEmpty() || CONTROL_KEYS.contains(name) || !seen.add(name)) continue;
            if (vars.containsKey(name)) {
                items.add(varItem(name, vars.get(name), false));
                continue;
            }
            List<String> subs = vars.keySet().stream()
                    .filter(k -> k.startsWith(name + ".")).limit(3).collect(Collectors.toList());
            if (subs.isEmpty()) items.add(varItem(name, null, true));
            else {
                for (String k : subs) items.add(varItem(k, vars.get(k), false));
            }
        }
        return items;
    }

    private static Map<String, Object> varItem(String name, Object value, boolean missing) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("value", value);
        item.put("missing", missing);
        return item;
    }

    private static double numOf(Object prefer, Object fallback) {
        if (prefer instanceof Number n) return n.doubleValue();
        if (fallback instanceof Number n) return n.doubleValue();
        return 0;
    }

    private static String textOf(Object v) {
        return v == null ? "—" : String.valueOf(v);
    }

    /** 决策图引用扫描：param.* 与 feat.* 并集（含表达式兜底与 FEATURE 节点裸编码识别） */
    private RefScan scanRefs(List<DecisionNode> nodes) {
        RefScan scan = new RefScan();
        for (DecisionNode n : nodes) {
            String label = nodeLabel(n);
            for (String ref : refVarsOf(n)) {
                String t = ref.trim();
                if (t.startsWith("param.")) addRef(scan.paramRefs, t.substring(6), label);
                else if (t.startsWith("c.")) addRef(scan.paramRefs, t.substring(2), label);
                else if (t.startsWith("feat.")) addFeatRef(scan.featRefs, scan.featRaw, t.substring(5), t, label);
                else if (t.startsWith("f.")) addFeatRef(scan.featRefs, scan.featRaw, t.substring(2), t, label);
            }
            String expr = n.getConditionExpr();
            if (!notBlank(expr)) continue;
            Matcher m = EXPR_REF.matcher(expr);
            while (m.find()) {
                String head = m.group(1);
                if ("param".equals(head) || "c".equals(head)) addRef(scan.paramRefs, m.group(2), label);
                else addFeatRef(scan.featRefs, scan.featRaw, m.group(2), head + "." + m.group(2), label);
            }
            if ("FEATURE".equalsIgnoreCase(String.valueOf(n.getNodeType()))) {
                Matcher lead = LEADING_IDENT.matcher(expr);
                if (lead.find() && !NAMESPACE_HEADS.contains(lead.group(1))) {
                    addFeatRef(scan.featRefs, scan.featRaw, lead.group(1), lead.group(1), label);
                }
            }
        }
        return scan;
    }

    private static final class RefScan {
        final Map<String, LinkedHashSet<String>> paramRefs = new LinkedHashMap<>();
        final Map<String, LinkedHashSet<String>> featRefs = new LinkedHashMap<>();
        final Map<String, String> featRaw = new LinkedHashMap<>();
    }

    /** resolve-inputs 请求体里可嵌套 params（当前试算入参），取出供流量判定读取 */
    private static Map<String, Object> subParamsOf(Map<String, Object> p) {
        Object nested = p.get("params");
        Map<String, Object> out = new LinkedHashMap<>();
        if (nested instanceof Map<?, ?> m) {
            m.forEach((k, v) -> { if (k != null) out.put(String.valueOf(k), v); });
        }
        return out;
    }

    /* ==================================================================== */
    /*  策略 / 版本解析                                                      */
    /* ==================================================================== */

    /** 试算可选的策略与版本：策略按编码定位，版本带状态与节点数供前端选择 */
    public List<TrialStrategyOptionDTO> listStrategyOptions() {
        List<TrialStrategyOptionDTO> out = new ArrayList<>();
        for (Strategy s : strategyMapper.selectList(null)) {
            List<StrategyVersion> versions = versionMapper.selectByStrategyIdOrderById(s.getId());
            Map<Long, Integer> nodeCounts = new LinkedHashMap<>();
            for (StrategyVersion v : versions) {
                nodeCounts.put(v.getId(), nodeMapper.selectByVersionIdOrderById(v.getId()).size());
            }

            TrialStrategyOptionDTO dto = new TrialStrategyOptionDTO();
            dto.setId(s.getId());
            dto.setCode(s.getCode());
            dto.setName(s.getName());
            dto.setCategory(s.getCategory());
            dto.setDescription(s.getDescription());
            dto.setOwner(s.getOwner());
            boolean online = versions.stream().anyMatch(v -> StrategyStatus.isPublished(v.getStatus()));
            dto.setStatus(online ? "PUBLISHED" : "DRAFT");
            dto.setStatusText(online ? "已发布" : "草稿");

            for (StrategyVersion v : versions) {
                TrialStrategyOptionDTO.VersionOption o = new TrialStrategyOptionDTO.VersionOption();
                o.setId(v.getId());
                o.setVersionNo(v.getVersionNo());
                o.setRev(v.getRev());
                o.setStatus(StrategyStatus.threeState(v.getStatus()));
                o.setStatusText(StrategyStatus.threeStateText(v.getStatus()));
                o.setStateLabel(v.getStateLabel());
                o.setBundle(v.getBundle());
                o.setGrayscalePct("OFFLINE".equals(o.getStatus()) ? 0
                        : (v.getGrayscalePct() == null ? 0 : v.getGrayscalePct()));
                o.setNodeCount(nodeCounts.getOrDefault(v.getId(), 0));
                dto.getVersions().add(o);
            }

            StrategyVersion def = pickDefaultVersion(versions, nodeCounts);
            if (def != null) {
                dto.setDefaultVersionId(def.getId());
                dto.setDefaultVersionNo(def.getVersionNo());
            }
            out.add(dto);
        }
        return out;
    }

    private Strategy resolveStrategy(Map<String, Object> p) {
        String code = str(p, "strategy_code", "strategyCode");
        if (code != null) {
            Strategy s = strategyMapper.selectOne(new QueryWrapper<Strategy>().eq("code", code));
            if (s == null) throw new RuntimeException("策略编码不存在：" + code + "，请先在「策略管理」确认策略已创建");
            return s;
        }
        Long id = longOf(p, "strategy_id", "strategyId");
        if (id != null) {
            Strategy s = strategyMapper.selectById(id);
            if (s == null) throw new RuntimeException("策略不存在: " + id);
            return s;
        }
        List<Strategy> all = strategyMapper.selectList(null);
        if (all.isEmpty()) throw new RuntimeException("暂无可试算的策略，请先在「策略管理」创建策略");
        return all.get(0);
    }

    private StrategyVersion resolveVersion(Strategy strategy, Map<String, Object> p) {
        List<StrategyVersion> versions = versionMapper.selectByStrategyIdOrderById(strategy.getId());
        if (versions.isEmpty()) return null;

        Long vid = longOf(p, "version_id", "versionId");
        if (vid != null) {
            return versions.stream().filter(v -> vid.equals(v.getId())).findFirst()
                    .orElseThrow(() -> new RuntimeException("版本 " + vid + " 不属于策略「" + strategy.getCode() + "」"));
        }
        String vno = str(p, "version_no", "versionNo", "version");
        if (vno != null) {
            return versions.stream().filter(v -> vno.equalsIgnoreCase(v.getVersionNo())).findFirst()
                    .orElseThrow(() -> new RuntimeException("策略「" + strategy.getCode() + "」下不存在版本 " + vno));
        }
        Map<Long, Integer> nodeCounts = new LinkedHashMap<>();
        for (StrategyVersion v : versions) {
            nodeCounts.put(v.getId(), nodeMapper.selectByVersionIdOrderById(v.getId()).size());
        }
        return pickDefaultVersion(versions, nodeCounts);
    }

    /** 默认版本：登记了决策图的优先，其次状态更靠线上，最后取 rev 更大的 */
    private StrategyVersion pickDefaultVersion(List<StrategyVersion> versions, Map<Long, Integer> nodeCounts) {
        if (versions.isEmpty()) return null;
        return versions.stream().max(Comparator.comparingLong(v -> versionScore(v, nodeCounts))).orElse(versions.get(0));
    }

    private long versionScore(StrategyVersion v, Map<Long, Integer> nodeCounts) {
        int nodes = nodeCounts.getOrDefault(v.getId(), 0);
        long rev = v.getRev() == null ? 0 : Math.min(v.getRev(), 999);
        return (nodes > 0 ? 1_000_000L : 0L) + StrategyStatus.rank(v.getStatus()) * 1000L + rev;
    }

    /** 回退解析输入模板用的「配置最完整」带图版本：排除目标版本后按综合分取最高（带图优先，其次更靠线上） */
    private StrategyVersion pickGraphVersion(Strategy strategy, StrategyVersion target) {
        List<StrategyVersion> versions = versionMapper.selectByStrategyIdOrderById(strategy.getId());
        Map<Long, Integer> nodeCounts = new LinkedHashMap<>();
        for (StrategyVersion v : versions) {
            if (target != null && target.getId().equals(v.getId())) continue;
            int c = nodeMapper.selectByVersionIdOrderById(v.getId()).size();
            if (c > 0) nodeCounts.put(v.getId(), c);
        }
        if (nodeCounts.isEmpty()) return null;
        return versions.stream().filter(v -> nodeCounts.containsKey(v.getId()))
                .max(Comparator.comparingLong(v -> versionScore(v, nodeCounts))).orElse(null);
    }

    /* ==================================================================== */
    /*  变量表与默认规则集                                                    */
    /* ==================================================================== */

    /**
     * 把试算入参摊平成条件表达式能引用的变量表，同时补齐表达式里常用的派生特征
     * （user_overdue_level / risk_level 等），避免编排图的条件因为"变量没登记"而求值不到。
     */
    private Map<String, Object> buildVars(Map<String, Object> p) {
        Map<String, Object> vars = new LinkedHashMap<>();
        p.forEach((k, v) -> {
            if (v == null || CONTROL_KEYS.contains(k)) return;
            vars.put(k, v);
            vars.put("param." + k, v);
        });

        BigDecimal overdue = num(p, "overdue_cnt", 3);
        String level = overdue.compareTo(BigDecimal.valueOf(3)) >= 0 ? "H"
                : (overdue.compareTo(BigDecimal.valueOf(1)) >= 0 ? "M" : "L");
        vars.put("user_overdue_level", level);
        vars.put("feat.user_overdue_level", level);
        vars.put("user_overdue_cnt", overdue);
        vars.put("feat.user_overdue_cnt", overdue);
        vars.put("view.risk_user_info.overdue_cnt", overdue);

        BigDecimal score = num(p, "risk_score", 742);
        vars.put("risk_score", score);
        vars.put("feat.risk_score", score);
        String riskLevel = score.compareTo(BigDecimal.valueOf(750)) >= 0 ? "HIGH"
                : (score.compareTo(BigDecimal.valueOf(620)) >= 0 ? "MID" : "LOW");
        vars.put("risk_level", riskLevel);
        vars.put("feat.risk_level", riskLevel);

        BigDecimal amount = num(p, "apply_amount", 50000);
        vars.put("apply_amount", amount);
        vars.put("param.apply_amount", amount);

        vars.putIfAbsent("event", "LOAN_APPLY");
        return vars;
    }

    /** 版本没有可求值的编排节点时使用的兜底准入规则集 */
    private DefaultRule defaultRule(Map<String, Object> p) {
        BigDecimal overdue = num(p, "overdue_cnt", 3);
        BigDecimal score = num(p, "risk_score", 742);
        if (overdue.compareTo(BigDecimal.valueOf(3)) >= 0) {
            return new DefaultRule("REJECT", "RISK_OVERDUE_HIGH", "R007 逾期准入");
        }
        if (score.compareTo(BigDecimal.valueOf(800)) >= 0) {
            return new DefaultRule("REJECT", "RISK_SCORE_HIGH", "R015 评分准入");
        }
        return new DefaultRule("REVIEW", "RISK_SCORE_MEDIUM", "默认规则集·转人工复核");
    }

    private static final class DefaultRule {
        private final String conclusion;
        private final String reasonCode;
        private final String hitRule;

        private DefaultRule(String conclusion, String reasonCode, String hitRule) {
            this.conclusion = conclusion;
            this.reasonCode = reasonCode;
            this.hitRule = hitRule;
        }
    }

    /* ==================================================================== */
    /*  执行路径                                                             */
    /* ==================================================================== */

    private List<String> graphStepTitles(List<DecisionNode> nodes, Map<String, Object> vars, DecisionNode hit) {
        List<String> out = new ArrayList<>();
        for (DecisionNode n : nodes) {
            String type = n.getNodeType() == null ? "STEP" : n.getNodeType().toUpperCase(Locale.ROOT);
            String title = n.getTitle() == null ? "" : n.getTitle().trim();
            String detail = switch (type) {
                case "START" -> notBlank(n.getConditionExpr()) ? n.getConditionExpr() : "事件接单";
                case "DATA" -> notBlank(n.getConditionExpr()) ? n.getConditionExpr() : "取数";
                case "FEATURE" -> featureDetail(n, vars);
                case "BRANCH" -> notBlank(n.getConditionExpr())
                        ? n.getConditionExpr() + "（未登记可求值表达式）" : "按条件分流";
                case "DECISION" -> decisionDetail(n, vars, hit);
                case "CROSS" -> "交叉决策树·按行列切片求值";
                case "ACTION" -> "短路终止·跳过后续节点";
                case "END" -> "组装输出·生成 Trace 与决策日志";
                default -> notBlank(n.getConditionExpr()) ? n.getConditionExpr() : "执行";
            };
            out.add(type + "·" + title + "·" + detail);
        }
        return out;
    }

    private String featureDetail(DecisionNode n, Map<String, Object> vars) {
        String expr = n.getConditionExpr();
        if (!notBlank(expr)) return "特征计算完成";
        ConditionEvaluator.Result r = ConditionEvaluator.evaluate(expr, vars);
        String mark = r == ConditionEvaluator.Result.TRUE ? "命中"
                : r == ConditionEvaluator.Result.FALSE ? "未命中" : "未登记可求值表达式";
        return expr + " → " + mark;
    }

    private String decisionDetail(DecisionNode n, Map<String, Object> vars, DecisionNode hit) {
        if (n == hit) return n.getConclusion() + "·" + (n.getReasonCode() == null ? "—" : n.getReasonCode());
        ConditionEvaluator.Result r = ConditionEvaluator.evaluate(n.getConditionExpr(), vars);
        if (r == ConditionEvaluator.Result.TRUE) return "条件满足·已被更高优先级节点短路";
        if (r == ConditionEvaluator.Result.FALSE) return "条件不满足·跳过";
        return "表达式未能求值·跳过";
    }

    /** 版本未编排决策图时的默认执行路径骨架 */
    private List<String> fallbackStepTitles(Map<String, Object> vars, String conclusion, String reasonCode) {
        String level = String.valueOf(vars.getOrDefault("feat.user_overdue_level", "L"));
        List<String> out = new ArrayList<>();
        out.add("START·事件接单·event = " + vars.getOrDefault("event", "LOAN_APPLY"));
        out.add("DATA·用户信息·view.risk_user_info·12变量");
        out.add("DATA·订单列表·view.loan_order_list·6变量");
        out.add("FEATURE·逾期等级·feat.user_overdue_level = " + level);
        out.add("FEATURE·申请次数7d·feat.user_apply_cnt_7d = 2");
        out.add("BRANCH·风险分档·按阈值分流");
        out.add("DECISION·默认规则集·" + conclusion + "·" + reasonCode);
        out.add("ACTION·TERMINATE 短路·跳过交叉决策树");
        out.add("END·组装输出·生成 Trace 与决策日志");
        return out;
    }

    private TrialExecutionStep step(int no, String title, double ms, TrialRecord record) {
        TrialExecutionStep s = new TrialExecutionStep();
        s.setTrialId(record.getId());
        s.setStepNo(no);
        s.setStepTitle(title);
        s.setElapsedMs(BigDecimal.valueOf(ms).setScale(1, RoundingMode.HALF_UP));
        stepMapper.insert(s);
        return s;
    }

    /* ==================================================================== */
    /*  记录查询                                                             */
    /* ==================================================================== */

    public List<TrialRecord> recent() {
        return recordMapper.selectTop20ByCreatedAtDesc();
    }

    public TrialDTO detail(Long id) {
        TrialRecord record = recordMapper.selectById(id);
        if (record == null) throw new RuntimeException("试算记录不存在: " + id);
        List<TrialExecutionStep> steps = stepMapper.selectByTrialIdOrderByStepNo(id);
        return new TrialDTO(record, steps);
    }

    /* ==================================================================== */
    /*  回归用例集                                                           */
    /* ==================================================================== */

    public List<TrialCaseDTO> listCases() {
        return listCases(null);
    }

    /** 用例集按策略过滤：strategyId 为空时返回全部 */
    public List<TrialCaseDTO> listCases(Long strategyId) {
        QueryWrapper<TrialCase> qw = new QueryWrapper<>();
        if (strategyId != null) qw.eq("strategy_id", strategyId);
        return caseMapper.selectList(qw).stream().map(this::toDTO).collect(Collectors.toList());
    }

    public TrialCaseDTO createCase(TrialCase c) {
        c.setId(null);
        c.setCreatedAt(java.time.LocalDateTime.now());
        caseMapper.insert(c);
        return toDTO(c);
    }

    public TrialCaseDTO updateCase(Long id, TrialCase patch) {
        TrialCase existing = caseMapper.selectById(id);
        if (existing == null) throw new RuntimeException("用例不存在: " + id);
        if (patch.getName() != null) existing.setName(patch.getName());
        if (patch.getStrategyId() != null) existing.setStrategyId(patch.getStrategyId());
        if (patch.getInputsJson() != null) existing.setInputsJson(patch.getInputsJson());
        if (patch.getExpectConclusion() != null) existing.setExpectConclusion(patch.getExpectConclusion());
        if (patch.getTags() != null) existing.setTags(patch.getTags());
        caseMapper.updateById(existing);
        return toDTO(existing);
    }

    public void deleteCase(Long id) {
        caseMapper.deleteById(id);
    }

    public List<TrialCaseResultDTO> runAllCases() {
        return runAllCases(null);
    }

    /** 批量跑用例；每个用例按它自己绑定的策略执行，未绑定的落到入参里的策略 */
    public List<TrialCaseResultDTO> runAllCases(Long strategyId) {
        List<TrialCase> cases = caseMapper.selectList(new QueryWrapper<TrialCase>()
                .eq(strategyId != null, "strategy_id", strategyId));
        List<TrialCaseResultDTO> results = new ArrayList<>();
        for (TrialCase c : cases) {
            Map<String, Object> inputs = withStrategy(parseJson(c.getInputsJson()), c.getStrategyId());
            String actual = String.valueOf(execute(inputs).getResponse().get("conclusion"));
            String status = actual.equals(c.getExpectConclusion()) ? "一致" : "不一致";
            results.add(new TrialCaseResultDTO(c.getId(), c.getExpectConclusion(), actual, status));
        }
        return results;
    }

    /* ==================================================================== */
    /*  批量回放 / 影子对比                                                  */
    /* ==================================================================== */

    public List<TrialCaseResultDTO> batchReplay(List<String> traceIds, Long strategyId, String version) {
        List<TrialCaseResultDTO> results = new ArrayList<>();
        if (traceIds == null) return results;
        String target = version == null ? null : version.trim();
        for (String traceId : traceIds) {
            Map<String, Object> in = new LinkedHashMap<>();
            if (strategyId != null) in.put("strategy_id", strategyId);
            // 目标版本在该策略下不存在时不带版本号，退化为默认版本，避免整批回放失败
            if (notBlank(target) && versionExists(strategyId, target)) in.put("version_no", target);
            String actual = String.valueOf(execute(in).getResponse().get("conclusion"));
            results.add(new TrialCaseResultDTO(null, "REVIEW", actual, "replayed"));
        }
        return results;
    }

    private boolean versionExists(Long strategyId, String versionNo) {
        try {
            Strategy s = resolveStrategy(strategyId == null ? Map.of() : Map.of("strategy_id", strategyId));
            return versionMapper.selectByStrategyIdOrderById(s.getId()).stream()
                    .anyMatch(v -> versionNo.equalsIgnoreCase(v.getVersionNo()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    public ShadowCompareDTO shadowCompare(Map<String, Object> inputs, Long strategyId) {
        Map<String, Object> base = withStrategy(inputs, strategyId);

        TrialDTO online = execute(base);
        long onlineMs = ((BigDecimal) online.getResponse().get("elapsedMs")).longValue();

        TrialDTO candidate = execute(base);
        long candidateMs = ((BigDecimal) candidate.getResponse().get("elapsedMs")).longValue();

        ShadowCompareDTO dto = new ShadowCompareDTO();
        dto.setOnlineResult(new ShadowCompareDTO.ResultItem(
                String.valueOf(online.getResponse().get("conclusion")), onlineMs));
        dto.setCandidateResult(new ShadowCompareDTO.ResultItem(
                String.valueOf(candidate.getResponse().get("conclusion")), candidateMs));
        dto.setSameConclusion(online.getResponse().get("conclusion").equals(candidate.getResponse().get("conclusion")));
        return dto;
    }

    /* ==================================================================== */
    /*  工具                                                                 */
    /* ==================================================================== */

    private Map<String, Object> withStrategy(Map<String, Object> inputs, Long strategyId) {
        Map<String, Object> out = new LinkedHashMap<>(inputs == null ? Map.of() : inputs);
        if (strategyId != null && !out.containsKey("strategy_id") && !out.containsKey("strategyId")) {
            out.put("strategy_id", strategyId);
        }
        return out;
    }

    private TrialCaseDTO toDTO(TrialCase c) {
        TrialCaseDTO dto = new TrialCaseDTO();
        dto.setId(c.getId());
        dto.setName(c.getName());
        dto.setStrategyId(c.getStrategyId());
        dto.setInputs(parseJson(c.getInputsJson()));
        dto.setExpectConclusion(c.getExpectConclusion());
        dto.setTags(c.getTags());
        dto.setCreatedAt(c.getCreatedAt());
        return dto;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) return new LinkedHashMap<>();
        try {
            return JSON.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return new LinkedHashMap<>();
        }
    }

    private String toJson(Map<String, Object> params) {
        try {
            return JSON.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static BigDecimal num(Map<String, Object> p, String key, double def) {
        Object v = p.get(key);
        if (v == null) return BigDecimal.valueOf(def);
        if (v instanceof Number n) return new BigDecimal(n.toString());
        String s = String.valueOf(v).trim();
        try {
            return s.isEmpty() ? BigDecimal.valueOf(def) : new BigDecimal(s);
        } catch (NumberFormatException e) {
            return BigDecimal.valueOf(def);
        }
    }

    private static String str(Map<String, Object> p, String... keys) {
        for (String k : keys) {
            Object v = p.get(k);
            if (v != null && !String.valueOf(v).trim().isEmpty()) return String.valueOf(v).trim();
        }
        return null;
    }

    private static Long longOf(Map<String, Object> p, String... keys) {
        String s = str(p, keys);
        if (s == null) return null;
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
