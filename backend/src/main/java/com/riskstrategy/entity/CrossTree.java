package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("cross_tree")
public class CrossTree {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long strategyId;
    private String name;
    private String version;
    private String status;
    private String defaultConclusion;
    private String coverage;
    private Integer maxDimensions;
    private LocalDateTime createdAt;
}
