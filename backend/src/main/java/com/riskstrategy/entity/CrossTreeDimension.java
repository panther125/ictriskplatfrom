package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("cross_tree_dimension")
public class CrossTreeDimension {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long treeId;
    private String dimNo;
    private String dimName;
    private Integer valueCount;
    private String expr;
    private String binsJson;
}
