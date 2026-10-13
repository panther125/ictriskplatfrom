package com.riskstrategy.dto;

import lombok.Data;
import java.util.List;

@Data
public class FeatureValidateDTO {
    private Boolean passed;
    private List<CheckItem> checks;

    @Data
    public static class CheckItem {
        private String item;
        private Boolean pass;
        private Boolean suggest;
        private String suggestText;

        public CheckItem() {}

        public CheckItem(String item, Boolean pass) {
            this.item = item;
            this.pass = pass;
        }
    }
}
