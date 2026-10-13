package com.riskstrategy.service;

import com.riskstrategy.common.StrategyStatus;
import com.riskstrategy.dto.TrialRoutingDTO;
import com.riskstrategy.entity.GrayscaleRule;
import com.riskstrategy.entity.StrategyVersion;
import com.riskstrategy.mapper.GrayscaleRuleMapper;
import com.riskstrategy.mapper.StrategyVersionMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 试算流量判定：按策略当前的切流配置（{@code strategy_version} 状态 + {@code grayscale_rule}）
 * 模拟本笔试算请求会命中哪个版本。纯读取、不改库。
 *
 * <p>口径（docs/trial-traffic-route.md §三）：
 * <ul>
 *   <li>候选 = 状态承接流量（已发布 / 灰度中）的版本；待审 / 草稿 / 已下线不参与（卡片灰显注明原因）；</li>
 *   <li>规则限定渠道 / 客群与请求不匹配 → 本轮不参与；全部被过滤时忽略限定继续判定（置 ⚠）；</li>
 *   <li>单版本直接全量命中；RANDOM / PERCENT 统一按 {@code md5(user_id) 前 8 位 % 100} 定槽位
 *       （送审决策 D2：可复现优先）；白名单明细未存库 → 模拟按兜底版本执行；</li>
 *   <li>份额合计 ≠ 100 / 模式混存等脏数据 → 归一化继续并置 ⚠，绝不抛异常阻断试算。</li>
 * </ul>
 */
@Service
public class TrafficRouter {

    private final StrategyVersionMapper versionMapper;
    private final GrayscaleRuleMapper grayscaleRuleMapper;

    public TrafficRouter(StrategyVersionMapper versionMapper, GrayscaleRuleMapper grayscaleRuleMapper) {
        this.versionMapper = versionMapper;
        this.grayscaleRuleMapper = grayscaleRuleMapper;
    }

    public TrialRoutingDTO route(Long strategyId, Map<String, Object> params) {
        Map<String, Object> p = params == null ? Map.of() : params;
        TrialRoutingDTO dto = new TrialRoutingDTO();
        dto.setBizKey("user_id");
        String bizValue = strOf(p.get("user_id"));
        dto.setBizValue(bizValue);

        // 新→旧：与切流配置的行序、区间累加方向一致
        List<StrategyVersion> newestFirst = new ArrayList<>(versionMapper.selectByStrategyIdOrderById(strategyId));
        java.util.Collections.reverse(newestFirst);

        List<Row> rows = new ArrayList<>();
        List<Row> online = new ArrayList<>();
        List<Row> filtered = new ArrayList<>();
        for (StrategyVersion v : newestFirst) {
            GrayscaleRule rule = activeRule(v.getId());
            Row row = new Row(v, rule);
            rows.add(row);
            if (!StrategyStatus.isPublished(v.getStatus())) {
                row.c.setNote(notOnlineNote(v.getStatus()));
                continue;
            }
            String limit = limitMismatch(rule, p);
            if (limit != null) {
                row.c.setNote(limit);
                row.c.setEligible(Boolean.FALSE);
                filtered.add(row);
                continue;
            }
            online.add(row);
        }
        dto.setCandidates(rows.stream().map(r -> r.c).collect(java.util.stream.Collectors.toList()));

        if (online.isEmpty() && filtered.isEmpty()) {
            dto.setFallback(Boolean.TRUE);
            dto.setReason("该策略没有在线版本（均已下线或未发布），按配置最完整的版本试算");
            return dto;
        }
        if (online.isEmpty()) {
            dto.getWarnings().add("全部在线版本均被渠道 / 客群限定过滤，忽略限定条件继续判定");
            online = filtered;
            for (Row r : online) {
                r.c.setNote(null);
                r.c.setEligible(Boolean.TRUE);
            }
        }

        // 模式：取新→旧第一个；混存为脏数据，告警后按它继续
        String mode = online.get(0).mode;
        Set<String> modes = new LinkedHashSet<>();
        for (Row r : online) modes.add(r.mode);
        if (modes.size() > 1) {
            dto.getWarnings().add("在线版本切流方式混存（" + String.join(" / ", modes) + "），按最新版本的方式判定");
        }
        dto.setMode(mode);
        dto.setModeText(modeTextOf(mode));

        if (online.size() == 1) {
            Row only = online.get(0);
            only.c.setRangeText("全量");
            return hit(dto, only, "仅一个线上版本，全量命中 " + only.v.getVersionNo()
                    + "（" + shareText(only) + "）");
        }

        if ("WHITELIST".equals(mode)) {
            Row fb = null;
            for (Row r : online) {
                if (r.rule != null && r.rule.getTrafficPct() != null && r.rule.getTrafficPct() >= 100) {
                    fb = r;
                    break;
                }
            }
            if (fb == null) {
                fb = online.get(0);
                dto.getWarnings().add("白名单切流未配置兜底版本（脏数据），模拟按最新在线版本执行");
            } else {
                dto.getWarnings().add("白名单明细未存库（规则只存人数），模拟按兜底版本 "
                        + fb.v.getVersionNo() + " 执行");
            }
            fb.c.setRangeText("兜底");
            return hit(dto, fb, "白名单切流：白名单明细未存库，模拟按兜底版本 " + fb.v.getVersionNo() + " 执行");
        }

        // PERCENT / RANDOM：统一哈希口径（可复现），区间按 新→旧 累加
        int total = 0;
        for (Row r : online) total += r.share;
        if (total <= 0) {
            dto.getWarnings().add("在线版本份额合计为 0（脏数据），模拟按最新在线版本执行");
            return hit(dto, online.get(0), "份额合计为 0，模拟按最新在线版本 " + online.get(0).v.getVersionNo() + " 执行");
        }
        if (total != 100) {
            dto.getWarnings().add("各版本份额合计为 " + total + "%，已按比例归一化模拟");
        }
        if (bizValue == null) {
            dto.getWarnings().add("试算入参缺少 user_id，按固定缺省键哈希（建议补上 user_id 以复现真实命中）");
        }
        int slot = hashSlot(bizValue == null ? "" : bizValue);
        dto.setSlot(slot);

        Row hit = null;
        int start = 0;
        int hitStart = 0;
        int hitEnd = 0;
        for (int i = 0; i < online.size(); i++) {
            Row r = online.get(i);
            int end = i == online.size() - 1 ? 100
                    : Math.min(100, start + (int) Math.round(100.0 * r.share / total));
            if (end < start) end = start;
            r.c.setRangeText("hash " + start + "–" + (end - 1));
            if (hit == null && slot >= start && slot < end) {
                hit = r;
                hitStart = start;
                hitEnd = end;
            }
            start = end;
        }
        if (hit == null) {
            // 归一化取整的极端兜底：槽位落在空段时给出最后一个版本，不让试算中断
            hit = online.get(online.size() - 1);
            hitStart = Math.min(slot, 99);
            hitEnd = 100;
        }
        String keyText = bizValue == null ? "（缺省键）" : bizValue;
        return hit(dto, hit, "user_id = " + keyText + " → 哈希槽位 " + slot + " ∈ [" + hitStart + ", " + hitEnd
                + ") → 命中 " + hit.v.getVersionNo() + "（份额 " + hit.share + "%）");
    }

    private TrialRoutingDTO hit(TrialRoutingDTO dto, Row row, String reason) {
        row.c.setHit(Boolean.TRUE);
        dto.setVersionId(row.v.getId());
        dto.setVersionNo(row.v.getVersionNo());
        dto.setStatusText(row.c.getStatusText());
        dto.setReason(reason);
        return dto;
    }

    /** 渠道 / 客群限定不匹配时返回人读原因，匹配（或无限定）返回 null */
    private static String limitMismatch(GrayscaleRule rule, Map<String, Object> p) {
        if (rule == null) return null;
        String rChannel = blankToNull(rule.getChannel());
        if (rChannel != null && !rChannel.equalsIgnoreCase(nvl(strOf(p.get("channel"))))) {
            return "限定渠道 " + rChannel + "，与请求渠道 " + reqText(p.get("channel")) + " 不符";
        }
        String rTag = blankToNull(rule.getUserTag());
        if (rTag != null && !rTag.equalsIgnoreCase(nvl(strOf(p.get("user_tag"))))) {
            return "限定客群 " + rTag + "，与请求客群 " + reqText(p.get("user_tag")) + " 不符";
        }
        return null;
    }

    private static String reqText(Object v) {
        String s = strOf(v);
        return s == null ? "（未提供）" : s;
    }

    private static String notOnlineNote(String status) {
        if ("PENDING_REVIEW".equals(status)) return "待审批，不参与切流";
        if ("ROLLED_BACK".equals(status) || "OFFLINE".equals(status)) return "已下线，不参与切流";
        return "草稿，不参与切流";
    }

    private static String preciseStatusText(String status) {
        if ("PENDING_REVIEW".equals(status)) return "待审批";
        if ("ROLLED_BACK".equals(status) || "OFFLINE".equals(status)) return "已下线";
        return "草稿";
    }

    private String shareText(Row row) {
        return "WHITELIST".equals(row.mode) ? String.valueOf(row.c.getPctNote()) : row.share + "%";
    }

    private GrayscaleRule activeRule(Long versionId) {
        for (GrayscaleRule r : grayscaleRuleMapper.selectByVersionId(versionId)) {
            if (r.getStatus() == null || "ACTIVE".equalsIgnoreCase(r.getStatus())) return r;
        }
        return null;
    }

    /** 与 StrategyService.safeMode 同口径：脏数据退化为百分比切流，不抛异常 */
    private static String safeMode(String mode) {
        if (mode == null || mode.trim().isEmpty()) return "PERCENT";
        String m = mode.trim().toUpperCase(Locale.ROOT);
        if ("RANDOM".equals(m) || "WHITELIST".equals(m)) return m;
        return "PERCENT";
    }

    private static String modeTextOf(String mode) {
        if ("RANDOM".equals(mode)) return "随机切流";
        if ("WHITELIST".equals(mode)) return "白名单切流";
        return "百分比切流";
    }

    /** md5(key) 前 8 位 hex → 取模 100；与文档 §3.4、演示数据（U100823 → 63）一致 */
    private static int hashSlot(String key) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(key.getBytes(StandardCharsets.UTF_8));
            long v = 0;
            for (int i = 0; i < 4; i++) v = (v << 8) | (d[i] & 0xFFL);
            return (int) Math.floorMod(v, 100);
        } catch (NoSuchAlgorithmException e) {
            return Math.floorMod(key.hashCode(), 100);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String strOf(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    /** 内部行：版本 + 规则 + 卡片项 + 模式 / 份额口径 */
    private final class Row {
        final StrategyVersion v;
        final GrayscaleRule rule;
        final TrialRoutingDTO.Candidate c = new TrialRoutingDTO.Candidate();
        final String mode;
        final int share;

        Row(StrategyVersion v, GrayscaleRule rule) {
            this.v = v;
            this.rule = rule;
            this.mode = safeMode(rule == null ? null : rule.getMode());
            c.setVersionId(v.getId());
            c.setVersionNo(v.getVersionNo());
            // 不参与切流的版本给精确文案：对外三态「草稿」会掩盖待审批 / 已下线的具体原因
            c.setStatusText(StrategyStatus.isPublished(v.getStatus())
                    ? StrategyStatus.threeStateText(v.getStatus())
                    : preciseStatusText(v.getStatus()));
            c.setEligible(StrategyStatus.isPublished(v.getStatus()));
            c.setHit(Boolean.FALSE);
            c.setRangeText("—");

            if ("WHITELIST".equals(mode)) {
                int pct = rule != null && rule.getTrafficPct() != null ? rule.getTrafficPct() : 0;
                boolean fb = rule != null && rule.getTrafficPct() != null && rule.getTrafficPct() >= 100;
                int wl = rule != null && rule.getWhitelistUsers() != null ? rule.getWhitelistUsers() : 0;
                c.setPct(pct);
                c.setPctNote(fb ? "兜底" : "白名单 " + wl + " 人");
            } else {
                int pct = rule != null && rule.getTrafficPct() != null ? rule.getTrafficPct()
                        : (v.getGrayscalePct() == null ? 0 : v.getGrayscalePct());
                if (pct <= 0 && StrategyStatus.isPublished(v.getStatus()) && rule == null) {
                    // 发布即全量：publishVersion 不建规则，此时按版本自身口径取 100
                    pct = 100;
                }
                c.setPct(pct);
            }
            this.share = c.getPct() == null ? 0 : c.getPct();
        }
    }
}
