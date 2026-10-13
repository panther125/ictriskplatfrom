package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("monitor_metric")
public class MonitorMetric {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String metricKey;
    private String metricName;
    @TableField("`value`")
    private java.math.BigDecimal value;
    private String unit;
    private String comparison;
    private String threshold;
    private String state;
    private LocalDateTime snapshotAt;
}
