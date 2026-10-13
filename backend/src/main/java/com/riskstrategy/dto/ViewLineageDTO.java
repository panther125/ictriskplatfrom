package com.riskstrategy.dto;

import lombok.Data;

@Data
public class ViewLineageDTO {
    private Integer totalRefs;
    private Integer baseFeatures;
    private Integer derivedFeatures;
    private Integer strategies;
    private String tree;
}
