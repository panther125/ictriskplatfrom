package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("track_sampling_config")
public class TrackSamplingConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String configKey;
    private String ratioSampling;
    private Integer errorAutoCapture;
    private Integer slowThresholdMs;
    private String retention;
    private LocalDateTime updatedAt;
}
