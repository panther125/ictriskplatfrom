package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 决策编排版本：每个策略的每个版本一行，管理画布对应的节点与连线。 */
@Data
@TableName("decision")
public class Decision {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long strategyId;
    private String versionNo;
    private String status;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
