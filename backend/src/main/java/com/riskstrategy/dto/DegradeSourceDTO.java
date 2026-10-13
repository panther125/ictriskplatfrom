package com.riskstrategy.dto;

import lombok.Data;

@Data
public class DegradeSourceDTO {
    private String name;
    private Integer count;

    public DegradeSourceDTO() {}

    public DegradeSourceDTO(String name, Integer count) {
        this.name = name;
        this.count = count;
    }
}
