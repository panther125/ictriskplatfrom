package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 业务趋势：通过率是银行最敏感的业务指标 */
@Data
@TableName("mon_business_trend")
public class MonBusinessTrend {
    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDateTime ts;
    private Long reqCnt;
    private BigDecimal passPct;
    private BigDecimal reviewPct;
    private BigDecimal rejectPct;
    private BigDecimal p99Ms;
    private BigDecimal errPct;
}
