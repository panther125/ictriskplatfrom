package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("rule_hit_stat")
public class RuleHitStat {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String ruleCode;
    private String ruleName;
    private Integer hitCount;
    private java.math.BigDecimal hitPct;
    private String period;
    /** rank 是 MySQL 保留字，自动生成的 SQL 需反引号转义 */
    @TableField("`rank`")
    private Integer rank;
}
