package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("health_status")
public class HealthStatus {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String targetType;
    private String targetCode;
    private String targetName;
    private String status;
    private String metric;
    private String detail;
    private LocalDateTime updatedAt;
}
