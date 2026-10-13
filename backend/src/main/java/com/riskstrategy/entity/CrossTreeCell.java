package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("cross_tree_cell")
public class CrossTreeCell {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long treeId;
    private String sliceName;
    private String rowKey;
    private String colKey;
    private String conclusion;
    private Integer score;
    private String remark;
}
