package com.riskstrategy.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.riskstrategy.common.StrategyStatus;
import com.riskstrategy.dto.StrategyGraphDTO;
import com.riskstrategy.dto.StrategyItemDTO;
import com.riskstrategy.dto.StrategyTrafficDTO;
import com.riskstrategy.dto.StrategyVersionItemDTO;
import com.riskstrategy.entity.DecisionNode;
import com.riskstrategy.entity.GrayscaleRule;
import com.riskstrategy.entity.NodeEdge;
import com.riskstrategy.entity.Strategy;
import com.riskstrategy.entity.StrategyVersion;
import com.riskstrategy.mapper.DecisionNodeMapper;
import com.riskstrategy.mapper.GrayscaleRuleMapper;
import com.riskstrategy.mapper.NodeEdgeMapper;
import com.riskstrategy.mapper.StrategyMapper;
import com.riskstrategy.mapper.StrategyVersionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class StrategyService {

    private final StrategyMapper strategyMapper;
    private final StrategyVersionMapper versionMapper;
    private final DecisionNodeMapper nodeMapper;
    private final NodeEdgeMapper edgeMapper;
    private final GrayscaleRuleMapper grayscaleRuleMapper;

    public StrategyService(StrategyMapper strategyMapper,
                           StrategyVersionMapper versionMapper,
                           DecisionNodeMapper nodeMapper,
                           NodeEdgeMapper edgeMapper,
                           GrayscaleRuleMapper grayscaleRuleMapper) {
        this.strategyMapper = strategyMapper;
        this.versionMapper = versionMapper;
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.grayscaleRuleMapper = grayscaleRuleMapper;
    }

    public List<Strategy> list() {
        return strategyMapper.selectList(null);
    }

    public List<StrategyVersion> versions(Long strategyId) {
        return versionMapper.selectByStrategyIdOrderById(strategyId);
    }

    public StrategyGraphDTO graph(Long versionId) {
        StrategyVersion version = versionMapper.selectById(versionId);
        if (version == null) throw new RuntimeException("版本不存在: " + versionId);
        List<DecisionNode> nodes = nodeMapper.selectByVersionIdOrderById(versionId);
        List<NodeEdge> edges = edgeMapper.selectByVersionIdOrderById(versionId);
        return new StrategyGraphDTO(version, nodes, edges);
    }

    /* ==================== 策略管理：列表 / 版本 / 流量分配 ==================== */

    /**
     * 策略列表：策略外部只暴露 草稿 / 已发布 两种状态，由该策略下的版本状态推导。
     * 一个决策可有多个版本同时在线（切流份额），在线版本按 新→旧 返回。
     */
    public List<StrategyItemDTO> manageList() {
        return strategyMapper.selectList(null).stream().map(s -> {
            List<StrategyVersion> versions = versionMapper.selectByStrategyIdOrderById(s.getId());
            List<StrategyVersion> online = versions.stream()
                    .filter(v -> StrategyStatus.isPublished(v.getStatus()))
                    .sorted(Comparator.comparing(StrategyVersion::getId).reversed())
                    .collect(Collectors.toList());

            StrategyItemDTO dto = new StrategyItemDTO();
            dto.setId(s.getId());
            dto.setCode(s.getCode());
            dto.setName(s.getName());
            dto.setCategory(s.getCategory());
            dto.setDescription(s.getDescription());
            dto.setOwner(s.getOwner());
            dto.setStatus(online.isEmpty() ? "DRAFT" : "PUBLISHED");
            dto.setStatusText(online.isEmpty() ? "草稿" : "已发布");
            dto.setVersionCount(versions.size());
            dto.setOnlineVersions(online.stream().map(this::onlineVersionOf).collect(Collectors.toList()));
            dto.setUpdatedAt(s.getUpdatedAt());
            return dto;
        }).collect(Collectors.toList());
    }

    /** 在线版本展示项：份额 / 方式取灰度规则；WHITELIST 下 pct=100 即兜底版本 */
    private StrategyItemDTO.OnlineVersion onlineVersionOf(StrategyVersion v) {
        List<GrayscaleRule> rules = grayscaleRuleMapper.selectByVersionId(v.getId());
        GrayscaleRule rule = rules.isEmpty() ? null : rules.get(0);
        String mode = safeMode(rule != null ? rule.getMode() : null);

        StrategyItemDTO.OnlineVersion ov = new StrategyItemDTO.OnlineVersion();
        ov.setVersionNo(v.getVersionNo());
        ov.setTrafficMode(mode);
        ov.setTrafficPct(rule != null && rule.getTrafficPct() != null ? rule.getTrafficPct() : trafficPctOf(v));
        ov.setWhitelistUsers(rule != null && rule.getWhitelistUsers() != null ? rule.getWhitelistUsers() : 0);
        ov.setFallback("WHITELIST".equals(mode) && rule != null
                && rule.getTrafficPct() != null && rule.getTrafficPct() >= 100);
        return ov;
    }

    /**
     * 策略内部版本列表：版本有 草稿 / 已发布 / 已下线 三态，并附带流量比例与切流方式。
     */
    public List<StrategyVersionItemDTO> versionsWithTraffic(Long strategyId) {
        return versionMapper.selectByStrategyIdOrderById(strategyId).stream().map(v -> {
            StrategyVersionItemDTO dto = new StrategyVersionItemDTO();
            dto.setId(v.getId());
            dto.setStrategyId(v.getStrategyId());
            dto.setVersionNo(v.getVersionNo());
            dto.setRev(v.getRev());
            dto.setStatus(StrategyStatus.threeState(v.getStatus()));
            dto.setStatusText(StrategyStatus.threeStateText(v.getStatus()));
            dto.setStateLabel(v.getStateLabel());
            dto.setBundle(v.getBundle());
            dto.setChangeCount(v.getChangeCount());
            dto.setSubmittedBy(v.getSubmittedBy());
            dto.setPublishedBy(v.getPublishedBy());
            dto.setPublishedAt(v.getPublishedAt());
            dto.setUpdatedAt(v.getUpdatedAt());

            List<GrayscaleRule> rules = grayscaleRuleMapper.selectByVersionId(v.getId());
            GrayscaleRule rule = rules.isEmpty() ? null : rules.get(0);
            String mode = safeMode(rule != null ? rule.getMode() : null);
            dto.setTrafficMode(mode);
            dto.setTrafficModeText(modeTextOf(mode));
            int pct = rule != null && rule.getTrafficPct() != null ? rule.getTrafficPct() : trafficPctOf(v);
            // 已下线版本不再承接流量，展示口径强制归零
            if ("OFFLINE".equals(dto.getStatus())) pct = 0;
            dto.setTrafficPct(pct);
            dto.setWhitelistUsers(rule != null && rule.getWhitelistUsers() != null ? rule.getWhitelistUsers() : 0);
            dto.setFallback("PUBLISHED".equals(dto.getStatus()) && "WHITELIST".equals(mode)
                    && rule != null && rule.getTrafficPct() != null && rule.getTrafficPct() >= 100);
            dto.setChannel(rule != null ? rule.getChannel() : null);
            dto.setUserTag(rule != null ? rule.getUserTag() : null);
            return dto;
        }).collect(Collectors.toList());
    }

    /**
     * 决策级切流配置：一次给决策下的多个版本分配流量份额（随机 / 百分比合计必须 100%）。
     * 份额为 0 的在线版本保存后自动下线；已下线版本份额 >0 即重新上线；草稿 / 待审版本不参与。
     */
    @Transactional
    public void updateStrategyTraffic(Long strategyId, StrategyTrafficDTO body) {
        Strategy strategy = strategyMapper.selectById(strategyId);
        if (strategy == null) throw new RuntimeException("策略不存在: " + strategyId);

        String mode = normalizeMode(body.getMode());
        List<StrategyTrafficDTO.Share> shares = body.getShares() == null ? List.of() : body.getShares();
        if (shares.isEmpty()) throw new RuntimeException("请至少为一个版本分配流量");

        Map<Long, StrategyVersion> versionById = new LinkedHashMap<>();
        for (StrategyVersion v : versionMapper.selectByStrategyIdOrderById(strategyId)) {
            versionById.put(v.getId(), v);
        }

        Map<Long, StrategyTrafficDTO.Share> shareByVersion = new LinkedHashMap<>();
        int pctTotal = 0;
        int activeCount = 0;
        int fallbackCount = 0;
        int whitelistTotal = 0;

        for (StrategyTrafficDTO.Share share : shares) {
            StrategyVersion v = share.getVersionId() == null ? null : versionById.get(share.getVersionId());
            if (v == null) throw new RuntimeException("份额表包含不属于该策略的版本");
            if (!isEligible(v.getStatus())) {
                throw new RuntimeException("版本 " + v.getVersionNo() + " 尚未发布，不能参与切流");
            }
            if (shareByVersion.put(share.getVersionId(), share) != null) {
                throw new RuntimeException("份额表存在重复的版本：" + v.getVersionNo());
            }
            if ("WHITELIST".equals(mode)) {
                boolean fallback = Boolean.TRUE.equals(share.getFallback());
                int wl = share.getWhitelistUsers() == null ? 0 : share.getWhitelistUsers();
                if (wl < 0) throw new RuntimeException("白名单人数不能为负数（版本 " + v.getVersionNo() + "）");
                if (fallback) fallbackCount++; else whitelistTotal += wl;
                if (fallback || wl > 0) activeCount++;
            } else {
                int pct = share.getTrafficPct() == null ? 0 : share.getTrafficPct();
                if (pct < 0 || pct > 100) {
                    throw new RuntimeException("份额需为 0-100 的整数（版本 " + v.getVersionNo() + "）");
                }
                pctTotal += pct;
                if (pct > 0) activeCount++;
            }
        }

        if ("WHITELIST".equals(mode)) {
            if (fallbackCount != 1) throw new RuntimeException("白名单切流需选择且仅选择一个兜底版本");
            if (whitelistTotal <= 0) throw new RuntimeException("白名单切流至少为一个版本配置白名单人数");
        } else if (pctTotal != 100) {
            throw new RuntimeException("各版本份额之和必须等于 100%（当前 " + pctTotal + "%）");
        }
        if (activeCount > 5) throw new RuntimeException("同时承接流量的版本最多 5 个（当前 " + activeCount + " 个）");

        String channel = blankToNull(body.getChannel());
        String userTag = blankToNull(body.getUserTag());

        for (StrategyVersion v : versionById.values()) {
            if (!isEligible(v.getStatus())) continue;

            StrategyTrafficDTO.Share share = shareByVersion.get(v.getId());
            int pct = 0;
            int whitelistUsers = 0;
            boolean fallback = false;
            if (share != null) {
                if ("WHITELIST".equals(mode)) {
                    fallback = Boolean.TRUE.equals(share.getFallback());
                    whitelistUsers = share.getWhitelistUsers() == null ? 0 : share.getWhitelistUsers();
                    pct = fallback ? 100 : 0;
                } else {
                    pct = share.getTrafficPct() == null ? 0 : share.getTrafficPct();
                }
            }
            boolean active = pct > 0 || ("WHITELIST".equals(mode) && whitelistUsers > 0);

            if (active) {
                upsertRule(v.getId(), mode, pct, whitelistUsers, channel, userTag);
                boolean wasOffline = !StrategyStatus.isPublished(v.getStatus());
                v.setGrayscalePct(pct);
                if ("WHITELIST".equals(mode) && !fallback) {
                    v.setStatus("GRAYSCALE");
                    v.setStateLabel("白名单 " + whitelistUsers + " 人");
                } else if (pct >= 100) {
                    v.setStatus("PUBLISHED");
                    v.setStateLabel("生产运行中");
                } else {
                    v.setStatus("GRAYSCALE");
                    v.setStateLabel("灰度中 " + pct + "%");
                }
                if (wasOffline) {
                    v.setPublishedAt(LocalDateTime.now());
                    if (v.getPublishedBy() == null) v.setPublishedBy(v.getSubmittedBy());
                }
                versionMapper.updateById(v);
            } else if (StrategyStatus.isPublished(v.getStatus())) {
                // 份额为 0 的在线版本：保存后自动下线
                v.setStatus("ROLLED_BACK");
                v.setStateLabel("已下线");
                v.setGrayscalePct(0);
                versionMapper.updateById(v);
                deactivateRules(v.getId());
            }
        }
    }

    /** 可参与切流的版本：已发布 / 灰度中 / 已下线；草稿与待审不可参与 */
    private static boolean isEligible(String status) {
        return StrategyStatus.isPublished(status)
                || "ROLLED_BACK".equals(status) || "OFFLINE".equals(status);
    }

    private void upsertRule(Long versionId, String mode, int pct, int whitelistUsers, String channel, String userTag) {
        List<GrayscaleRule> existing = grayscaleRuleMapper.selectByVersionId(versionId);
        GrayscaleRule rule = existing.isEmpty() ? new GrayscaleRule() : existing.get(0);
        rule.setVersionId(versionId);
        rule.setMode(mode);
        rule.setTrafficPct(pct);
        rule.setWhitelistUsers(whitelistUsers);
        rule.setChannel(channel);
        rule.setUserTag(userTag);
        rule.setStatus("ACTIVE");
        if (existing.isEmpty()) {
            grayscaleRuleMapper.insert(rule);
        } else {
            // updateById 默认忽略 null 字段，渠道/标签置空需显式 set
            UpdateWrapper<GrayscaleRule> uw = new UpdateWrapper<>();
            uw.eq("id", rule.getId())
                    .set("mode", mode)
                    .set("traffic_pct", pct)
                    .set("whitelist_users", whitelistUsers)
                    .set("channel", channel)
                    .set("user_tag", userTag)
                    .set("status", "ACTIVE");
            grayscaleRuleMapper.update(null, uw);
        }
    }

    /** 发布版本：同策略下其它线上版本转为已下线，保证同一策略只有一个版本在线 */
    public void publishVersion(Long versionId) {
        StrategyVersion version = versionMapper.selectById(versionId);
        if (version == null) throw new RuntimeException("版本不存在: " + versionId);
        if ("ROLLED_BACK".equals(version.getStatus()) || "OFFLINE".equals(version.getStatus())) {
            throw new RuntimeException("已下线的版本不能重复发布，请新建版本后再发布");
        }

        for (StrategyVersion v : versionMapper.selectByStrategyIdOrderById(version.getStrategyId())) {
            if (!v.getId().equals(versionId) && StrategyStatus.isPublished(v.getStatus())) {
                v.setStatus("ROLLED_BACK");
                v.setStateLabel("已下线");
                v.setGrayscalePct(0);
                versionMapper.updateById(v);
                deactivateRules(v.getId());
            }
        }
        version.setStatus("PUBLISHED");
        version.setStateLabel("生产运行中");
        if (version.getGrayscalePct() == null || version.getGrayscalePct() == 0) {
            version.setGrayscalePct(100);
        }
        version.setPublishedAt(LocalDateTime.now());
        if (version.getPublishedBy() == null) {
            version.setPublishedBy(version.getSubmittedBy());
        }
        versionMapper.updateById(version);
    }

    /** 下线版本：版本转为已下线并清空流量 */
    public void offlineVersion(Long versionId) {
        StrategyVersion version = versionMapper.selectById(versionId);
        if (version == null) throw new RuntimeException("版本不存在: " + versionId);
        version.setStatus("ROLLED_BACK");
        version.setStateLabel("已下线");
        version.setGrayscalePct(0);
        versionMapper.updateById(version);
        deactivateRules(versionId);
    }

    /** 把某版本的灰度规则置为失效并清零流量，已下线版本不再承接流量 */
    private void deactivateRules(Long versionId) {
        for (GrayscaleRule rule : grayscaleRuleMapper.selectByVersionId(versionId)) {
            rule.setStatus("INACTIVE");
            rule.setTrafficPct(0);
            grayscaleRuleMapper.updateById(rule);
        }
    }

    /* ---------------- 状态与流量口径：见 common/StrategyStatus ---------------- */

    private static String normalizeMode(String mode) {
        if (mode == null || mode.trim().isEmpty()) return "PERCENT";
        String m = mode.trim().toUpperCase(Locale.ROOT);
        if ("RANDOM".equals(m) || "PERCENT".equals(m) || "WHITELIST".equals(m)) return m;
        throw new RuntimeException("不支持的切流方式: " + mode);
    }

    /** 读库展示用：遇到脏数据退化为百分比切流，不抛异常 */
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

    private static int trafficPctOf(StrategyVersion v) {
        return v.getGrayscalePct() == null ? 0 : v.getGrayscalePct();
    }

    private static String blankToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }
}
