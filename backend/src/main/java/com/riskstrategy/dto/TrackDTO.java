package com.riskstrategy.dto;

import com.riskstrategy.entity.TrackRecord;
import com.riskstrategy.entity.TrackStep;
import com.riskstrategy.entity.TrackVar;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class TrackDTO {
    private TrackRecord record;
    private List<TrackStep> steps;
    /** 变量快照：namespace(param|view|feat|node) -> { varName: varValue } */
    private Map<String, Map<String, String>> variables;
    /** 耗时分解：category -> {ms, pct} */
    private Map<String, Map<String, Object>> timingBreakdown;
    /** 入参对象 */
    private Map<String, Object> input;
    /** 返回报文 */
    private Map<String, Object> response;
    /** 命中路径节点 id 数组 */
    private List<String> hitPath;
    /** 是否为兜底/降级结论 */
    private boolean degraded;

    public TrackDTO() {
        this.variables = new LinkedHashMap<>();
        this.timingBreakdown = new LinkedHashMap<>();
        this.input = new LinkedHashMap<>();
        this.response = new LinkedHashMap<>();
    }
}
