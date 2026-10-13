package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 决策连线（画布信息）：from_port 区分分支节点的多条出边。 */
@Data
@TableName("decision_branch")
public class DecisionBranch {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long decisionId;
    private String fromNode;
    private String fromPort;
    private String toNode;
    private String label;
}
