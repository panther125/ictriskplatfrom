package com.riskstrategy.dto;

import lombok.Data;

@Data
public class DatasourceHealthDTO {
    private String name;
    private String poolUsage;
    private Integer avgLatencyMs;
    private String status;

    public DatasourceHealthDTO() {}

    public DatasourceHealthDTO(String name, String poolUsage, Integer avgLatencyMs, String status) {
        this.name = name;
        this.poolUsage = poolUsage;
        this.avgLatencyMs = avgLatencyMs;
        this.status = status;
    }
}
