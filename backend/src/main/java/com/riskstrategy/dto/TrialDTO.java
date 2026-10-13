package com.riskstrategy.dto;

import com.riskstrategy.entity.TrialExecutionStep;
import com.riskstrategy.entity.TrialRecord;
import lombok.Data;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class TrialDTO {
    private TrialRecord record;
    private List<TrialExecutionStep> steps;
    /** 变量快照：view.* / feat.* / node.* */
    private Map<String, Map<String, Object>> variableSnapshot;
    /** 返回报文 JSON */
    private Map<String, Object> response;
    /** 流量判定结果（自动模式；手动选版本为 null，见 docs/trial-traffic-route.md §3.6） */
    private TrialRoutingDTO routing;
    /** 特征真实计算明细（§4.3） */
    private List<Map<String, Object>> featureRuns;
    /** 逐节点计算明细（§5.1） */
    private List<Map<String, Object>> nodeDetails;

    /** 同步落下的全链路 trace（track_record.id），可拿去决策日志页回放 */
    private Long trackId;
    /** 与试算记录共用的 traceId，在决策日志里按它检索 */
    private String traceId;

    public TrialDTO(TrialRecord record, List<TrialExecutionStep> steps) {
        this.record = record;
        this.steps = steps;
        this.variableSnapshot = new LinkedHashMap<>();
        this.response = new LinkedHashMap<>();
        this.featureRuns = new ArrayList<>();
        this.nodeDetails = new ArrayList<>();
    }
}
