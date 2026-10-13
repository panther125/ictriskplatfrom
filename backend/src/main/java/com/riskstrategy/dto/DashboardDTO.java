package com.riskstrategy.dto;

import com.riskstrategy.entity.HealthStatus;
import com.riskstrategy.entity.MonitorMetric;
import com.riskstrategy.entity.ReleaseRecord;
import com.riskstrategy.entity.RuleHitStat;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class DashboardDTO {
    private List<MonitorMetric> metrics;        // 顶部四项指标
    private List<Map<String, Object>> todos;    // 待处理事项
    private List<ReleaseRecord> recentReleases; // 最近发布与变更
    private List<HealthStatus> health;          // 数据源与特征健康度
    private List<RuleHitStat> topRules;         // 规则命中 TOP
    private Map<String, Object> quickEntries;   // 常用入口
}
