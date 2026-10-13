package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 规则命中：DEAD 死规则 / HOT 过热规则 是策略腐化的两个典型信号 */
@Data
@TableName("mon_rule_stat")
public class MonRuleStat {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String ruleCode;
    private String ruleName;
    private String category;
    private Long hitCnt;
    private BigDecimal hitPct;
    private BigDecimal hitPctPrev;
    private String flag;
    private String period;
}
