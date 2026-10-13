package com.riskstrategy.dto;

import com.riskstrategy.entity.*;
import lombok.Data;

import java.util.List;

/**
 * 运行监控：按「一笔请求从进来到出结论」的调用链分层返回。
 *
 * L1 接入 · L2 取数 · L3 特征 · L4 模型 · L5 规则 · L6 决策 · L7 贷后
 *
 * 约定：SLO 红绿灯与告警由后端按阈值「算」出来，前端只负责渲染，不猜状态。
 */
@Data
public class MonitoringDTO {

    /** 数据窗口说明，避免使用者误把 24h 快照当成实时值 */
    private Meta meta;

    /** SLO 红绿灯 */
    private List<SloItem> slo;

    /** 顶部 KPI 卡（能聚合的一律聚合算出，不写死） */
    private List<KpiCard> kpi;

    private List<MonStageLatency> stages;      // 全链路分段耗时
    private List<MonDatasource> datasources;   // 外部数据源健康度
    private List<MonFeatureStat> features;     // 特征稳定性 PSI
    private List<MonModelStat> models;         // 模型 PSI/KS/AUC
    private List<MonScoreBucket> scoreBuckets; // 评分分布与分箱坏账
    private List<MonRuleStat> rules;           // 规则命中与腐化信号
    private List<MonBusinessTrend> trend;      // 业务趋势(逐小时)
    private List<MonChannelStat> channels;     // 渠道风险表现
    private List<MonOutcome> outcomes;         // 贷后 Vintage

    /** 由各层数据扫描出来的告警，按严重度排序 */
    private List<AlarmItem> alarms;

    /** 由趋势算出的汇总，前端不必再自己加权 */
    private Summary summary;

    @Data
    public static class Meta {
        private String window;      // 近24小时
        private String updatedAt;
        private String env;         // PROD
        private String note;        // 离线指标 T+1 回写
    }

    @Data
    public static class SloItem {
        private String key;
        private String label;
        private String layer;
        private String actual;      // 已格式化，带单位
        private String threshold;   // 目标口径
        private String status;      // PASS / WARN / FAIL
        private String hint;        // 不达标时说明影响面
    }

    @Data
    public static class KpiCard {
        private String key;
        private String label;
        private String value;
        private String unit;
        private String comparison;
        private String threshold;
        private String state;       // NORMAL / WARN / BREAK
        private String layer;
    }

    @Data
    public static class AlarmItem {
        private String level;       // FATAL / WARN / INFO
        private String layer;
        private String target;
        private String title;
        private String detail;
        private String occurredAt;
        private String suggestion;  // 处置建议
    }

    @Data
    public static class Summary {
        private Long decisionCnt;      // 近24h决策量
        private Double passPct;        // 加权通过率
        private Double reviewPct;
        private Double rejectPct;
        private Double avgP99Ms;
        private Double peakP99Ms;
        private Long peakQps;
        private Double successPct;     // 决策成功率
        private Double fetchSuccessPct;// 外部取数加权成功率
        private Double approvalAmountWan;
    }
}
