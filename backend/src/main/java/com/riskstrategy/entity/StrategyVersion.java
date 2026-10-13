package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("strategy_version")
public class StrategyVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long strategyId;
    private String versionNo;
    private Integer rev;
    private String status;
    private String stateLabel;
    private String bundle;
    private Integer changeCount;
    private Integer grayscalePct;
    private String submittedBy;
    private String publishedBy;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
