package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("decision_node")
public class DecisionNode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long versionId;
    private String nodeId;
    private String nodeType;
    private String title;
    private Integer x;
    private Integer y;
    private String conditionExpr;
    private String conclusion;
    private String reasonCode;
    private Integer priority;
    private String refVarsJson;
    private String paramsJson;
}
