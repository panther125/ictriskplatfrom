package com.riskstrategy.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 试算流量判定结果：按切流配置模拟本笔请求命中哪个版本。
 * 结构详见 docs/trial-traffic-route.md §3.6；手动选版本试算时该对象为 null。
 */
@Data
public class TrialRoutingDTO {

    /** 自动判定恒为 true（手动选版本时整个对象为 null） */
    private Boolean auto = Boolean.TRUE;
    /** RANDOM / PERCENT / WHITELIST */
    private String mode;
    private String modeText;
    /** 哈希试算键（固定 user_id） */
    private String bizKey;
    private String bizValue;
    /** 哈希槽位 0–99（非哈希口径为 null） */
    private Integer slot;
    private Long versionId;
    private String versionNo;
    private String statusText;
    /** 判定结论人读行 */
    private String reason;
    /** 无在线版本等无法判定：true，versionId 为空，试算回落「配置最完整版本」 */
    private Boolean fallback = Boolean.FALSE;
    private List<Candidate> candidates = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();

    @Data
    public static class Candidate {
        private Long versionId;
        private String versionNo;
        private String statusText;
        /** 份额（白名单口径为规则 traffic_pct，兜底=100） */
        private Integer pct;
        /** 白名单口径备注：白名单 N 人 / 兜底 */
        private String pctNote;
        /** 哈希区间文本（hash 0–29）；不参与或非哈希口径为 — */
        private String rangeText;
        private Boolean eligible;
        private Boolean hit;
        /** 不参与原因 / 备注 */
        private String note;
    }
}
