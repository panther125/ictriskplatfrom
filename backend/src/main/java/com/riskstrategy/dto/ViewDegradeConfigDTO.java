package com.riskstrategy.dto;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class ViewDegradeConfigDTO {
    private String degradeMode;
    private String onFallback;
    private String cacheL1;
    private String cacheL2;
    private CircuitBreakerConfig circuitBreaker;

    @Data
    public static class CircuitBreakerConfig {
        private Boolean enabled;
        private BigDecimal errorRate;
    }
}
