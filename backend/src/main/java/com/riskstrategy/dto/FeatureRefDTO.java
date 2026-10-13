package com.riskstrategy.dto;

import lombok.Data;

import java.util.List;

/**
 * 衍生特征表达式中 {@code @@base_feature_code} 的一条引用解析结果。
 */
@Data
public class FeatureRefDTO {
    /** 引用出现原文，如 @@user_overdue_cnt */
    private String raw;
    private String code;
    private Long id;
    private String name;
    private String featureType;
    private String dataType;
    /** 被引用基础特征绑定的视图 */
    private String viewCode;
    private String viewField;
    /** 是否解析成功 */
    private Boolean resolved;
    /** 未解析成功的原因 */
    private String message;
    /** 该引用带入的入参（被引用基础特征 → 其视图入参） */
    private List<ResolvedInputDTO> inputs;
}
