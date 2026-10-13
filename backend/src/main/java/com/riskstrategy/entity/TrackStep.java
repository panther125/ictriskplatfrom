package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("track_step")
public class TrackStep {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long trackId;
    private Integer stepNo;
    private String nodeId;
    private String nodeType;
    private String category;
    private String stepTitle;
    private java.math.BigDecimal elapsedMs;
}
