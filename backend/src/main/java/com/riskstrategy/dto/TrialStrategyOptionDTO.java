package com.riskstrategy.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 试算中心的策略选项：策略 + 该策略下可试算的版本清单。
 * 前端据此渲染「策略编码」下拉与「试算版本」下拉，避免写死 strategy_id。
 */
@Data
public class TrialStrategyOptionDTO {

    private Long id;
    /** 策略编码，试算接口按它定位策略（如 loan_apply） */
    private String code;
    private String name;
    private String category;
    private String description;
    private String owner;
    /** 策略对外状态：DRAFT 草稿 / PUBLISHED 已发布 */
    private String status;
    private String statusText;

    /** 默认试算版本：优先「登记了决策图的版本」，其次状态更靠前的版本 */
    private Long defaultVersionId;
    private String defaultVersionNo;

    private List<VersionOption> versions = new ArrayList<>();

    @Data
    public static class VersionOption {
        private Long id;
        private String versionNo;
        private Integer rev;
        /** 三态：DRAFT / PUBLISHED / OFFLINE */
        private String status;
        private String statusText;
        private String stateLabel;
        private String bundle;
        private Integer grayscalePct;
        /** 该版本登记的决策节点数，0 表示未编排（试算时走默认规则集） */
        private Integer nodeCount;
    }
}
