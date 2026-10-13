package com.riskstrategy.dto;

import com.riskstrategy.entity.DecisionNode;
import com.riskstrategy.entity.NodeEdge;
import com.riskstrategy.entity.StrategyVersion;
import lombok.Data;
import java.util.List;

@Data
public class StrategyGraphDTO {
    private StrategyVersion version;
    private List<DecisionNode> nodes;
    private List<NodeEdge> edges;
    private boolean valid;
    private int warningCount;

    public StrategyGraphDTO(StrategyVersion version, List<DecisionNode> nodes, List<NodeEdge> edges) {
        this.version = version;
        this.nodes = nodes;
        this.edges = edges;
        this.valid = true;
        this.warningCount = 2;
    }
}
