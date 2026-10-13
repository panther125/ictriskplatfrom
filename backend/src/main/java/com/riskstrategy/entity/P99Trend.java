package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("p99_trend")
public class P99Trend {
    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDateTime ts;
    private java.math.BigDecimal valueMs;
    private Integer sloMs;
}
