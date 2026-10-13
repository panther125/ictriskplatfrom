package com.riskstrategy.dto;

import lombok.Data;

/**
 * 特征验证时可用的一个输入变量。
 *
 * <p>三种来源：
 * <ul>
 *   <li>{@code VIEW}   —— 基础特征绑定的视图入参（$name 占位符），验证时直接用视图的入参</li>
 *   <li>{@code FEATURE}—— 衍生特征 {@code @@baseCode} 引用后，被引用基础特征所携带的视图入参（自动解析）</li>
 *   <li>{@code PARAM}  —— 衍生特征表达式里直接写的 {@code :param.xxx} 决策入参</li>
 * </ul>
 */
@Data
public class ResolvedInputDTO {
    /** 变量名：视图入参为变量名本身（如 mobile），决策入参为 param.xxx */
    private String name;
    private String type;
    /** VIEW / FEATURE / PARAM / BUILTIN_TIME */
    private String source;
    /** 人类可读来源描述，如「视图 risk_user_info 入参」「基础特征 @@user_blacklist_hit 的视图入参」 */
    private String sourceLabel;
    private Boolean required;
    private String defaultValue;
    /** 建议测试值（优先取视图入参默认值） */
    private String sampleValue;
    /** 来源指向：引用场景为基础特征编码；视图场景为视图编码 */
    private String fromRef;
    /** 同一变量名被多个来源命中时的附加来源（用于提示合并） */
    private java.util.List<String> mergedFrom;

    public ResolvedInputDTO() {}

    public ResolvedInputDTO(String name, String type, String source, String sourceLabel) {
        this.name = name;
        this.type = type;
        this.source = source;
        this.sourceLabel = sourceLabel;
    }
}
