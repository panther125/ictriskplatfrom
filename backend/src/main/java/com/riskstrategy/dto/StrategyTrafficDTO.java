package com.riskstrategy.dto;

import lombok.Data;

import java.util.List;

/**
 * 决策级切流配置：一次给决策下的多个版本分配流量份额。
 * 例：随机切流 50% → v0.0.1、50% → v0.0.3。
 * 切流方式 / 渠道 / 客群对决策下所有参与版本统一生效。
 */
@Data
public class StrategyTrafficDTO {
    /** RANDOM 随机切流 / PERCENT 百分比切流 / WHITELIST 白名单切流 */
    private String mode;
    /** 限定渠道，如 APP_IOS */
    private String channel;
    /** 限定客群标签，如 VIP */
    private String userTag;
    /** 版本份额表：只包含可参与版本（已发布 / 已下线）；未列出的版本份额按 0 处理 */
    private List<Share> shares;

    @Data
    public static class Share {
        private Long versionId;
        /** RANDOM / PERCENT 模式：份额 0-100，参与版本合计必须 100 */
        private Integer trafficPct;
        /** WHITELIST 模式：该版本的白名单人数 */
        private Integer whitelistUsers;
        /** WHITELIST 模式：兜底版本（未命中白名单的流量走这里），有且仅有一个 */
        private Boolean fallback;
    }
}
