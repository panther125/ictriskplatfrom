package com.riskstrategy.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class FeatureTrialDTO {
    private Object outputs;
    private Boolean success;
    private Long elapsedMs;

    // ---- 特征验证上下文（验证时把「用哪些入参、引用了谁」一并回显） ----
    private String featureCode;
    /** BASE / DERIVED */
    private String featureType;
    /** VIEW_EXEC（基础特征：真实跑绑定视图）/ EXPR_EXEC（衍生特征：实测引用值 + 引擎真实执行表达式） */
    private String mode;
    private String viewCode;
    private String viewField;
    /** 验证时实际使用的入参（含来源标注：视图入参 / 引用基础特征的入参 / 决策入参） */
    private List<ResolvedInputDTO> resolvedInputs;
    /** 衍生特征表达式里的 @@ 引用解析结果 */
    private List<FeatureRefDTO> refs;
    /** 每个被引用基础特征的实测取值（引用编码 → 值） */
    private Map<String, Object> refValues;
    /** 表达式回显：@@code 用实侧值替换后的样子 */
    private String exprPreview;
    /** 基础特征：绑定视图的原始验证返回（含实际请求 / 响应预览） */
    private Object viewValidation;
    /** 衍生特征：表达式是否未真实执行；四引擎接入后恒为 false（保留字段供前端兼容标注） */
    private Boolean simulated;
    /** 衍生特征：实际执行的引擎 SQL / JAVA / PYTHON / OP */
    private String engine;
    /** 衍生特征：实算输出值（单出参时的原始返回值；多出参时为表达式返回的整个结果） */
    private Object derivedValue;
    private String derivedValueType;
    /**
     * 按「出参定义」逐项取到的值：每项 {name, type, value, missing}。
     * 表达式返回结构化结果（dict / Map / 多列）时按出参名匹配；只返回单值时归到第 1 个出参。
     */
    private List<Map<String, Object>> outputResults;
    /** 衍生特征：引擎执行耗时（不含引用基础特征取数耗时） */
    private Long execElapsedMs;
    private List<String> warnings;
    private String error;
}
