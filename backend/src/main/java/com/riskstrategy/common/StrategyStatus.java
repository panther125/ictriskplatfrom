package com.riskstrategy.common;

/**
 * 策略 / 版本状态口径的唯一出处。
 *
 * <p>库里 {@code strategy_version.status} 有 5 种取值（PUBLISHED / GRAYSCALE / PENDING_REVIEW /
 * DRAFT / ROLLED_BACK），但对外只暴露两种口径：
 * <ul>
 *   <li>策略列表：草稿 / 已发布</li>
 *   <li>版本列表：草稿 / 已发布 / 已下线</li>
 * </ul>
 * 历史数据里还有 {@code OFFLINE} 这种等价于 {@code ROLLED_BACK} 的写法，统一在此收敛。
 */
public final class StrategyStatus {

    private StrategyStatus() {
    }

    /** 是否承接线上流量（已发布 / 灰度中） */
    public static boolean isPublished(String status) {
        return "PUBLISHED".equals(status) || "GRAYSCALE".equals(status);
    }

    /** 版本三态：DRAFT / PUBLISHED / OFFLINE */
    public static String threeState(String status) {
        if (isPublished(status)) return "PUBLISHED";
        if ("ROLLED_BACK".equals(status) || "OFFLINE".equals(status)) return "OFFLINE";
        return "DRAFT";
    }

    public static String threeStateText(String status) {
        String s = threeState(status);
        if ("PUBLISHED".equals(s)) return "已发布";
        if ("OFFLINE".equals(s)) return "已下线";
        return "草稿";
    }

    /** 策略两态：DRAFT / PUBLISHED */
    public static String twoState(String status) {
        return isPublished(status) ? "PUBLISHED" : "DRAFT";
    }

    public static String twoStateText(String status) {
        return isPublished(status) ? "已发布" : "草稿";
    }

    /** 排序权重：越"线上"越大，试算选默认版本时用 */
    public static int rank(String status) {
        if (status == null) return 0;
        return switch (status) {
            case "PUBLISHED" -> 5;
            case "GRAYSCALE" -> 4;
            case "PENDING_REVIEW" -> 3;
            case "DRAFT" -> 2;
            case "ROLLED_BACK", "OFFLINE" -> 1;
            default -> 0;
        };
    }
}
