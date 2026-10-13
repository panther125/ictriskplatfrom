package com.riskstrategy.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        Strategy strategy = strategyMapper.selectById(strategyId);
        if (strategy == null) throw new RuntimeException("策略不存在: " + strategyId);
        Decision draft = decisionMapper.selectByStrategyId(strategyId).stream()
                .filter(d -> "DRAFT".equals(d.getStatus()))
                .findFirst().orElse(null);
        if (draft == null) draft = createDraft(strategyId);
        return graphOf(strategy, draft);
    }

    /** 全量保存画布：校验后替换该决策版本的全部节点与连线。 */
    @Transactional
    public DecisionGraphDTO saveGraph(Long decisionId, SaveDecisionGraphDTO req) {
        Decision decision = decisionMapper.selectById(decisionId);
        if (decision == null) throw new RuntimeException("决策版本不存在: " + decisionId);
        if (!"DRAFT".equals(decision.getStatus())) {
            throw new RuntimeException("该版本状态为 " + decision.getStatus() + "，已定版不可编辑，请新建草稿版本");
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

    private Decision createDraft(Long strategyId) {
        Decision d = new Decision();
        d.setStrategyId(strategyId);
        d.setVersionNo(nextVersionNo(strategyId));
        d.setStatus("DRAFT");
        d.setDescription("编排草稿");
        decisionMapper.insert(d);

        insertNode(d.getId(), "n_001", "START", "开始", 380, 60, "{}");
        insertNode(d.getId(), "n_002", "END", "结束", 380, 520, "{}");
        insertEdge(d.getId(), "n_001", null, "n_002", null);
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
