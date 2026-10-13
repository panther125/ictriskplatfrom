package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("trial_case")
public class TrialCase {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private Long strategyId;
    private String inputsJson;
    private String expectConclusion;
    private String tags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
