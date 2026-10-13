package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("feature")
public class Feature {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String featureType;
    private String computeType;
    private String dataType;
    private String status;
    private String businessDesc;
    private String sqlExpr;
    /** 【基础特征】绑定的视图 id，1:1 引用该视图的一个出参 */
    private Long viewId;
    /** 【基础特征】绑定视图编码（创建时快照） */
    private String viewCode;
    /** 【基础特征】引用的视图出参字段名，即特征输出值 */
    private String viewField;
    /** 【衍生特征】加工表达式，用 @@base_feature_code 引用基础特征 */
    private String expr;
    private String missingPolicy;
    private Integer timeoutMs;
    private Integer refCount;
    private java.math.BigDecimal emptyRate;
    private java.math.BigDecimal emptyBase;
    private String owner;
    private Integer inputCount;
    private Integer outputCount;
    /**
     * 入参定义（JSON 数组字符串）：<code>[{varCode,varType,source,required,defaultValue,enabled}]</code>。
     * 取代原来的 feature_input_var 子表；入参的默认测试值存在各项的 defaultValue 字段里。
     */
    private String inputVarsJson;
    /**
     * 出参定义（JSON 数组字符串）：<code>[{varCode,varType,description,sortNo}]</code>。
     * 取代原来的 feature_output_var 子表；第 1 个出参的类型即本特征的 dataType。
     */
    private String outputVarsJson;
    private String verifyStatus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long deleted;
}
