package com.riskstrategy.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 策略管理列表项：策略外部只暴露 草稿 / 已发布 两种状态，
 * 由该策略下的版本状态推导（存在 PUBLISHED / GRAYSCALE 版本即为已发布）。
 * 一个决策可有多个版本同时在线（切流份额），故在线版本为列表。
 */
@Data
public class StrategyItemDTO {
    private Long id;
    private String code;
    private String name;
    private String category;
    private String description;
    private String owner;
    /** DRAFT / PUBLISHED */
    private String status;
    /** 草稿 / 已发布 */
    private String statusText;
    private Integer versionCount;
    /** 在线版本（多版本并行切流，新→旧），未上线为空列表 */
    private List<OnlineVersion> onlineVersions;
    private LocalDateTime updatedAt;

    @Data
    public static class OnlineVersion {
        private String versionNo;
        /** 该版本承接的流量份额 % */
        private Integer trafficPct;
        /** RANDOM / PERCENT / WHITELIST */
        private String trafficMode;
        /** 白名单切流下的白名单人数 */
        private Integer whitelistUsers;
        /** 白名单切流下是否为兜底版本 */
        private Boolean fallback;
    }
}
