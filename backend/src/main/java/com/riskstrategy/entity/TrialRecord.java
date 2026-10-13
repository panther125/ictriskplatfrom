package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("trial_record")
public class TrialRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceId;
    private Long strategyId;
    private String version;
    private String inputJson;
    private String conclusion;
    private String reasonCode;
    private String hitRule;
    private java.math.BigDecimal elapsedMs;
    private Integer degraded;
    private String env;
    private LocalDateTime createdAt;
}
