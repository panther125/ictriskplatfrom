package com.riskstrategy.entity;

import lombok.Data;

/**
 * 特征入参定义项。
 *
 * <p>自 2026-10-13 起不再单独建表，而是序列化成 JSON 数组存在 {@code feature.input_vars_json} 里，
 * 随特征一起读写（与 {@link ViewInputVar} 对视图的存法一致）。因此这里只是普通 POJO，
 * 没有主键、也没有 featureId —— JSON 数组的位置本身就表达了归属与顺序。
 */
@Data
public class FeatureInputVar {
    private String varCode;
    private String varType;
    /**
     * FEATURE 基础特征引用 / VIEW 视图入参 / PARAM 决策入参 / BUILTIN_TIME 系统内置 /
     * MANUAL 用户在「入参定义」里手工补充
     */
    private String source;
    /** 是否必填（用户可在入参定义里调整） */
    private Boolean required;
    /** 默认测试值（用户可在入参定义里调整） */
    private String defaultValue;
    /** 是否参与验证：false = 用户已从入参清单里移除 */
    private Boolean enabled;
}
