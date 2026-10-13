package com.riskstrategy.dto;

import com.riskstrategy.entity.TrackRecord;
import lombok.Data;
import java.util.Map;

@Data
public class TrackReplayDTO {
    private TrackDTO current;          // 回放产生的新 Trace
    private TrackRecord original;      // 原始 Trace
    private Map<String, Object> compared; // 对比：耗时变化 / 结论是否一致

    public TrackReplayDTO(TrackDTO current, TrackRecord original, Map<String, Object> compared) {
        this.current = current;
        this.original = original;
        this.compared = compared;
    }
}
