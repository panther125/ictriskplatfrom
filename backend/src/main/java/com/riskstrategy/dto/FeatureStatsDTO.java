package com.riskstrategy.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class FeatureStatsDTO {
    private Double ioCoverage;
    private Integer registeredOutputs;
    private Integer totalFeatures;
    private Integer inputRefTotal;
    private Integer unverifiedCount;
    private Map<String, Integer> verifyDistribution;
    private List<RefItem> topInputRefs;

    @Data
    public static class RefItem {
        private String name;
        private Integer count;

        public RefItem() {}

        public RefItem(String name, Integer count) {
            this.name = name;
            this.count = count;
        }
    }
}
