package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("view_degrade_config")
public class ViewDegradeConfig {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long viewId;
    private String degradeMode;
    private String onFallback;
    private String cacheL1;
    private String cacheL2;
    private Integer cbEnabled;
    private BigDecimal cbErrorRate;
    private LocalDateTime updatedAt;
}
