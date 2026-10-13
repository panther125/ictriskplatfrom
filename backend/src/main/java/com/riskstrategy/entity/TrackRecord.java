package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("track_record")
public class TrackRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceId;
    private Long strategyId;
    private String strategyName;
    private String version;
    private String env;
    private String source;
    private String inputJson;
    private String conclusion;
    private String reasonCode;
    private String hitRule;
    private String hitPath;
    private java.math.BigDecimal totalElapsedMs;
    private java.math.BigDecimal engineElapsedMs;
    private Integer degraded;
    private java.math.BigDecimal totalScore;
    private String businessKey;
    private String channel;
    private String channelCode;
    private String samplingReason;
    private Integer degradeCount;
    private LocalDateTime createdAt;
}
