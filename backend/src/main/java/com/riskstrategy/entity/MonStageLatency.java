package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 全链路分段耗时：端到端 P99 是怎么花掉的 */
@Data
@TableName("mon_stage_latency")
public class MonStageLatency {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String stageCode;
    private String stageName;
    private String layer;
    private Integer sortNo;
    private Long calls;
    private BigDecimal p50Ms;
    private BigDecimal p95Ms;
    private BigDecimal p99Ms;
    private BigDecimal errPct;
    private BigDecimal timeoutPct;
    private Integer parallel;
    private String note;
    private LocalDateTime updatedAt;
}
