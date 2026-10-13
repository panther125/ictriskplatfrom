package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 特征稳定性：PSI 漂移 / 缺失率 / 计算耗时 */
@Data
@TableName("mon_feature_stat")
public class MonFeatureStat {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String featCode;
    private String featName;
    private Long calls;
    private BigDecimal nullPct;
    private BigDecimal defaultHitPct;
    private BigDecimal psi;
    private String psiLevel;
    private BigDecimal avgMs;
    private BigDecimal p99Ms;
    private String status;
    private LocalDateTime updatedAt;
}
