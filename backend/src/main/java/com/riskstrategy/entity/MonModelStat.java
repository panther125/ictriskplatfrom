package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 模型监控：PSI / KS / AUC 与冠军-挑战者对比 */
@Data
@TableName("mon_model_stat")
public class MonModelStat {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String modelCode;
    private String modelName;
    private String version;
    private String role;
    private Long calls;
    private BigDecimal psi;
    private BigDecimal ks;
    private BigDecimal auc;
    private BigDecimal avgScore;
    private BigDecimal timeoutPct;
    private BigDecimal p99Ms;
    private String status;
    private LocalDateTime updatedAt;
}
