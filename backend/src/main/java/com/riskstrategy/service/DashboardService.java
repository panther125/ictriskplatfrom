package com.riskstrategy.service;

import com.riskstrategy.dto.DashboardDTO;
import com.riskstrategy.entity.MonitorMetric;
import com.riskstrategy.mapper.HealthStatusMapper;
import com.riskstrategy.mapper.MonitorMetricMapper;
import com.riskstrategy.mapper.ReleaseRecordMapper;
import com.riskstrategy.mapper.RuleHitStatMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class DashboardService {

    private final MonitorMetricMapper metricMapper;
    private final HealthStatusMapper healthMapper;
    private final ReleaseRecordMapper releaseMapper;
    private final RuleHitStatMapper ruleHitMapper;

    public DashboardService(MonitorMetricMapper metricMapper,
                            HealthStatusMapper healthMapper,
                            ReleaseRecordMapper releaseMapper,
                            RuleHitStatMapper ruleHitMapper) {
        this.metricMapper = metricMapper;
        this.healthMapper = healthMapper;
        this.releaseMapper = releaseMapper;
        this.ruleHitMapper = ruleHitMapper;
    }

    public DashboardDTO build() {
        DashboardDTO dto = new DashboardDTO();
        // 顶部四项指标：在线策略版本 / 今日在线决策量 / P99 / 兜底占比
        List<MonitorMetric> metrics = metricMapper.selectTop8BySnapshotAtDesc()
                .stream()
                .filter(m -> !m.getMetricKey().equals("today_decision_prev"))
                .toList();
        dto.setMetrics(metrics);
        dto.setTodos(List.of(
                Map.of("title", "3个策略版本待审批(最长等待4h20m)", "count", 3, "priority", "HIGH"),
                Map.of("title", "5条编译期校验警告待确认(条件重叠3·实验特征引用2)", "count", 5, "priority", "MEDIUM"),
                Map.of("title", "2个版本处于灰度观察中(loan_apply_v3 10%·credit_check_v2 5%)", "count", 2, "priority", "MEDIUM"),
                Map.of("title", "1个视图已熔断·1个特征空值率超基线2倍", "count", 2, "priority", "HIGH")
        ));
        dto.setRecentReleases(releaseMapper.selectTop10ByOccurredAtDesc());
        dto.setHealth(healthMapper.selectList(null));
        dto.setTopRules(ruleHitMapper.selectByPeriodOrderByRank("近24小时"));
        dto.setQuickEntries(Map.of(
                "newStrategy", "新建策略",
                "importView", "导入视图配置"
        ));
        return dto;
    }
}
