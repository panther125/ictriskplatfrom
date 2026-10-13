package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("trial_execution_step")
public class TrialExecutionStep {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long trialId;
    private Integer stepNo;
    private String stepTitle;
    private java.math.BigDecimal elapsedMs;
}
