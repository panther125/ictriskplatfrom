package com.riskstrategy.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 策略内部版本项：版本有 草稿 / 已发布 / 已下线 三种状态，并展示每个版本承接的流量比例与切流方式。
 */
@Data
public class StrategyVersionItemDTO {
    private Long id;
    private Long strategyId;
    private String versionNo;
    private Integer rev;
    /** DRAFT / PUBLISHED / OFFLINE */
    private String status;
    /** 草稿 / 已发布 / 已下线 */
    private String statusText;
    private String stateLabel;
    private String bundle;
    private Integer changeCount;

    /** 流量比例 %：白名单切流下表示白名单覆盖占比，随机切流下表示随机放量占比 */
    private Integer trafficPct;
    /** RANDOM / PERCENT / WHITELIST */
    private String trafficMode;
    /** 随机切流 / 百分比切流 / 白名单切流 */
    private String trafficModeText;
    private Integer whitelistUsers;
    /** WHITELIST 模式：是否为兜底版本（未命中白名单的流量走这里） */
    private Boolean fallback;
    private String channel;
    private String userTag;

    private String submittedBy;
    private String publishedBy;
    private LocalDateTime publishedAt;
    private LocalDateTime updatedAt;
}
