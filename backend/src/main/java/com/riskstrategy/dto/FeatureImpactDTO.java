package com.riskstrategy.dto;

import lombok.Data;
import java.util.List;

@Data
public class FeatureImpactDTO {
    private List<String> upstreamViews;
    private Integer downstreamRefs;
    private Integer downstreamStrategies;
    private Integer downstreamFeatures;
    private List<String> strategies;
}
