package com.riskstrategy.dto;

import lombok.Data;
import java.util.Map;

@Data
public class MatrixBoxingDTO {
    private Map<String, DimBoxing> dimensions;
    private String cellConclusion;
    private Integer cellScore;
    private String finalConclusion;
    private Integer finalScore;

    @Data
    public static class DimBoxing {
        private Object value;
        private String box;

        public DimBoxing() {}

        public DimBoxing(Object value, String box) {
            this.value = value;
            this.box = box;
        }
    }
}
