package com.riskstrategy.dto;

import lombok.Data;

@Data
public class ShadowCompareDTO {
    private ResultItem onlineResult;
    private ResultItem candidateResult;
    private Boolean sameConclusion;

    @Data
    public static class ResultItem {
        private String conclusion;
        private Long elapsedMs;

        public ResultItem() {}

        public ResultItem(String conclusion, Long elapsedMs) {
            this.conclusion = conclusion;
            this.elapsedMs = elapsedMs;
        }
    }
}
