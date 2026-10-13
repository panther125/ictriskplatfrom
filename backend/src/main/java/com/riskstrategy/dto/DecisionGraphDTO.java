package com.riskstrategy.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 决策编排图：版本信息 + 画布节点（rule）+ 连线（decision_branch）。 */
@Data
public class DecisionGraphDTO {
    private Long decisionId;
    private Long strategyId;
    private String strategyCode;
    private String strategyName;
    private String versionNo;
    private String status;
    private String description;
    private LocalDateTime updatedAt;
    private List<GraphNodeDTO> nodes;
    private List<GraphEdgeDTO> edges;
}
