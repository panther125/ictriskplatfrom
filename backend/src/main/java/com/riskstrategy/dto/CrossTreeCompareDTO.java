package com.riskstrategy.dto;

import lombok.Data;

@Data
public class CrossTreeCompareDTO {
    private String type;
    private String cell;
    private String oldValue;
    private String newValue;
}
