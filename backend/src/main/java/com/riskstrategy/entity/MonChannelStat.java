package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 渠道维度风险表现：不同渠道的客群质量差异很大 */
@Data
@TableName("mon_channel_stat")
public class MonChannelStat {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String channel;
    private String channelCode;
    private Long reqCnt;
    private BigDecimal passPct;
    private BigDecimal rejectPct;
    private BigDecimal fpd30Pct;
    private BigDecimal amountWan;
}
