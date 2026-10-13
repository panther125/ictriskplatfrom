package com.riskstrategy.entity;

import lombok.Data;

/**
 * 特征输出变量：一个特征可以有多个出参。
 *
 * <p>基础特征固定 1 个（引用视图字段），衍生特征由用户在「出参定义」里自由增删改。
 * 验证时按这份清单逐项展示取值。
 *
 * <p>自 2026-10-13 起不再单独建表，而是序列化成 JSON 数组存在 {@code feature.output_vars_json} 里，
 * 随特征一起读写。因此这里只是普通 POJO，没有主键与 featureId。
 */
@Data
public class FeatureOutputVar {
    /** 出参名，如 result / risk_level */
    private String varCode;
    /** STRING / INTEGER / LONG / DECIMAL / BOOLEAN / DATETIME */
    private String varType;
    private String description;
    private Integer sortNo;
}
