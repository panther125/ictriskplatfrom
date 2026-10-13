package com.riskstrategy.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskstrategy.dto.*;
import com.riskstrategy.engine.DerivedExecutor;
import com.riskstrategy.engine.EngineResult;
import com.riskstrategy.entity.*;
import com.riskstrategy.mapper.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

@Service
public class FeatureService {

    /** 衍生特征里对基础特征的引用语法：@@base_feature_code */
    private static final Pattern REF_PATTERN = Pattern.compile("@@([a-zA-Z_][a-zA-Z0-9_.]{0,127})");
    /** 衍生特征里对决策入参的引用语法：:param.xxx */
    private static final Pattern PARAM_PATTERN = Pattern.compile(":param\\.([a-zA-Z_][a-zA-Z0-9_]*)");
    private static final Set<String> FEATURE_TYPES = Set.of("BASE", "DERIVED", "COMPOSITE");
    private static final Set<String> COMPUTE_TYPES = Set.of("REF", "SQL", "Java", "Python", "OP");

    private final FeatureMapper featureMapper;
    private final FeatureTestCaseMapper testCaseMapper;
    private final FeatureApprovalMapper approvalMapper;
    private final ViewService viewService;
    private final DerivedExecutor derivedExecutor;
    private final ObjectMapper objectMapper;

    public FeatureService(FeatureMapper featureMapper,
                          FeatureTestCaseMapper testCaseMapper,
                          FeatureApprovalMapper approvalMapper,
                          ViewService viewService,
                          DerivedExecutor derivedExecutor,
                          ObjectMapper objectMapper) {
        this.featureMapper = featureMapper;
        this.testCaseMapper = testCaseMapper;
        this.approvalMapper = approvalMapper;
        this.viewService = viewService;
        this.derivedExecutor = derivedExecutor;
        this.objectMapper = objectMapper;
    }

    public PageResult<Feature> list(int page, int size) {
        Page<Feature> p = new Page<>(page, size);
        return PageResult.of(featureMapper.selectPage(p, null));
    }

    public FeatureDetailDTO detail(Long id) {
        Feature feature = featureMapper.selectById(id);
        if (feature == null) throw new RuntimeException("特征不存在: " + id);
        List<FeatureTestCase> testCases = testCaseMapper.selectByFeatureId(id);
        return new FeatureDetailDTO(feature, inputVarsOf(feature), outputVarsOf(feature), testCases);
    }

    // =========================================================================
    //  入参 / 出参定义：存在 feature 表的 input_vars_json / output_vars_json 两列里
    //  （2026-10-13 起由 feature_input_var / feature_output_var 两张子表合并而来）
    // =========================================================================

    /** 读特征的入参定义。列空 / 解析失败时返回空列表，不抛异常打断页面。 */
    public List<FeatureInputVar> inputVarsOf(Feature f) {
        if (f == null || isBlank(f.getInputVarsJson())) return new ArrayList<>();
        try {
            List<FeatureInputVar> list = objectMapper.readValue(f.getInputVarsJson(),
                    new TypeReference<List<FeatureInputVar>>() {});
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public List<FeatureInputVar> inputVarsOf(Long featureId) {
        return featureId == null ? new ArrayList<>() : inputVarsOf(featureMapper.selectById(featureId));
    }

    /** 读特征的出参定义（按写入时的 sortNo 顺序）。 */
    public List<FeatureOutputVar> outputVarsOf(Feature f) {
        if (f == null || isBlank(f.getOutputVarsJson())) return new ArrayList<>();
        try {
            List<FeatureOutputVar> list = objectMapper.readValue(f.getOutputVarsJson(),
                    new TypeReference<List<FeatureOutputVar>>() {});
            return list == null ? new ArrayList<>() : list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public List<FeatureOutputVar> outputVarsOf(Long featureId) {
        if (featureId == null) return new ArrayList<>();
        return outputVarsOf(featureMapper.selectById(featureId));
    }

    /**
     * 把入参 / 出参清单写成 JSON 列内容。
     *
     * <p>空清单写 {@code []} 而不是 NULL：一是语义上「用户把清单清空了」也要能落库，
     * 二是 MyBatis-Plus 的 updateById 会跳过 null 字段，写 null 根本清不掉旧值。
     */
    private String toVarsJson(Object rows) {
        try {
            return objectMapper.writeValueAsString(rows == null ? List.of() : rows);
        } catch (Exception e) {
            throw new RuntimeException("入参 / 出参定义保存失败：" + e.getMessage());
        }
    }

    /** 只更新入参 JSON 列（<code>inputCount</code> 为 null 时不动计数）。 */
    private void writeInputVars(Long featureId, List<FeatureInputVar> rows, Integer inputCount) {
        Feature patch = new Feature();
        patch.setId(featureId);
        patch.setInputVarsJson(toVarsJson(rows));
        if (inputCount != null) patch.setInputCount(inputCount);
        featureMapper.updateById(patch);
    }

    /** 只更新出参 JSON 列（顺带对齐 outputCount 与 dataType）。 */
    private void writeOutputVars(Long featureId, List<FeatureOutputVar> rows) {
        Feature patch = new Feature();
        patch.setId(featureId);
        patch.setOutputVarsJson(toVarsJson(rows));
        patch.setOutputCount(rows.size());
        if (!rows.isEmpty()) patch.setDataType(rows.get(0).getVarType());
        featureMapper.updateById(patch);
    }

    public Feature create(Feature feature) {
        validateBinding(feature, null, true);
        feature.setId(null);
        featureMapper.insert(feature);
        ensureOutputVars(feature);
        return feature;
    }

    public Feature update(Long id, Feature patch) {
        Feature existing = featureMapper.selectById(id);
        if (existing == null) throw new RuntimeException("特征不存在: " + id);
        // 编码不在这里改：走 POST /features/{id}/rename，那里会一并同步下游引用，
        // 放在本接口会被绕过同步逻辑；patch.code 在此静默忽略。
        if (patch.getName() != null) existing.setName(patch.getName());
        if (patch.getStatus() != null) existing.setStatus(patch.getStatus());
        if (patch.getBusinessDesc() != null) existing.setBusinessDesc(patch.getBusinessDesc());
        if (patch.getSqlExpr() != null) existing.setSqlExpr(patch.getSqlExpr());
        if (patch.getFeatureType() != null) existing.setFeatureType(patch.getFeatureType());
        if (patch.getComputeType() != null) existing.setComputeType(patch.getComputeType());
        if (patch.getDataType() != null) existing.setDataType(patch.getDataType());
        if (patch.getViewId() != null) existing.setViewId(patch.getViewId());
        if (patch.getViewCode() != null) existing.setViewCode(patch.getViewCode());
        if (patch.getViewField() != null) existing.setViewField(patch.getViewField());
        if (patch.getExpr() != null) existing.setExpr(patch.getExpr());
        if (patch.getInputCount() != null) existing.setInputCount(patch.getInputCount());
        if (patch.getOutputCount() != null) existing.setOutputCount(patch.getOutputCount());
        if (patch.getVerifyStatus() != null) existing.setVerifyStatus(patch.getVerifyStatus());
        validateBinding(existing, id, false);
        featureMapper.updateById(existing);
        return existing;
    }

    // =========================================================================
    //  特征改名：换编码 + 同步所有按编码引用它的下游配置
    // =========================================================================

    /**
     * 修改特征编码，并把下游配置里按编码写死的引用一并改掉。
     *
     * <p>同步范围（配置态）：
     * <ul>
     *   <li>其它衍生特征表达式里的 {@code @@旧编码}；</li>
     *   <li>策略决策节点的条件表达式 / 引用变量清单里的 {@code feat.旧编码}；</li>
     *   <li>决策规则 config_json 里的 {@code feat.旧编码}；</li>
     *   <li>交叉决策树维度表达式里的 {@code feat.旧编码}。</li>
     * </ul>
     * 决策日志 / 追踪回放（track_*）属运行态历史记录，**故意不改** —— 历史就该保留当时的编码。
     *
     * @return {oldCode, newCode, changed, total, details:[{target, count}]}
     */
    public Map<String, Object> renameCode(Long id, String newCodeRaw) {
        Feature feature = featureMapper.selectById(id);
        if (feature == null) throw new RuntimeException("特征不存在: " + id);
        String newCode = newCodeRaw == null ? "" : newCodeRaw.trim();
        if (newCode.isEmpty()) throw new RuntimeException("新编码不能为空");
        if (!newCode.matches("[a-zA-Z_][a-zA-Z0-9_.]*")) {
            throw new RuntimeException("特征编码只能由字母、数字、下划线与点组成，且以字母或下划线开头");
        }
        String oldCode = feature.getCode();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("oldCode", oldCode);
        out.put("newCode", newCode);
        if (newCode.equals(oldCode)) {
            out.put("changed", false);
            out.put("total", 0);
            out.put("details", List.of());
            return out;
        }
        Feature dup = featureMapper.selectByCode(newCode);
        if (dup != null && !dup.getId().equals(id)) {
            throw new RuntimeException("编码「" + newCode + "」已被特征「" + dup.getName() + "」占用，请换一个");
        }

        // 1) 先落自己的编码，后面按新编码写下游
        feature.setCode(newCode);
        featureMapper.updateById(feature);

        // 2) 同步下游配置里的引用
        List<Map<String, Object>> details = new ArrayList<>();
        int total = 0;
        for (String[] pair : refPatterns(oldCode, newCode)) {
            String pat = pair[0], rep = pair[1];
            total += collectHit(details, "衍生特征表达式", featureMapper.renameInFeatureExpr(pat, rep));
            total += collectHit(details, "特征 SQL 表达式", featureMapper.renameInFeatureSqlExpr(pat, rep));
            total += collectHit(details, "策略节点条件", featureMapper.renameInNodeCondition(pat, rep));
            total += collectHit(details, "策略节点引用变量", featureMapper.renameInNodeRefVars(pat, rep));
            total += collectHit(details, "决策规则配置", featureMapper.renameInRuleConfig(pat, rep));
            total += collectHit(details, "交叉树维度", featureMapper.renameInCrossTreeDim(pat, rep));
        }
        out.put("changed", true);
        out.put("total", total);
        out.put("details", details);
        return out;
    }

    /**
     * 组装「旧引用 → 新引用」的正则替换对。平台里同一个特征有三种写法：
     * <ol>
     *   <li>衍生特征表达式：{@code @@编码}</li>
     *   <li>策略节点 / 规则 / 交叉树：{@code feat.编码}</li>
     *   <li>裸编码（历史写法，如决策节点条件里的 {@code user_overdue_level = H}）</li>
     * </ol>
     * 第 3 种虽然范围最宽，但两侧都加了词边界断言，并把 {@code @@} / {@code .} / {@code @} 排除在前置断言外，
     * 因此不会命中 {@code @@编码}、{@code feat.编码} 或 {@code view.xxx.编码} 这类更长的限定名，也不会把 abc 误伤成 abcd。
     */
    private List<String[]> refPatterns(String oldCode, String newCode) {
        List<String[]> pairs = new ArrayList<>();
        String oc = escapeRegex(oldCode);
        // 1) @@编码
        pairs.add(new String[]{"@@" + oc + "(?![A-Za-z0-9_.])", "@@" + newCode});
        // 2) feat.编码（编码本身带 feat. 前缀时，第 3 条裸匹配即可覆盖）
        if (!oldCode.startsWith("feat.")) {
            pairs.add(new String[]{"(?<![A-Za-z0-9_.@])feat\\." + oc + "(?![A-Za-z0-9_.])", "feat." + newCode});
        }
        // 3) 裸编码
        pairs.add(new String[]{"(?<![A-Za-z0-9_.@])" + oc + "(?![A-Za-z0-9_.])", newCode});
        return pairs;
    }

    /** 转义正则元字符（编码只允许字母数字下划线点，这里做防御性处理） */
    private String escapeRegex(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) sb.append('\\');
            sb.append(c);
        }
        return sb.toString();
    }

    private int collectHit(List<Map<String, Object>> details, String target, int count) {
        if (count > 0) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("target", target);
            d.put("count", count);
            details.add(d);
        }
        return count;
    }

    // =========================================================================
    //  特征绑定：基础特征（引用视图出参）/ 衍生特征（@@ 引用基础特征）
    // =========================================================================

    /**
     * 创建 / 保存时的绑定校验与派生字段填充。
     *
     * @param strict 创建态为 true：额外强校验业务口径说明；保存态为 false，避免历史数据无法回写。
     */
    public void validateBinding(Feature f, Long selfId, boolean strict) {
        String type = f.getFeatureType() == null ? "DERIVED" : f.getFeatureType().trim().toUpperCase(Locale.ROOT);
        if (!FEATURE_TYPES.contains(type)) {
            throw new RuntimeException("创建失败：特征类型只能是 BASE（基础特征）/ DERIVED（衍生特征）");
        }
        f.setFeatureType(type);

        if (strict && isBlank(f.getBusinessDesc())) {
            throw new RuntimeException("创建失败：请填写业务口径说明，写清楚「这个数从哪来、什么时间范围、含不含某类记录」");
        }
        // C6：口径说明 ≥10 字
        if (!isBlank(f.getBusinessDesc()) && f.getBusinessDesc().trim().length() < 10) {
            throw new RuntimeException("创建失败：业务口径说明至少 10 个字，当前 "
                    + f.getBusinessDesc().trim().length() + " 字");
        }

        if ("BASE".equals(type)) {
            validateBaseBinding(f);
        } else {
            validateDerivedBinding(f, selfId);
        }
    }

    /** 基础特征：必须选定「一个视图的一个出参」，类型继承视图字段，入参即视图入参。 */
    private void validateBaseBinding(Feature f) {
        if (f.getViewId() == null && isBlank(f.getViewCode())) {
            throw new RuntimeException("基础特征必须选择一个视图作为数据来源");
        }
        if (isBlank(f.getViewField())) {
            throw new RuntimeException("基础特征必须选择该视图的一个出参作为特征输出值");
        }
        ViewDetailDTO detail;
        try {
            detail = f.getViewId() != null ? viewService.detail(f.getViewId())
                    : viewService.detailByCode(f.getViewCode());
        } catch (RuntimeException e) {
            throw new RuntimeException("基础特征绑定的视图不可用：" + e.getMessage());
        }
        DataSourceView view = detail.getView();
        f.setViewId(view.getId());
        f.setViewCode(view.getCode());

        // C3.5：只能绑定「已发布上线」的视图——草稿 / 降级中的视图口径未定版，不能作为特征取数来源
        if (!"PUBLISHED".equalsIgnoreCase(String.valueOf(view.getStatus()))) {
            throw new RuntimeException("基础特征只能绑定「已发布上线」的视图，视图 " + view.getCode()
                    + " 当前状态为「" + statusLabel(view.getStatus()) + "」；请先到视图管理发布后再创建基础特征");
        }

        // C4：LIST 视图返回多行，不能 1:1 建基础特征
        if ("LIST".equalsIgnoreCase(view.getReadMode())) {
            throw new RuntimeException("视图 " + view.getCode()
                    + " 的取数模式为 LIST（返回多行），不能直接建基础特征；请改建成聚合类衍生特征");
        }
        // C1 + C3：只能引用一个字段，且数据类型必须与视图字段完全一致
        ViewField field = detail.getFields().stream()
                .filter(x -> f.getViewField().equalsIgnoreCase(x.getFieldName()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("出参 " + f.getViewField()
                        + " 不在视图 " + view.getCode() + " 的出参清单中，请重新选择"));
        f.setViewField(field.getFieldName());
        f.setDataType(field.getFieldType());
        if (isBlank(f.getComputeType())) f.setComputeType("REF");
        f.setInputCount(detail.getInputs() != null ? detail.getInputs().size() : 0);
        f.setOutputCount(1);
    }

    /** 视图状态码 → 面向用户的中文说明 */
    private String statusLabel(String status) {
        if (status == null || status.isBlank()) return "未知状态";
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "PUBLISHED" -> "已发布上线";
            case "DRAFT" -> "草稿";
            case "CIRCUIT_BREAKER" -> "降级中";
            case "OFFLINE" -> "已下线";
            default -> status;
        };
    }

    /** 衍生特征：表达式必填，@@ 引用必须都能解析到有效的基础特征，且不得引用自身。 */
    private void validateDerivedBinding(Feature f, Long selfId) {
        if (isBlank(f.getExpr())) {
            throw new RuntimeException("衍生特征必须填写加工表达式，可用 @@基础特征编码 引用基础特征");
        }
        List<FeatureRefDTO> refs = resolveRefs(f.getExpr());
        List<String> bad = refs.stream()
                .filter(r -> !Boolean.TRUE.equals(r.getResolved()))
                .map(r -> r.getRaw() + "（" + r.getMessage() + "）")
                .collect(Collectors.toList());
        if (!bad.isEmpty()) {
            throw new RuntimeException("表达式中存在无法解析的引用：" + String.join("；", bad));
        }
        for (FeatureRefDTO r : refs) {
            if (selfId != null && selfId.equals(r.getId())) {
                throw new RuntimeException("衍生特征不能引用自身：" + r.getRaw());
            }
        }
        if (f.getDataType() == null || f.getDataType().isBlank()) f.setDataType("STRING");
        if (isBlank(f.getComputeType())) f.setComputeType("SQL");
        if ("OP".equalsIgnoreCase(f.getComputeType())) {
            String opError = derivedExecutor.validateExpression(f.getComputeType(), f.getExpr());
            if (opError != null) {
                throw new RuntimeException("OP 算子树配置无效：" + opError);
            }
        }
        f.setInputCount(collectDerivedInputs(f.getExpr(), refs).size());
        if (f.getOutputCount() == null) f.setOutputCount(1);
    }

    // ---- @@ 引用解析 ----

    /**
     * 解析衍生特征表达式里的 {@code @@基础特征编码}。
     * 只有基础特征可被引用；返回顺序与出现顺序一致，重复引用只保留一条。
     */
    public List<FeatureRefDTO> resolveRefs(String expr) {
        List<FeatureRefDTO> result = new ArrayList<>();
        if (isBlank(expr)) return result;
        Matcher m = REF_PATTERN.matcher(expr);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find()) {
            String code = m.group(1);
            if (!seen.add(code)) continue;
            FeatureRefDTO dto = new FeatureRefDTO();
            dto.setRaw("@@" + code);
            dto.setCode(code);
            Feature target = featureMapper.selectByCode(code);
            if (target == null) {
                dto.setResolved(false);
                dto.setMessage("平台内不存在该编码的特征");
                result.add(dto);
                continue;
            }
            dto.setId(target.getId());
            dto.setName(target.getName());
            dto.setFeatureType(target.getFeatureType());
            dto.setDataType(target.getDataType());
            dto.setViewCode(target.getViewCode());
            dto.setViewField(target.getViewField());
            if (!"BASE".equalsIgnoreCase(target.getFeatureType())) {
                dto.setResolved(false);
                dto.setMessage("只有基础特征可以被 @@ 引用，该特征是「"
                        + ("DERIVED".equalsIgnoreCase(target.getFeatureType()) ? "衍生" : "组合") + "特征」");
                result.add(dto);
                continue;
            }
            if (target.getViewId() == null && isBlank(target.getViewCode())) {
                dto.setResolved(false);
                dto.setMessage("该基础特征尚未绑定视图，无法解析其入参");
                result.add(dto);
                continue;
            }
            try {
                ViewDetailDTO vd = target.getViewId() != null
                        ? viewService.detail(target.getViewId())
                        : viewService.detailByCode(target.getViewCode());
                dto.setResolved(true);
                dto.setInputs(viewInputsOf(vd, target.getCode()));
            } catch (RuntimeException e) {
                dto.setResolved(false);
                dto.setMessage("绑定的视图不可用：" + e.getMessage());
            }
            result.add(dto);
        }
        return result;
    }

    /** 把视图的登记入参映射成验证可用入参 */
    private List<ResolvedInputDTO> viewInputsOf(ViewDetailDTO detail, String fromRef) {
        List<ResolvedInputDTO> list = new ArrayList<>();
        String viewCode = detail.getView() != null ? detail.getView().getCode() : "?";
        List<ViewInputVar> inputs = detail.getInputs() == null ? List.of() : detail.getInputs();
        for (ViewInputVar iv : inputs) {
            ResolvedInputDTO dto = new ResolvedInputDTO(
                    iv.getVarCode(),
                    iv.getVarType() == null ? "STRING" : iv.getVarType(),
                    fromRef == null ? "VIEW" : "FEATURE",
                    fromRef == null
                            ? "视图 " + viewCode + " 入参"
                            : "基础特征 " + fromRef + " → 视图 " + viewCode + " 入参");
            dto.setRequired(iv.getRequired() == null || iv.getRequired());
            dto.setDefaultValue(iv.getDefaultValue());
            dto.setSampleValue(iv.getDefaultValue());
            dto.setFromRef(fromRef == null ? viewCode : fromRef);
            list.add(dto);
        }
        return list;
    }

    /** 表达式里直接写的决策入参 :param.xxx */
    private List<ResolvedInputDTO> paramInputsOf(String expr) {
        List<ResolvedInputDTO> list = new ArrayList<>();
        if (isBlank(expr)) return list;
        Matcher m = PARAM_PATTERN.matcher(expr);
        Set<String> seen = new LinkedHashSet<>();
        while (m.find()) {
            if (!seen.add(m.group(1))) continue;
            ResolvedInputDTO dto = new ResolvedInputDTO(
                    "param." + m.group(1), "STRING", "PARAM", "决策请求入参 param");
            dto.setRequired(true);
            list.add(dto);
        }
        return list;
    }

    /** 衍生特征的全部入参：决策入参 + 每个被引用基础特征所携带的视图入参（按变量名去重）。 */
    public List<ResolvedInputDTO> collectDerivedInputs(String expr, List<FeatureRefDTO> refs) {
        LinkedHashMap<String, ResolvedInputDTO> merged = new LinkedHashMap<>();
        for (ResolvedInputDTO p : paramInputsOf(expr)) mergeInput(merged, p);
        for (ResolvedInputDTO in : collectDerivedInputs(refs)) mergeInput(merged, in);
        return new ArrayList<>(merged.values());
    }

    /** 只统计 @@ 引用带入的入参 */
    public List<ResolvedInputDTO> collectDerivedInputs(List<FeatureRefDTO> refs) {
        LinkedHashMap<String, ResolvedInputDTO> merged = new LinkedHashMap<>();
        for (FeatureRefDTO ref : refs) {
            if (!Boolean.TRUE.equals(ref.getResolved()) || ref.getInputs() == null) continue;
            for (ResolvedInputDTO input : ref.getInputs()) {
                mergeInput(merged, input);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private void mergeInput(LinkedHashMap<String, ResolvedInputDTO> merged, ResolvedInputDTO input) {
        ResolvedInputDTO exists = merged.get(input.getName());
        if (exists == null) {
            merged.put(input.getName(), input);
            return;
        }
        // 同名入参来自多个来源：保留首个定义，把来源记进 mergedFrom，避免重复填写
        if (exists.getMergedFrom() == null) exists.setMergedFrom(new ArrayList<>());
        String existLabel = exists.getSourceLabel();
        String newLabel = input.getSourceLabel();
        if (newLabel != null && !newLabel.equals(existLabel) && !exists.getMergedFrom().contains(newLabel)) {
            exists.getMergedFrom().add(newLabel);
        }
    }

    /**
     * 按表单态（尚未落库）解析验证所需入参。
     * BASE → 视图入参；DERIVED → :param 与 @@ 引用带入的入参并集。
     */
    public Map<String, Object> resolveInputs(FeatureResolveInputsRequest req) {
        Map<String, Object> resp = new LinkedHashMap<>();
        String type = req.getFeatureType() == null ? "DERIVED" : req.getFeatureType().trim().toUpperCase(Locale.ROOT);
        List<ResolvedInputDTO> inputs = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if ("BASE".equals(type)) {
            if (req.getViewId() == null && isBlank(req.getViewCode())) {
                warnings.add("尚未选择视图，无法解析入参");
            } else {
                ViewDetailDTO detail;
                try {
                    detail = req.getViewId() != null ? viewService.detail(req.getViewId())
                            : viewService.detailByCode(req.getViewCode());
                } catch (RuntimeException e) {
                    warnings.add(e.getMessage());
                    detail = null;
                }
                if (detail != null) {
                    inputs = viewInputsOf(detail, null);
                    resp.put("view", viewSummary(detail));
                    if (!"PUBLISHED".equalsIgnoreCase(String.valueOf(detail.getView().getStatus()))) {
                        warnings.add("该视图尚未发布上线（当前「" + statusLabel(detail.getView().getStatus())
                                + "」），只有已发布上线的视图才能作为基础特征的取数来源");
                    }
                    if ("LIST".equalsIgnoreCase(detail.getView().getReadMode())) {
                        warnings.add("该视图取数模式为 LIST（多行返回），无法直接建基础特征，请改建成聚合类衍生特征");
                    }
                }
            }
        } else {
            List<FeatureRefDTO> refs = resolveRefs(req.getExpr());
            for (FeatureRefDTO ref : refs) {
                if (!Boolean.TRUE.equals(ref.getResolved())) {
                    warnings.add(ref.getRaw() + " 解析失败：" + ref.getMessage());
                }
            }
            List<ResolvedInputDTO> params = paramInputsOf(req.getExpr());
            LinkedHashMap<String, ResolvedInputDTO> merged = new LinkedHashMap<>();
            for (ResolvedInputDTO p : params) mergeInput(merged, p);
            for (ResolvedInputDTO in : collectDerivedInputs(refs)) mergeInput(merged, in);
            inputs = new ArrayList<>(merged.values());
            resp.put("refs", refs);
            resp.put("resolvedRefCount", refs.stream().filter(r -> Boolean.TRUE.equals(r.getResolved())).count());
            resp.put("unresolvedRefCount", refs.stream().filter(r -> !Boolean.TRUE.equals(r.getResolved())).count());
        }

        resp.put("featureType", type);
        resp.put("inputs", inputs);
        resp.put("inputCount", inputs.size());
        resp.put("warnings", warnings);
        return resp;
    }

    /** 列出可作为 @@ 引用目标的基础特征 */
    public List<Map<String, Object>> listBaseFeatures(int limit) {
        return featureMapper.selectBaseFeatures(limit).stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.getId());
            m.put("code", f.getCode());
            m.put("name", f.getName());
            m.put("dataType", f.getDataType());
            m.put("viewCode", f.getViewCode());
            m.put("viewField", f.getViewField());
            m.put("status", f.getStatus());
            return m;
        }).collect(Collectors.toList());
    }

    /** 把解析出的入参登记到 feature.input_vars_json（供血缘与热度统计使用）。
     *  已存在的同名列会保留用户调整过的「必填 / 默认值 / 是否参与」，其余按最新解析结果刷新。 */
    public void saveInputVars(Long featureId, List<ResolvedInputDTO> inputs) {
        if (featureId == null) return;
        List<FeatureInputVar> existing = inputVarsOf(featureId);
        Map<String, FeatureInputVar> byCode = new LinkedHashMap<>();
        for (FeatureInputVar v : existing) byCode.putIfAbsent(v.getVarCode(), v);

        List<FeatureInputVar> rows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<ResolvedInputDTO> safe = inputs == null ? List.of() : inputs;
        for (ResolvedInputDTO in : safe) {
            if (isBlank(in.getName()) || !seen.add(in.getName())) continue;
            FeatureInputVar old = byCode.remove(in.getName());
            FeatureInputVar v = new FeatureInputVar();
            v.setVarCode(in.getName());
            v.setVarType(isBlank(in.getType()) ? "STRING" : in.getType());
            v.setSource(in.getSource() == null ? "FEATURE" : in.getSource());
            v.setRequired(old != null && old.getRequired() != null
                    ? old.getRequired() : (in.getRequired() == null || in.getRequired()));
            v.setDefaultValue(old != null && old.getDefaultValue() != null
                    ? old.getDefaultValue() : in.getDefaultValue());
            v.setEnabled(old == null || old.getEnabled() == null ? Boolean.TRUE : old.getEnabled());
            rows.add(v);
        }
        // 用户在「入参定义」里手工补充的行，解析结果里不会出现，需要原样保留
        for (FeatureInputVar left : byCode.values()) {
            if ("MANUAL".equalsIgnoreCase(left.getSource())) rows.add(left);
        }
        writeInputVars(featureId, rows, null);
    }

    /**
     * 入参定义全量保存：前端把用户在步骤 2 编辑后的最终清单整份提交上来
     * （含 enabled=false 的「已移除」标记、以及 source=MANUAL 的手工补充行）。
     */
    public List<FeatureInputVar> saveInputVarRows(Long id, List<FeatureInputVar> rows) {
        Feature f = featureMapper.selectById(id);
        if (f == null) throw new RuntimeException("特征不存在: " + id);
        List<FeatureInputVar> clean = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int enabledCount = 0;
        List<FeatureInputVar> safe = rows == null ? List.of() : rows;
        for (FeatureInputVar r : safe) {
            if (r == null || isBlank(r.getVarCode()) || !seen.add(r.getVarCode())) continue;
            String code = r.getVarCode().trim();
            if (!code.matches("[a-zA-Z_][a-zA-Z0-9_.]*")) {
                throw new RuntimeException("入参名「" + code + "」不合法：只能由字母、数字、下划线与点组成，且以字母或下划线开头");
            }
            FeatureInputVar v = new FeatureInputVar();
            v.setVarCode(code);
            v.setVarType(isBlank(r.getVarType()) ? "STRING" : r.getVarType());
            v.setSource(isBlank(r.getSource()) ? "MANUAL" : r.getSource());
            v.setRequired(r.getRequired() == null || r.getRequired());
            v.setDefaultValue(r.getDefaultValue());
            v.setEnabled(r.getEnabled() == null || r.getEnabled());
            if (Boolean.TRUE.equals(v.getEnabled())) enabledCount++;
            clean.add(v);
        }
        writeInputVars(id, clean, enabledCount);
        return clean;
    }

    /** 供验证步骤使用：取某个已存在特征当前应填的入参清单（叠加用户在入参定义里的调整） */
    public Map<String, Object> resolvedInputsOf(Feature f) {
        FeatureResolveInputsRequest req = new FeatureResolveInputsRequest();
        req.setFeatureType(f.getFeatureType());
        req.setViewId(f.getViewId());
        req.setViewCode(f.getViewCode());
        req.setViewField(f.getViewField());
        req.setExpr(f.getExpr());
        Map<String, Object> resolved = resolveInputs(req);
        applyInputOverrides(f.getId(), resolved);
        return resolved;
    }

    /**
     * 用「入参定义」里保存过的用户调整覆盖自动解析结果：
     * <ul>
     *   <li>enabled=false → 用户在页面上移除了该行，验证时不再要求填写；</li>
     *   <li>同名列 → 用用户设定的类型 / 必填 / 默认值；</li>
     *   <li>source=MANUAL → 解析结果里没有也补进来（手工补充的决策入参）。</li>
     * </ul>
     */
    private void applyInputOverrides(Long featureId, Map<String, Object> resolved) {
        if (featureId == null) return;
        List<FeatureInputVar> saved = inputVarsOf(featureId);
        if (saved == null || saved.isEmpty()) return;
        Map<String, FeatureInputVar> byCode = new LinkedHashMap<>();
        for (FeatureInputVar v : saved) byCode.putIfAbsent(v.getVarCode(), v);

        List<ResolvedInputDTO> inputs = asList(resolved.get("inputs"));
        List<ResolvedInputDTO> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolvedInputDTO in : inputs) {
            FeatureInputVar o = byCode.get(in.getName());
            if (o != null) {
                if (Boolean.FALSE.equals(o.getEnabled())) { seen.add(in.getName()); continue; }
                if (!isBlank(o.getVarType())) in.setType(o.getVarType());
                if (o.getRequired() != null) in.setRequired(o.getRequired());
                if (o.getDefaultValue() != null) {
                    in.setDefaultValue(o.getDefaultValue());
                    in.setSampleValue(o.getDefaultValue());
                }
            }
            seen.add(in.getName());
            merged.add(in);
        }
        for (FeatureInputVar o : saved) {
            if (!"MANUAL".equalsIgnoreCase(o.getSource())) continue;
            if (Boolean.FALSE.equals(o.getEnabled())) continue;
            if (!seen.add(o.getVarCode())) continue;
            ResolvedInputDTO dto = new ResolvedInputDTO(o.getVarCode(),
                    isBlank(o.getVarType()) ? "STRING" : o.getVarType(),
                    "MANUAL", "在入参定义里手工补充的入参");
            dto.setRequired(o.getRequired() == null || o.getRequired());
            dto.setDefaultValue(o.getDefaultValue());
            dto.setSampleValue(o.getDefaultValue());
            merged.add(dto);
        }
        resolved.put("inputs", merged);
    }

    /** 按 id 取入参清单（验证步骤进入时调用） */
    public Map<String, Object> resolvedInputById(Long id) {
        Feature f = featureMapper.selectById(id);
        if (f == null) throw new RuntimeException("特征不存在: " + id);
        return resolvedInputsOf(f);
    }

    /** 入参登记：按当前绑定重新解析并写入 feature.input_vars_json（供血缘 / 引用热度统计） */
    public Map<String, Object> syncInputVars(Long id) {
        Feature f = featureMapper.selectById(id);
        if (f == null) throw new RuntimeException("特征不存在: " + id);
        Map<String, Object> resolved = resolvedInputsOf(f);
        List<ResolvedInputDTO> inputs = asList(resolved.get("inputs"));
        saveInputVars(id, inputs);
        // 出参兜底：新建特征 / 历史数据没有出参记录时补一份，并把 outputCount 对齐
        syncOutputVarsIfAbsent(id);
        Feature patch = new Feature();
        patch.setId(id);
        patch.setInputCount(inputs.size());
        featureMapper.updateById(patch);
        resolved.put("synced", true);
        return resolved;
    }

    // =========================================================================
    //  出参定义：一个特征可以有多个出参（基础特征固定 1 个，继承绑定视图字段）
    // =========================================================================

    /** 出参定义全量保存：用户在「出参定义」里增删改后的最终清单 */
    public List<FeatureOutputVar> saveOutputVars(Long id, List<FeatureOutputVar> rows) {
        Feature f = featureMapper.selectById(id);
        if (f == null) throw new RuntimeException("特征不存在: " + id);
        boolean isBase = "BASE".equalsIgnoreCase(f.getFeatureType());
        List<FeatureOutputVar> clean = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int sort = 0;
        List<FeatureOutputVar> safe = rows == null ? List.of() : rows;
        for (FeatureOutputVar r : safe) {
            if (r == null || isBlank(r.getVarCode()) || !seen.add(r.getVarCode().trim())) continue;
            String code = r.getVarCode().trim();
            if (!code.matches("[a-zA-Z_][a-zA-Z0-9_.]*")) {
                throw new RuntimeException("出参名「" + code + "」不合法：只能由字母、数字、下划线与点组成，且以字母或下划线开头");
            }
            FeatureOutputVar v = new FeatureOutputVar();
            v.setVarCode(code);
            v.setVarType(isBlank(r.getVarType()) ? "STRING" : r.getVarType());
            v.setDescription(r.getDescription());
            v.setSortNo(sort++);
            clean.add(v);
            if (isBase) break; // 基础特征 1 视图 → 1 出参，不允许并列多个
        }
        if (clean.isEmpty()) throw new RuntimeException("出参定义不能为空：请至少保留 1 个出参");
        // 首个出参类型与特征主类型保持一致（下游按特征 dataType 取值）
        writeOutputVars(id, clean);
        return clean;
    }

    /** 出参兜底：没有出参记录时按特征类型补一份（衍生默认 result，基础同步视图字段） */
    public void syncOutputVarsIfAbsent(Long id) {
        Feature f = featureMapper.selectById(id);
        if (f == null) return;
        if (!outputVarsOf(f).isEmpty()) return;
        List<FeatureOutputVar> rows = defaultOutputVars(f);
        if (rows.isEmpty()) return;
        writeOutputVars(id, rows);
    }

    /** 新建特征时的出参兜底 */
    private void ensureOutputVars(Feature f) {
        if (f == null || f.getId() == null) return;
        if (!outputVarsOf(f).isEmpty()) return;
        List<FeatureOutputVar> rows = defaultOutputVars(f);
        if (rows.isEmpty()) return;
        writeOutputVars(f.getId(), rows);
        f.setOutputVarsJson(toVarsJson(rows));
    }

    private List<FeatureOutputVar> defaultOutputVars(Feature f) {
        List<FeatureOutputVar> rows = new ArrayList<>();
        if ("BASE".equalsIgnoreCase(f.getFeatureType())) {
            if (isBlank(f.getViewField())) return rows;
            FeatureOutputVar v = new FeatureOutputVar();
            v.setVarCode(f.getViewField());
            v.setVarType(isBlank(f.getDataType()) ? "STRING" : f.getDataType());
            v.setDescription("引用视图字段");
            v.setSortNo(0);
            rows.add(v);
        } else {
            FeatureOutputVar v = new FeatureOutputVar();
            v.setVarCode("result");
            v.setVarType(isBlank(f.getDataType()) ? "STRING" : f.getDataType());
            v.setDescription("表达式返回值");
            v.setSortNo(0);
            rows.add(v);
        }
        return rows;
    }

    // ---- gap: stats ----
    public FeatureStatsDTO getStats() {
        List<Feature> all = featureMapper.selectList(null);
        int total = all.size();
        int registeredOutputs = all.stream().mapToInt(f -> f.getOutputCount() != null ? f.getOutputCount() : 0).sum();
        int totalInputs = all.stream().mapToInt(f -> f.getInputCount() != null ? f.getInputCount() : 0).sum();
        int unverified = (int) all.stream().filter(f -> "UNVERIFIED".equals(f.getVerifyStatus())).count();
        double coverage = total == 0 ? 0 : Math.round((total - unverified) * 1000.0 / total) / 10.0;

        Map<String, Integer> dist = new LinkedHashMap<>();
        for (Map<String, Object> row : featureMapper.countByVerifyStatus()) {
            String status = String.valueOf(row.get("verifyStatus")).toLowerCase();
            dist.put(status, ((Number) row.get("count")).intValue());
        }

        // 入参引用热度：原先靠 feature_input_var 表聚合，改存 JSON 后在内存里统计
        Map<String, Integer> inputRefs = new HashMap<>();
        for (Feature f : all) {
            for (FeatureInputVar v : inputVarsOf(f)) {
                if (isBlank(v.getVarCode())) continue;
                inputRefs.merge(v.getVarCode(), 1, Integer::sum);
            }
        }
        List<FeatureStatsDTO.RefItem> topRefs = inputRefs.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(5)
                .map(e -> new FeatureStatsDTO.RefItem(e.getKey(), e.getValue()))
                .collect(Collectors.toList());

        FeatureStatsDTO dto = new FeatureStatsDTO();
        dto.setIoCoverage(coverage);
        dto.setRegisteredOutputs(registeredOutputs);
        dto.setTotalFeatures(total);
        dto.setInputRefTotal(totalInputs);
        dto.setUnverifiedCount(unverified);
        dto.setVerifyDistribution(dist);
        dto.setTopInputRefs(topRefs);
        return dto;
    }

    // ---- gap: batch-verify ----
    public void batchVerify(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        featureMapper.batchUpdateVerifyStatus(ids, "VERIFIED");
    }

    // ---- gap: publish ----
    public void publish(Long id) {
        Feature f = featureMapper.selectById(id);
        if (f == null) throw new RuntimeException("特征不存在: " + id);
        featureMapper.publishById(id);
    }

    // ---- gap: offline ----
    public void offline(Long id) {
        Feature f = featureMapper.selectById(id);
        if (f == null) throw new RuntimeException("特征不存在: " + id);
        featureMapper.offlineById(id);
    }

    // ---- gap: validate-name ----
    public FeatureValidateDTO validateName(String code, String domain) {
        List<FeatureValidateDTO.CheckItem> checks = new ArrayList<>();

        boolean patternOk = code != null && code.matches("[a-z][a-z0-9_]*");
        checks.add(new FeatureValidateDTO.CheckItem("域.实体.指标_命名模式", patternOk));
        checks.add(new FeatureValidateDTO.CheckItem("全小写 + 下划线分隔", patternOk));

        boolean hasTimeSuffix = code != null && code.matches(".*_\\d+d$");
        FeatureValidateDTO.CheckItem timeCheck = new FeatureValidateDTO.CheckItem("窗口类指标建议带时间后缀", hasTimeSuffix);
        if (!hasTimeSuffix) {
            timeCheck.setSuggest(true);
            timeCheck.setSuggestText("建议命名如 xxx_7d / xxx_30d");
        }
        checks.add(timeCheck);

        Set<String> reserved = Set.of("select", "delete", "update", "insert", "drop", "alter", "table", "index");
        boolean noReserved = code == null || !reserved.contains(code.toLowerCase());
        checks.add(new FeatureValidateDTO.CheckItem("无保留字冲突", noReserved));

        boolean unique = true;
        if (code != null) {
            List<Feature> similar = featureMapper.selectSimilarByCode(code, 10);
            unique = similar.stream().noneMatch(f -> code.equals(f.getCode()));
        }
        checks.add(new FeatureValidateDTO.CheckItem("同域内名称唯一", unique));

        FeatureValidateDTO dto = new FeatureValidateDTO();
        dto.setPassed(checks.stream().allMatch(c -> Boolean.TRUE.equals(c.getPass())));
        dto.setChecks(checks);
        return dto;
    }

    // ---- gap: similar ----
    public List<SimilarFeatureDTO> findSimilar(String code) {
        List<Feature> matches = featureMapper.selectSimilarByCode(code, 5);
        int codeLen = code == null ? 1 : code.length();
        return matches.stream().map(f -> {
            int common = commonPrefix(code, f.getCode());
            int pct = codeLen == 0 ? 0 : common * 100 / codeLen;
            return new SimilarFeatureDTO(f.getCode(), pct + "%");
        }).collect(Collectors.toList());
    }

    // ---- gap: impact ----
    public FeatureImpactDTO getImpact(Long id) {
        Feature f = featureMapper.selectById(id);
        if (f == null) throw new RuntimeException("特征不存在: " + id);
        FeatureImpactDTO dto = new FeatureImpactDTO();
        dto.setUpstreamViews(List.of("view.risk_user_info", "view.loan_order_list"));
        dto.setDownstreamRefs(f.getRefCount() != null ? f.getRefCount() : 0);
        dto.setDownstreamStrategies(3);
        dto.setDownstreamFeatures(f.getInputCount() != null ? f.getInputCount() : 0);
        dto.setStrategies(List.of("还款能力准入", "首贷额度", "反欺诈"));
        return dto;
    }

    // ---- gap: parse-inputs ----
    public List<ParsedInputDTO> parseInputs(String sql) {
        List<ParsedInputDTO> result = new ArrayList<>();
        if (sql == null || sql.isBlank()) return result;
        Pattern p = Pattern.compile(":param\\.([a-zA-Z_][a-zA-Z0-9_]*)");
        Matcher m = p.matcher(sql);
        Set<String> seen = new HashSet<>();
        while (m.find()) {
            String name = m.group(1);
            if (seen.add(name)) {
                result.add(new ParsedInputDTO("param." + name, "STRING", "决策请求 param"));
            }
        }
        if (sql.contains("window_start") || sql.contains("window_end")) {
            result.add(new ParsedInputDTO("param.window_start", "DATETIME", "系统内置 sys"));
        }
        return result;
    }

    // ---- trial：按特征类型执行验证 ----
    /**
     * 特征验证。
     *
     * <ul>
     *   <li>基础特征：用绑定的视图脚本 + 该视图登记的全部入参真实执行一次取数（复用 ViewService.validate，
     *       MySQL / HTTP / CSV / PMML 都走各自适配器），只核对选定的那一个出参；</li>
     *   <li>衍生特征：先解析 {@code @@} 引用，再用同一份测试值去跑每个被引用基础特征所在视图，
     *       回显引用实测值；随后把实侧值代入表达式，按加工方式（SQL / Java / Python / OP）
     *       真实执行一次，给出衍生输出。</li>
     * </ul>
     */
    public FeatureTrialDTO trial(Long id, Map<String, Object> testValues) {
        Feature f = featureMapper.selectById(id);
        if (f == null) throw new RuntimeException("特征不存在: " + id);
        Map<String, Object> values = testValues == null ? Map.of() : testValues;

        FeatureTrialDTO dto = new FeatureTrialDTO();
        dto.setFeatureCode(f.getCode());
        dto.setFeatureType(f.getFeatureType());
        dto.setWarnings(new ArrayList<>());

        Map<String, Object> resolved = resolvedInputsOf(f);
        List<ResolvedInputDTO> inputs = asList(resolved.get("inputs"));
        List<FeatureRefDTO> refs = asList(resolved.get("refs"));
        dto.setResolvedInputs(inputs);
        dto.setRefs(refs);
        for (Object o : asList(resolved.get("warnings"))) dto.getWarnings().add(String.valueOf(o));
        for (ResolvedInputDTO in : inputs) {
            if (Boolean.TRUE.equals(in.getRequired()) && isBlank(strOf(values.get(in.getName())))) {
                dto.getWarnings().add("必填入参 " + in.getName() + " 未填写测试值，验证将被拦截");
            }
        }

        if ("BASE".equalsIgnoreCase(f.getFeatureType())) {
            return trialBase(f, inputs, values, dto);
        }
        return trialDerived(f, inputs, refs, values, dto);
    }

    /** 基础特征验证：真实执行绑定视图的取数脚本，只核对特征选定的出参 */
    private FeatureTrialDTO trialBase(Feature f, List<ResolvedInputDTO> inputs,
                                      Map<String, Object> values, FeatureTrialDTO dto) {
        dto.setMode("VIEW_EXEC");
        ViewDetailDTO detail;
        try {
            detail = f.getViewId() != null ? viewService.detail(f.getViewId())
                    : viewService.detailByCode(f.getViewCode());
        } catch (RuntimeException e) {
            dto.setSuccess(false);
            dto.setError("绑定的视图不可用：" + e.getMessage());
            return dto;
        }
        DataSourceView view = detail.getView();
        dto.setViewCode(view.getCode());
        dto.setViewField(f.getViewField());

        ViewValidateRequest req = new ViewValidateRequest();
        req.setViewId(view.getId());
        req.setSourceType(view.getSourceType());
        req.setReadMode(view.getReadMode());
        req.setScriptContent(view.getScriptContent());

        List<ViewValidateRequest.InputValue> inputValues = new ArrayList<>();
        for (ViewInputVar iv : detail.getInputs()) {
            ViewValidateRequest.InputValue v = new ViewValidateRequest.InputValue();
            v.setVarCode(iv.getVarCode());
            v.setVarType(iv.getVarType());
            v.setRequired(iv.getRequired() == null || iv.getRequired());
            Object raw = values.get(iv.getVarCode());
            if (raw == null) raw = values.get("param." + iv.getVarCode());
            v.setValue(raw == null ? null : String.valueOf(raw));
            inputValues.add(v);
        }
        req.setInputs(inputValues);

        ViewField target = detail.getFields().stream()
                .filter(x -> f.getViewField() != null && f.getViewField().equalsIgnoreCase(x.getFieldName()))
                .findFirst().orElse(null);
        ViewValidateRequest.OutputSpec spec = new ViewValidateRequest.OutputSpec();
        spec.setFieldName(f.getViewField());
        spec.setFieldType(target != null ? target.getFieldType() : f.getDataType());
        spec.setDefaultValue(target != null ? target.getDefaultValue() : null);
        spec.setSort(0);
        req.setOutputs(List.of(spec));

        Map<String, Object> result = viewService.validate(req);
        dto.setViewValidation(result);
        Object elapsed = result.get("elapsedMs");
        dto.setElapsedMs(elapsed instanceof Number n ? n.longValue() : null);
        boolean ok = Boolean.TRUE.equals(result.get("success"));
        dto.setSuccess(ok);
        if (ok) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Object o : asList(result.get("results"))) {
                if (o instanceof Map<?, ?> row) {
                    out.put(String.valueOf(row.get("fieldName")), row.get("value"));
                    if (Boolean.TRUE.equals(row.get("defaultValueUsed"))) {
                        dto.getWarnings().add("出参 " + row.get("fieldName") + " 取到空值，已按默认值兜底");
                    }
                }
            }
            dto.setOutputs(out);
            dto.setOutputResults(buildOutputResults(outputVarsOf(f.getId()), out, dto.getWarnings()));
        } else {
            dto.setError(strOf(result.get("error")));
        }
        return dto;
    }

    /**
     * 衍生特征验证：先实测每个被引用基础特征的取值，再把实侧值代入表达式，
     * 按加工方式（SQL / Java / Python / OP）真实执行一次，给出衍生输出。
     */
    private FeatureTrialDTO trialDerived(Feature f, List<ResolvedInputDTO> inputs, List<FeatureRefDTO> refs,
                                         Map<String, Object> values, FeatureTrialDTO dto) {
        dto.setMode("EXPR_EXEC");
        dto.setSimulated(false);
        List<String> unresolved = refs.stream()
                .filter(r -> !Boolean.TRUE.equals(r.getResolved()))
                .map(FeatureRefDTO::getRaw).collect(Collectors.toList());
        if (!unresolved.isEmpty()) {
            dto.setSuccess(false);
            dto.setError("存在无法解析的 @@ 引用：" + String.join("、", unresolved));
            return dto;
        }

        Map<String, Object> refValues = new LinkedHashMap<>();
        long total = 0L;
        // 引用基础特征 → 用它们的视图真实取数，核对引用可用性与实侧值
        for (FeatureRefDTO ref : refs) {
            Feature base = featureMapper.selectById(ref.getId());
            if (base == null) {
                dto.getWarnings().add(ref.getRaw() + " 对应特征已不存在，已跳过");
                continue;
            }
            FeatureTrialDTO sub = new FeatureTrialDTO();
            sub.setWarnings(new ArrayList<>());
            trialBase(base, ref.getInputs(), values, sub);
            total += sub.getElapsedMs() == null ? 0 : sub.getElapsedMs();
            if (Boolean.TRUE.equals(sub.getSuccess())) {
                Map<String, Object> out = sub.getOutputs() instanceof Map<?, ?> m
                        ? castMap(m) : new LinkedHashMap<>();
                refValues.put(ref.getCode(), out.isEmpty() ? null : out.values().iterator().next());
            } else {
                dto.getWarnings().add(ref.getRaw() + " 取数未通过：" + strOf(sub.getError()));
                refValues.put(ref.getCode(), null);
            }
        }
        dto.setRefValues(refValues);
        dto.setElapsedMs(total);

        // @@ 实测值代入表达式后，按加工方式真实执行
        EngineResult result = derivedExecutor.execute(f, inputs, values, refValues);
        dto.setEngine(result.getEngine());
        dto.setExecElapsedMs(result.getElapsedMs());
        dto.getWarnings().addAll(result.getWarnings());
        dto.setExprPreview(!isBlank(result.getExecutedExpr())
                ? result.getExecutedExpr()
                : renderExprPreview(strOf(f.getExpr()), refs, refValues));

        Map<String, Object> outputs = new LinkedHashMap<>();
        outputs.put("refs", refValues);
        outputs.put("derived", result.getValue());
        dto.setOutputs(outputs);
        if (result.isSuccess()) {
            dto.setSuccess(true);
            dto.setDerivedValue(result.getValue());
            dto.setDerivedValueType(result.getValueType());
            // 按「出参定义」逐项取值：结构化结果按出参名匹配，单值归到第 1 个出参
            dto.setOutputResults(buildOutputResults(outputVarsOf(f.getId()), result.getValue(), dto.getWarnings()));
        } else {
            dto.setSuccess(false);
            dto.setError(result.getError());
        }
        return dto;
    }

    /**
     * 按「出参定义」从执行结果里逐项取值，供前端按出参展示「每个出参的值是多少」。
     *
     * <p>取值规则：
     * <ul>
     *   <li>结果本身是结构化数据（Python 返回 dict / 引擎返回 Map / JSON 对象字符串）→ 按出参名匹配（大小写不敏感）；</li>
     *   <li>结果只有单个标量 → 归到第 1 个出参；配了多个出参时其余出参置空并提示；</li>
     *   <li>对不上的出参标记 missing=true，前端可提示该出参没有产出值。</li>
     * </ul>
     */
    private List<Map<String, Object>> buildOutputResults(List<FeatureOutputVar> outVars, Object value,
                                                         List<String> warnings) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (outVars == null || outVars.isEmpty()) return list;
        Map<String, Object> structured = asStructuredMap(value);
        Map<String, String> lowerKeys = new LinkedHashMap<>();
        if (structured != null) {
            for (String k : structured.keySet()) lowerKeys.putIfAbsent(k.toLowerCase(Locale.ROOT), k);
        }
        boolean single = outVars.size() == 1;
        for (FeatureOutputVar ov : outVars) {
            String name = ov.getVarCode();
            Object v = null;
            boolean missing = false;
            if (structured != null) {
                String realKey = lowerKeys.get(name.toLowerCase(Locale.ROOT));
                if (realKey != null) v = structured.get(realKey);
                else missing = true;
            } else if (single) {
                v = value;
            } else {
                missing = true;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", name);
            item.put("type", ov.getVarType());
            item.put("value", v);
            item.put("missing", missing);
            list.add(item);
            if (missing && warnings != null) {
                warnings.add("出参「" + name + "」在执行结果里没有对应值，已置空"
                        + (structured == null && !single ? "（表达式需返回带该字段的结构化结果，如 dict / JSON）" : ""));
            }
        }
        return list;
    }

    /** 把执行结果解析成结构化 Map；不是结构化数据时返回 null（按标量处理） */
    private Map<String, Object> asStructuredMap(Object value) {
        if (value instanceof Map<?, ?> m) return castMap(m);
        if (value instanceof String s && s.trim().startsWith("{")) {
            try {
                JsonNode node = objectMapper.readTree(s);
                if (node != null && node.isObject()) {
                    return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
                }
            } catch (Exception ignore) {
                // 不是合法 JSON，按标量处理
            }
        }
        return null;
    }

    /** 把表达式里的 @@code 替换成实侧值，便于人工核对 */
    private String renderExprPreview(String expr, List<FeatureRefDTO> refs, Map<String, Object> refValues) {
        if (isBlank(expr)) return "";
        String out = expr;
        for (FeatureRefDTO ref : refs) {
            Object v = refValues.get(ref.getCode());
            String literal;
            if (v == null) {
                literal = "NULL";
            } else {
                String s = String.valueOf(v);
                literal = s.matches("-?\\d+(\\.\\d+)?") ? s : "'" + s + "'";
            }
            out = out.replace(ref.getRaw(), literal);
        }
        return out;
    }

    private String strOf(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 非空安全的列表转换：返回的 List 元素按 Object 处理（调用方自行 instanceof 判断） */
    @SuppressWarnings("unchecked")
    private <T> List<T> asList(Object o) {
        return o instanceof List ? (List<T>) o : new ArrayList<>();
    }

    private Map<String, Object> castMap(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    // ---- trial 结束 ----

    // ---- gap: precheck ----
    public List<PrecheckItemDTO> precheck(Long id) {
        Feature f = featureMapper.selectById(id);
        if (f == null) throw new RuntimeException("特征不存在: " + id);
        List<PrecheckItemDTO> items = new ArrayList<>();

        boolean verified = "VERIFIED".equals(f.getVerifyStatus());
        items.add(new PrecheckItemDTO("验证结果必须全程",
                "当前验证状态: " + f.getVerifyStatus(),
                verified ? "通过" : "阻断",
                verified ? "pass" : "block"));

        boolean ioRegistered = f.getInputCount() != null && f.getOutputCount() != null
                && f.getInputCount() > 0 && f.getOutputCount() > 0;
        items.add(new PrecheckItemDTO("入参与出参已登记",
                "入参: " + f.getInputCount() + " 出参: " + f.getOutputCount(),
                ioRegistered ? "通过" : "待确认",
                ioRegistered ? "pass" : "pending"));

        items.add(bindingCheck(f));

        items.add(new PrecheckItemDTO("影响面须人工确认",
                "下游引用: " + f.getRefCount() + " 处",
                "待确认", "pending"));
        return items;
    }

    /** 发布前校验「特征 → 视图出参」或「特征 → @@ 基础特征」的引用是否仍然有效 */
    private PrecheckItemDTO bindingCheck(Feature f) {
        if ("BASE".equalsIgnoreCase(f.getFeatureType())) {
            if (f.getViewId() == null || isBlank(f.getViewField())) {
                return new PrecheckItemDTO("基础特征引用关系有效", "尚未绑定视图出参", "阻断", "block");
            }
            ViewDetailDTO vd;
            try {
                vd = viewService.detail(f.getViewId());
            } catch (RuntimeException e) {
                return new PrecheckItemDTO("基础特征引用关系有效", e.getMessage(), "阻断", "block");
            }
            if (!"PUBLISHED".equalsIgnoreCase(String.valueOf(vd.getView().getStatus()))) {
                return new PrecheckItemDTO("基础特征引用关系有效",
                        "视图 " + f.getViewCode() + " 未发布上线（当前「" + statusLabel(vd.getView().getStatus())
                                + "」），基础特征的取数来源必须是已发布上线的视图", "阻断", "block");
            }
            if ("LIST".equalsIgnoreCase(vd.getView().getReadMode())) {
                return new PrecheckItemDTO("基础特征引用关系有效",
                        "视图 " + f.getViewCode() + " 取数模式为 LIST（多行），不能 1:1 建基础特征", "阻断", "block");
            }
            boolean fieldAlive = vd.getFields().stream()
                    .anyMatch(x -> f.getViewField().equalsIgnoreCase(x.getFieldName()));
            if (!fieldAlive) {
                return new PrecheckItemDTO("基础特征引用关系有效",
                        "视图 " + f.getViewCode() + " 中已找不到出参 " + f.getViewField() + "，请重新绑定",
                        "阻断", "block");
            }
            return new PrecheckItemDTO("基础特征引用关系有效",
                    "1:1 引用视图 " + f.getViewCode() + " 的出参 " + f.getViewField(), "通过", "pass");
        }

        List<FeatureRefDTO> refs = resolveRefs(f.getExpr());
        if (refs.isEmpty()) {
            return new PrecheckItemDTO("衍生特征引用关系有效", "表达式中未引用基础特征，仅使用决策入参", "通过", "pass");
        }
        long bad = refs.stream().filter(r -> !Boolean.TRUE.equals(r.getResolved())).count();
        if (bad > 0) {
            return new PrecheckItemDTO("衍生特征引用关系有效",
                    "共 " + refs.size() + " 个 @@ 引用，其中 " + bad + " 个无法解析", "阻断", "block");
        }
        return new PrecheckItemDTO("衍生特征引用关系有效",
                "共 " + refs.size() + " 个 @@ 引用，全部可解析，入参已合并去重", "通过", "pass");
    }

    // ---- gap: submit-approval ----
    public void submitApproval(Long id, String remark) {
        Feature f = featureMapper.selectById(id);
        if (f == null) throw new RuntimeException("特征不存在: " + id);
        FeatureApproval a = new FeatureApproval();
        a.setFeatureId(id);
        a.setStepNo(1);
        a.setStepName("提交审批");
        a.setApprover(f.getOwner());
        a.setStatus("PENDING");
        a.setRemark(remark);
        approvalMapper.insert(a);
    }

    // ---- helpers ----
    /** 视图摘要（回显给前端做基础特征绑定确认，全部字段做 null 安全处理） */
    private Map<String, Object> viewSummary(ViewDetailDTO detail) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", detail.getView().getId());
        view.put("code", nz(detail.getView().getCode()));
        view.put("name", nz(detail.getView().getName()));
        view.put("sourceType", nz(detail.getView().getSourceType()));
        view.put("readMode", nz(detail.getView().getReadMode()));
        view.put("status", nz(detail.getView().getStatus()));
        List<Map<String, Object>> fields = new ArrayList<>();
        for (ViewField f : detail.getFields()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fieldName", nz(f.getFieldName()));
            m.put("fieldType", nz(f.getFieldType()));
            m.put("description", nz(f.getDescription()));
            m.put("defaultValue", nz(f.getDefaultValue()));
            fields.add(m);
        }
        view.put("fields", fields);
        return view;
    }

    private String nz(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private int commonPrefix(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }
}
