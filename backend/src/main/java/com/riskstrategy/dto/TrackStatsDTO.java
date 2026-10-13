package com.riskstrategy.dto;

import lombok.Data;

@Data
public class TrackStatsDTO {
    private Integer total;
    private Integer degraded;
    private Integer slowRequests;
    private Integer exceptions;
}
