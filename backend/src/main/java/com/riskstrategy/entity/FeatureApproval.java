package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("feature_approval")
public class FeatureApproval {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long featureId;
    private Integer stepNo;
    private String stepName;
    private String approver;
    private String status;
    private String remark;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
}
