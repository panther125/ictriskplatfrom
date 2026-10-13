package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("node_edge")
public class NodeEdge {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long versionId;
    private String fromNode;
    private String toNode;
    private String label;
    private String fromPort;
    private String toPort;
}
