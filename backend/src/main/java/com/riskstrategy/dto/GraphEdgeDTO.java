package com.riskstrategy.dto;

import lombok.Data;

/** 画布连线（decision_branch 表一行）。 */
@Data
public class GraphEdgeDTO {
    private String fromNode;
    private String fromPort;
    private String toNode;
    private String label;
}
