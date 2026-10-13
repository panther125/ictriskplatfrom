package com.riskstrategy.dto;

import lombok.Data;

@Data
public class PrecheckItemDTO {
    private String title;
    private String desc;
    private String result;
    private String type;

    public PrecheckItemDTO() {}

    public PrecheckItemDTO(String title, String desc, String result, String type) {
        this.title = title;
        this.desc = desc;
        this.result = result;
        this.type = type;
    }
}
