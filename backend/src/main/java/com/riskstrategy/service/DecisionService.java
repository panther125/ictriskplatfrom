package com.riskstrategy.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskstrategy.common.StrategyStatus;
import com.riskstrategy.dto.CreateVersionDTO;
import com.riskstrategy.dto.DecisionGraphDTO;
import com.riskstrategy.dto.GraphEdgeDTO;
import com.riskstrategy.dto.GraphNodeDTO;
import com.riskstrategy.dto.SaveDecisionGraphDTO;
import com.riskstrategy.entity.Decision;
import com.riskstrategy.entity.DecisionBranch;
import com.riskstrategy.entity.Rule;
import com.riskstrategy.entity.Strategy;
import com.riskstrategy.entity.StrategyVersion;
import com.riskstrategy.mapper.DecisionBranchMapper;
import com.riskstrategy.mapper.DecisionMapper;
import com.riskstrategy.mapper.RuleMapper;
import com.riskstrategy.mapper.StrategyMapper;
import com.riskstrategy.mapper.StrategyVersionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 策略编排：decision（版本）/ rule（画布节点）/ decision_branch（连线）。
 * 编排页进入时取该策略的最新草稿版本（无则新建并种入 开始 / 结束），保存为全量替换。
 */
@Service
public class DecisionService {

    private static final Set<String> NODE_TYPES = Set.of("START", "END", "BRANCH", "CASE", "CROSS", "NORMAL");
    private static final Pattern VERSION_NUM = Pattern.compile("^v(\\d+)$", Pattern.CASE_INSENSITIVE);

    private final DecisionMapper decisionMapper;
    private final RuleMapper ruleMapper;
    private final DecisionBranchMapper branchMapper;
    private final StrategyMapper strategyMapper;
    private final StrategyVersionMapper versionMapper;
    private final ObjectMapper objectMapper;

    public DecisionService(DecisionMapper decisionMapper,
                           RuleMapper ruleMapper,
                           DecisionBranchMapper branchMapper,
                           StrategyMapper strategyMapper,
                           StrategyVersionMapper versionMapper,
                           ObjectMapper objectMapper) {
        this.decisionMapper = decisionMapper;
        this.ruleMapper = ruleMapper;
        this.branchMapper = branchMapper;
        this.strategyMapper = strategyMapper;
        this.versionMapper = versionMapper;
        this.objectMapper = objectMapper;
    }

    /** 取某策略的最新编排草稿（无则新建并种入 开始/结束），返回完整画布。 */
    public DecisionGraphDTO draftGraph(Long strategyId) {
        return draftGraph(strategyId, null);
    }

    /**
     * 打开编排画布。
     *
     * <p>指定 versionId 时（版本列表点「编排」）打开该版本的画布；该版本没有留存画布
     * （历史版本 / 已下线版本）则基于它派生一个新草稿——口径统一为「任何修改都从新版本开始」。
     * versionId 为 null 时沿用「该策略最新的编排草稿」。
     */
    @Transactional
    public DecisionGraphDTO draftGraph(Long strategyId, Long versionId) {
        Strategy strategy = strategyMapper.selectById(strategyId);
        if (strategy == null) throw new RuntimeException("策略不存在: " + strategyId);

        if (versionId != null) {
            StrategyVersion base = versionMapper.selectById(versionId);
            if (base == null || !base.getStrategyId().equals(strategyId)) {
                throw new RuntimeException("版本不存在或不属于该策略: " + versionId);
            }
            Decision owned = decisionOfVersion(strategyId, base.getVersionNo());
            if (owned != null) {
                DecisionGraphDTO dto = graphOf(strategy, owned);
                dto.setVersionId(base.getId());
                dto.setReadOnly(false);
                return dto;
            }
            return deriveDraft(strategy, trim(base.getVersionNo()), null);
        }

        Decision draft = decisionMapper.selectByStrategyId(strategyId).stream()
                .filter(d -> "DRAFT".equals(d.getStatus()))
                .max(Comparator.comparingInt(d -> versionNum(d.getVersionNo())))
                .orElse(null);
        if (draft == null) draft = createDraft(strategyId);
        DecisionGraphDTO dto = graphOf(strategy, draft);
        dto.setVersionId(versionIdOf(strategyId, draft.getVersionNo()));
        dto.setReadOnly(false);
        return dto;
    }

    /**
     * 只读查看某版本的画布（不可修改）。
     *
     * <p>取数优先级：该版本自己留存的画布 → 该策略最新有画布的编排（参照，附说明）→ 空态。
     * 历史版本发布时平台未留存画布快照，降级参照能让用户至少看清当前口径大致长什么样，
     * 配合「新增版本」即可继承后再改。
     */
    public DecisionGraphDTO versionGraph(Long versionId) {
        StrategyVersion version = versionMapper.selectById(versionId);
        if (version == null) throw new RuntimeException("版本不存在: " + versionId);
        Strategy strategy = strategyMapper.selectById(version.getStrategyId());
        if (strategy == null) throw new RuntimeException("策略不存在: " + version.getStrategyId());

        String versionNo = trim(version.getVersionNo());
        Decision owned = decisionOfVersion(strategy.getId(), versionNo);

        DecisionGraphDTO dto;
        if (owned != null && !ruleMapper.selectByDecisionIdOrderById(owned.getId()).isEmpty()) {
            dto = graphOf(strategy, owned);
            dto.setCanvasSource("EXACT");
        } else {
            Decision latest = latestCanvas(strategy.getId());
            if (latest != null) {
                dto = graphOf(strategy, latest);
                dto.setCanvasSource("FALLBACK");
                dto.setCanvasNote("版本 " + versionNo + " 未留存独立画布快照，下方展示的是该策略最新的编排内容（"
                        + trim(latest.getVersionNo()) + "），与线上口径未必一致，仅供参照；"
                        + "如需在此基础上调整，请用「新增版本」继承后再编排。");
            } else {
                dto = blankGraphOf(strategy, versionNo);
                dto.setCanvasSource("EMPTY");
                dto.setCanvasNote("版本 " + versionNo + " 没有可展示的画布内容，该策略目前也还没有任何编排草稿。");
            }
        }
        // 以「版本」为准回填展示字段：查看的是版本，不是画布行
        dto.setVersionId(version.getId());
        dto.setVersionNo(versionNo);
        dto.setStatus(version.getStatus());
        dto.setReadOnly(true);
        return dto;
    }

    /**
     * 新增版本：新版本继承「上一个画布」的节点与连线配置，落一条草稿编排 + 一条草稿版本记录。
     *
     * <p>继承源：显式指定 baseVersionNo → 否则该策略最新版本 → 源版本未留存画布时退到最新画布。
     */
    @Transactional
    public DecisionGraphDTO createVersion(Long strategyId, CreateVersionDTO req) {
        Strategy strategy = strategyMapper.selectById(strategyId);
        if (strategy == null) throw new RuntimeException("策略不存在: " + strategyId);

        List<StrategyVersion> versions = versionMapper.selectByStrategyIdOrderById(strategyId);
        String baseVersionNo = trim(req == null ? null : req.getBaseVersionNo());
        if (!baseVersionNo.isEmpty()) {
            final String want = baseVersionNo;
            boolean exists = versions.stream().anyMatch(v -> want.equalsIgnoreCase(trim(v.getVersionNo())));
            if (!exists) throw new RuntimeException("继承源版本不存在: " + baseVersionNo);
        } else {
            baseVersionNo = versions.stream()
                    .max(Comparator.comparingInt(v -> versionNum(v.getVersionNo())))
                    .map(v -> trim(v.getVersionNo())).orElse("");
        }

        String description = trim(req == null ? null : req.getDescription());
        return deriveDraft(strategy, baseVersionNo.isEmpty() ? null : baseVersionNo,
                description.isEmpty() ? null : description);
    }

    /**
     * 新建策略时初始化第一个草稿版本（v1）。
     *
     * <p>指定 inheritFromStrategyId 时，完整复制该策略「最新有画布的编排」作为新策略的起点；
     * 否则只种入「开始 / 结束」骨架。返回的画布带 versionId，前端可直接跳进编排页。
     */
    @Transactional
    public DecisionGraphDTO bootstrap(Strategy strategy, Long inheritFromStrategyId) {
        Decision source = inheritFromStrategyId == null ? null : latestCanvas(inheritFromStrategyId);
        Decision d = seedDraft(strategy, "初始版本 · " + (source == null
                ? "空画布（开始 / 结束）"
                : "继承自策略 #" + inheritFromStrategyId + " 的最新编排"), source);
        DecisionGraphDTO dto = graphOf(strategy, d);
        dto.setVersionId(versionIdOf(strategy.getId(), d.getVersionNo()));
        dto.setReadOnly(false);
        return dto;
    }

    /** 全量保存画布：校验后替换该决策版本的全部节点与连线。 */
    @Transactional
    public DecisionGraphDTO saveGraph(Long decisionId, SaveDecisionGraphDTO req) {
        Decision decision = decisionMapper.selectById(decisionId);
        if (decision == null) throw new RuntimeException("决策版本不存在: " + decisionId);
        // 版本级冻结（与视图 / 特征的「发布即冻结」同口径）：
        // ① decision 自身状态非草稿；
        // ② 该画布的 versionNo 在 strategy_version 中对应已发布 / 灰度中的版本。
        // 两者满足其一即已定版。注意不能按「策略已发布」一锅端——线上跑着的时候
        // 仍要能编排新草稿版本（灰度发布的前提），冻的只是已上线的那个版本本身。
        if (!"DRAFT".equals(decision.getStatus())) {
            throw new RuntimeException("该版本状态为 " + decision.getStatus() + "，已定版不可编辑，请新建草稿版本");
        }
        if (isVersionFrozen(decision)) {
            throw new RuntimeException("版本 " + decision.getVersionNo()
                    + " 已发布上线，编排内容已定版不可修改；如需调整请先下线该版本，或新建草稿版本");
        }
        Strategy strategy = strategyMapper.selectById(decision.getStrategyId());
        if (strategy == null) throw new RuntimeException("策略不存在: " + decision.getStrategyId());

        List<GraphNodeDTO> nodes = req.getNodes() == null ? List.of() : req.getNodes();
        List<GraphEdgeDTO> edges = req.getEdges() == null ? List.of() : req.getEdges();

        Set<String> keys = new HashSet<>();
        for (GraphNodeDTO n : nodes) {
            String key = trim(n.getNodeKey());
            if (key.isEmpty()) throw new RuntimeException("存在缺少 nodeKey 的节点");
            if (!keys.add(key)) throw new RuntimeException("节点键重复: " + key);
            String type = trim(n.getNodeType()).toUpperCase(Locale.ROOT);
            if (!NODE_TYPES.contains(type)) {
                throw new RuntimeException("未知节点类型: " + n.getNodeType() + "（支持 START/END/BRANCH/CASE/CROSS/NORMAL）");
            }
            n.setNodeKey(key);
            n.setNodeType(type);
            if (trim(n.getTitle()).isEmpty()) n.setTitle(defaultTitle(type));
        }
        for (GraphEdgeDTO e : edges) {
            String from = trim(e.getFromNode());
            String to = trim(e.getToNode());
            if (!keys.contains(from) || !keys.contains(to)) {
                throw new RuntimeException("连线引用了不存在的节点: " + from + " → " + to);
            }
        }

        ruleMapper.delete(Wrappers.<Rule>lambdaQuery().eq(Rule::getDecisionId, decisionId));
        branchMapper.delete(Wrappers.<DecisionBranch>lambdaQuery().eq(DecisionBranch::getDecisionId, decisionId));

        for (GraphNodeDTO n : nodes) {
            Rule r = new Rule();
            r.setDecisionId(decisionId);
            r.setNodeKey(n.getNodeKey());
            r.setNodeType(n.getNodeType());
            r.setTitle(trim(n.getTitle()));
            r.setX(n.getX() == null ? 0 : n.getX());
            r.setY(n.getY() == null ? 0 : n.getY());
            r.setConfigJson(writeJson(n.getConfig()));
            ruleMapper.insert(r);
        }
        for (GraphEdgeDTO e : edges) {
            DecisionBranch b = new DecisionBranch();
            b.setDecisionId(decisionId);
            b.setFromNode(trim(e.getFromNode()));
            b.setFromPort(blankToNull(e.getFromPort()));
            b.setToNode(trim(e.getToNode()));
            b.setLabel(blankToNull(e.getLabel()));
            branchMapper.insert(b);
        }

        decision.setUpdatedAt(LocalDateTime.now());
        decisionMapper.updateById(decision);
        return graphOf(strategy, decision);
    }

    /* ==================== 内部实现 ==================== */

    /**
     * 派生新草稿：新建 decision 画布 + 对应的 strategy_version 草稿行，画布内容继承 baseVersionNo。
     *
     * <p>版本与画布用 (策略, 版本号) 对齐，因此新增版本必须同时落两边，否则版本列表看不到它。
     */
    private DecisionGraphDTO deriveDraft(Strategy strategy, String baseVersionNo, String description) {
        Long strategyId = strategy.getId();
        Decision source = baseVersionNo == null ? null : decisionOfVersion(strategyId, baseVersionNo);
        if (source == null || ruleMapper.selectByDecisionIdOrderById(source.getId()).isEmpty()) {
            // 源版本没留存画布（历史版本 / 已下线版本）→ 退到该策略最新有画布的编排
            source = latestCanvas(strategyId);
        }

        Decision d = seedDraft(strategy, description == null ? "新增版本 · 继承上一个画布配置" : description, source);

        DecisionGraphDTO dto = graphOf(strategy, d);
        dto.setVersionId(versionIdOf(strategyId, d.getVersionNo()));
        dto.setReadOnly(false);
        if (baseVersionNo != null) dto.setDerivedFromVersionNo(baseVersionNo);
        return dto;
    }

    /** 按版本号找对应的版本记录 id（新增版本后前端据此跳转到该版本的编排页）。 */
    private Long versionIdOf(Long strategyId, String versionNo) {
        String want = trim(versionNo);
        if (want.isEmpty()) return null;
        return versionMapper.selectByStrategyIdOrderById(strategyId).stream()
                .filter(v -> want.equalsIgnoreCase(trim(v.getVersionNo())))
                .map(StrategyVersion::getId)
                .findFirst().orElse(null);
    }

    /** 按版本号找画布行（大小写不敏感）。 */
    private Decision decisionOfVersion(Long strategyId, String versionNo) {
        String want = trim(versionNo);
        if (want.isEmpty()) return null;
        return decisionMapper.selectByStrategyId(strategyId).stream()
                .filter(d -> want.equalsIgnoreCase(trim(d.getVersionNo())))
                .findFirst().orElse(null);
    }

    /** 该策略「最新且真的画了东西」的编排，用作历史版本查看时的参照。 */
    private Decision latestCanvas(Long strategyId) {
        return decisionMapper.selectByStrategyId(strategyId).stream()
                .filter(d -> !ruleMapper.selectByDecisionIdOrderById(d.getId()).isEmpty())
                .max(Comparator.comparingInt(d -> versionNum(d.getVersionNo())))
                .orElse(null);
    }

    /** 画布内容整体复制：节点 + 连线（含坐标与规则配置 JSON），用于新版本继承。 */
    private void copyGraph(Long fromDecisionId, Long toDecisionId) {
        for (Rule r : ruleMapper.selectByDecisionIdOrderById(fromDecisionId)) {
            Rule n = new Rule();
            n.setDecisionId(toDecisionId);
            n.setNodeKey(r.getNodeKey());
            n.setNodeType(r.getNodeType());
            n.setTitle(r.getTitle());
            n.setX(r.getX());
            n.setY(r.getY());
            n.setConfigJson(r.getConfigJson());
            ruleMapper.insert(n);
        }
        for (DecisionBranch b : branchMapper.selectByDecisionIdOrderById(fromDecisionId)) {
            DecisionBranch e = new DecisionBranch();
            e.setDecisionId(toDecisionId);
            e.setFromNode(b.getFromNode());
            e.setFromPort(b.getFromPort());
            e.setToNode(b.getToNode());
            e.setLabel(b.getLabel());
            branchMapper.insert(e);
        }
    }

    /** 空画布壳：只带策略与版本信息，节点/连线为空。 */
    private DecisionGraphDTO blankGraphOf(Strategy s, String versionNo) {
        DecisionGraphDTO dto = new DecisionGraphDTO();
        dto.setStrategyId(s.getId());
        dto.setStrategyCode(s.getCode());
        dto.setStrategyName(s.getName());
        dto.setVersionNo(versionNo);
        dto.setNodes(List.of());
        dto.setEdges(List.of());
        return dto;
    }

    /**
     * 该画布版本是否已定版：strategy_version 里同策略、同版本号且处于已发布 / 灰度中。
     *
     * <p>decision.status 历史上只有 DRAFT 一种取值（无任何代码改写过它），
     * 单靠状态判断拦不住已上线版本，必须与 strategy_version 交叉核对。
     */
    private boolean isVersionFrozen(Decision decision) {
        String versionNo = decision.getVersionNo();
        if (versionNo == null || versionNo.isBlank()) return false;
        return versionMapper.selectByStrategyIdOrderById(decision.getStrategyId()).stream()
                .anyMatch(v -> versionNo.equalsIgnoreCase(v.getVersionNo().trim())
                        && StrategyStatus.isPublished(v.getStatus()));
    }

    private Decision createDraft(Long strategyId) {
        Strategy strategy = strategyMapper.selectById(strategyId);
        return seedDraft(strategy, "编排草稿", null);
    }

    /**
     * 落一条新草稿并种入画布内容：source 有内容则整体复制，否则种入「开始 / 结束」骨架。
     *
     * <p>新建策略（bootstrap）、新增版本（deriveDraft）、首次进入编排（createDraft）三条入口
     * 共用这一处口径，避免某一处漏种骨架或漏复制连线。
     */
    private Decision seedDraft(Strategy strategy, String description, Decision source) {
        Decision d = newDraftRow(strategy, description);
        if (source != null && !ruleMapper.selectByDecisionIdOrderById(source.getId()).isEmpty()) {
            copyGraph(source.getId(), d.getId());
        } else {
            insertNode(d.getId(), "n_001", "START", "开始", 380, 60, "{}");
            insertNode(d.getId(), "n_002", "END", "结束", 380, 520, "{}");
            insertEdge(d.getId(), "n_001", null, "n_002", null);
        }
        return d;
    }

    /**
     * 新建一条草稿：decision 画布行 + 对应的 strategy_version 草稿行（两者版本号一致）。
     *
     * <p>画布与版本必须成对落库——否则版本管理页看不到这个草稿，也就无法对它切流与发布。
     */
    private Decision newDraftRow(Strategy strategy, String description) {
        Long strategyId = strategy.getId();
        Decision d = new Decision();
        d.setStrategyId(strategyId);
        d.setVersionNo(nextVersionNo(strategyId));
        d.setStatus("DRAFT");
        d.setDescription(description);
        decisionMapper.insert(d);

        StrategyVersion sv = new StrategyVersion();
        sv.setStrategyId(strategyId);
        sv.setVersionNo(d.getVersionNo());
        sv.setRev(0);
        sv.setStatus("DRAFT");
        sv.setStateLabel("草稿");
        sv.setGrayscalePct(0);
        sv.setSubmittedBy(strategy.getOwner());
        versionMapper.insert(sv);
        return d;
    }

    /** 新版本号 = 决策表与策略版本表中该策略的最大 vN + 1（两套版本号不撞车）。 */
    private String nextVersionNo(Long strategyId) {
        int max = 0;
        for (Decision d : decisionMapper.selectByStrategyId(strategyId)) {
            max = Math.max(max, versionNum(d.getVersionNo()));
        }
        for (StrategyVersion v : versionMapper.selectByStrategyIdOrderById(strategyId)) {
            max = Math.max(max, versionNum(v.getVersionNo()));
        }
        return "v" + (max + 1);
    }

    private int versionNum(String versionNo) {
        if (versionNo == null) return 0;
        Matcher m = VERSION_NUM.matcher(versionNo.trim());
        return m.matches() ? Integer.parseInt(m.group(1)) : 0;
    }

    private void insertNode(Long decisionId, String key, String type, String title, int x, int y, String configJson) {
        Rule r = new Rule();
        r.setDecisionId(decisionId);
        r.setNodeKey(key);
        r.setNodeType(type);
        r.setTitle(title);
        r.setX(x);
        r.setY(y);
        r.setConfigJson(configJson);
        ruleMapper.insert(r);
    }

    private void insertEdge(Long decisionId, String fromNode, String fromPort, String toNode, String label) {
        DecisionBranch b = new DecisionBranch();
        b.setDecisionId(decisionId);
        b.setFromNode(fromNode);
        b.setFromPort(fromPort);
        b.setToNode(toNode);
        b.setLabel(label);
        branchMapper.insert(b);
    }

    private DecisionGraphDTO graphOf(Strategy s, Decision d) {
        DecisionGraphDTO dto = new DecisionGraphDTO();
        dto.setDecisionId(d.getId());
        dto.setStrategyId(s.getId());
        dto.setStrategyCode(s.getCode());
        dto.setStrategyName(s.getName());
        dto.setVersionNo(d.getVersionNo());
        dto.setStatus(d.getStatus());
        dto.setDescription(d.getDescription());
        dto.setUpdatedAt(d.getUpdatedAt());

        List<GraphNodeDTO> nodes = new ArrayList<>();
        for (Rule r : ruleMapper.selectByDecisionIdOrderById(d.getId())) {
            GraphNodeDTO n = new GraphNodeDTO();
            n.setNodeKey(r.getNodeKey());
            n.setNodeType(r.getNodeType());
            n.setTitle(r.getTitle());
            n.setX(r.getX());
            n.setY(r.getY());
            n.setConfig(parseJson(r.getConfigJson()));
            nodes.add(n);
        }
        List<GraphEdgeDTO> edges = new ArrayList<>();
        for (DecisionBranch b : branchMapper.selectByDecisionIdOrderById(d.getId())) {
            GraphEdgeDTO e = new GraphEdgeDTO();
            e.setFromNode(b.getFromNode());
            e.setFromPort(b.getFromPort());
            e.setToNode(b.getToNode());
            e.setLabel(b.getLabel());
            edges.add(e);
        }
        dto.setNodes(nodes);
        dto.setEdges(edges);
        return dto;
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private String writeJson(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("节点配置序列化失败：" + e.getMessage());
        }
    }

    private static String defaultTitle(String type) {
        return switch (type) {
            case "START" -> "开始";
            case "END" -> "结束";
            case "BRANCH" -> "分支";
            case "CASE" -> "决策";
            case "CROSS" -> "交叉决策";
            default -> "普通决策";
        };
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String blankToNull(String s) {
        String t = trim(s);
        return t.isEmpty() ? null : t;
    }
}
