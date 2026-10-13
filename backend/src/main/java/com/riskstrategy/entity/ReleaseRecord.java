package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("release_record")
public class ReleaseRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String scopeType;
    private String operator;
    private String operatorType;
    private LocalDateTime occurredAt;
    private String detail;
}
