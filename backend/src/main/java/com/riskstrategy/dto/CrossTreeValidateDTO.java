package com.riskstrategy.dto;

import lombok.Data;
import java.util.List;

@Data
public class CrossTreeValidateDTO {
    private Double coverage;
    private Integer configuredCells;
    private Integer totalCells;
    private List<CheckItem> checks;

    @Data
    public static class CheckItem {
        private String item;
        private Boolean pass;
        private Boolean warn;

        public CheckItem() {}

        public CheckItem(String item, Boolean pass) {
            this.item = item;
            this.pass = pass;
            this.warn = false;
        }
    }
}
