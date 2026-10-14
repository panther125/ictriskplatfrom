package com.riskstrategy.dto;

import lombok.Data;

/**
 * 新增策略请求：策略本体 + 可选的初始画布来源。
 *
 * <p>新策略会自动落一个草稿版本（v1）：指定 inheritFromStrategyId 时完整复制该策略
 * 最新编排的节点、连线、坐标与规则配置，否则只种入「开始 / 结束」骨架。
 */
@Data
public class CreateStrategyDTO {

    /** 策略名称，如「逾期滚动率准入策略」，必填。 */
    private String name;
    /** 策略编码，小写字母/数字/下划线且以字母开头，如 loan_apply_v3，必填且全局唯一。 */
    private String code;
    /** 业务域，缺省「决策中心」。 */
    private String category;
    /** 策略说明，可选。 */
    private String description;
    /** 责任人，可选。 */
    private String owner;
    /** 初始画布的继承源策略 id（可选）：新策略 v1 复制该策略最新编排的画布。 */
    private Long inheritFromStrategyId;
}
