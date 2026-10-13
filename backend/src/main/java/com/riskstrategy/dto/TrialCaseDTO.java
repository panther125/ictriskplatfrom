package com.riskstrategy.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class TrialCaseDTO {
    private Long id;
    private String name;
    private Long strategyId;
    private Object inputs;
    private String expectConclusion;
    private String tags;
    private LocalDateTime createdAt;
}
