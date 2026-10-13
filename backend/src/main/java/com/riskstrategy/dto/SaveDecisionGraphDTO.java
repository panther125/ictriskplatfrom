package com.riskstrategy.dto;

import lombok.Data;

import java.util.List;

/** 保存画布请求：全量替换该决策版本的节点与连线。 */
@Data
public class SaveDecisionGraphDTO {
    private List<GraphNodeDTO> nodes;
    private List<GraphEdgeDTO> edges;
}
