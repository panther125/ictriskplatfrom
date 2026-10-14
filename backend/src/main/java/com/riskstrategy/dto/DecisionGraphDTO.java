package com.riskstrategy.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 决策编排图：版本信息 + 画布节点（rule）+ 连线（decision_branch）。 */
@Data
public class DecisionGraphDTO {
    private Long decisionId;
    /** 版本记录 id（strategy_version.id）：新增版本后前端据此跳转到该版本的编排页。 */
    private Long versionId;
    private Long strategyId;
    private String strategyCode;
    private String strategyName;
    private String versionNo;
    private String status;
    private String description;
    private LocalDateTime updatedAt;
    private List<GraphNodeDTO> nodes;
    private List<GraphEdgeDTO> edges;

    /**
     * 画布来源：EXACT（该版本自己留存的画布）/ FALLBACK（版本未留存，取该策略最新编排作参照）/ EMPTY（无内容）。
     * 仅「查看」语义的读取会填充；编排草稿读取为 null。
     */
    private String canvasSource;
    /** 画布来源说明：FALLBACK / EMPTY 时解释为什么看到的不是该版本的原始内容。 */
    private String canvasNote;
    /** 只读标记：查看已定版版本的画布时 true，前端据此禁用一切编辑入口。 */
    private Boolean readOnly;
    /** 本次读取派生出了新草稿时，记录被继承的源版本号（如「已基于 v11 新建草稿 v14」）。 */
    private String derivedFromVersionNo;
}
