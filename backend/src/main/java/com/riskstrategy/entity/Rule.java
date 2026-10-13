package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 决策画布节点：坐标 + 节点内的规则配置（JSON）。 */
@Data
@TableName("rule")
public class Rule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long decisionId;
    private String nodeKey;
    private String nodeType;
    private String title;
    private Integer x;
    private Integer y;
    private String configJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
