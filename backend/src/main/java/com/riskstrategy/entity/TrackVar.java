package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("track_var")
public class TrackVar {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long trackId;
    private String namespace;
    private String varName;
    private String varValue;
    private Integer stepNo;
}
