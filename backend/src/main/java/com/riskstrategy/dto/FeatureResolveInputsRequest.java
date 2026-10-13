package com.riskstrategy.dto;

import lombok.Data;

/**
 * 特征入参解析请求：按特征类型自动推导验证所需入参。
 *
 * <ul>
 *   <li>{@code BASE}：需要 {@code viewId}，返回该视图登记的全部入参；</li>
 *   <li>{@code DERIVED}：需要 {@code expr}，返回表达式里 {@code :param.x} 与
 *       {@code @@baseCode} 引用带入的入参并集。</li>
 * </ul>
 */
@Data
public class FeatureResolveInputsRequest {
    /** BASE / DERIVED */
    private String featureType;
    private Long viewId;
    private String viewCode;
    /** 基础特征引用的视图出参字段名（用于出参定义回显，不参与入参解析） */
    private String viewField;
    /** 衍生特征表达式，允许含 @@base_feature_code 与 :param.xxx */
    private String expr;
}
