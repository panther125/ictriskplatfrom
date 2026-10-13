package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 贷后资产质量：Vintage 曲线，监控放出去的钱到底坏没坏 */
@Data
@TableName("mon_outcome")
public class MonOutcome {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String vintageMonth;
    private Integer mob;
    private BigDecimal fpd30Pct;
    private BigDecimal dpd30Pct;
    private BigDecimal cumBadPct;
}
