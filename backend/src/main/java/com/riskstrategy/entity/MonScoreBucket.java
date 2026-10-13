package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 评分分布与分箱坏账：badRate 需单调递减，否则评分卡失效 */
@Data
@TableName("mon_score_bucket")
public class MonScoreBucket {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String modelCode;
    private String bucketLabel;
    private Integer sortNo;
    private BigDecimal basePct;
    private BigDecimal curPct;
    private Long cnt;
    private BigDecimal badRate;
}
