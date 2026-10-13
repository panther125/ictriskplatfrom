package com.riskstrategy.dto;

import lombok.Data;

@Data
public class TrialCaseResultDTO {
    private Long caseId;
    private String expect;
    private String actual;
    private String status;

    public TrialCaseResultDTO() {}

    public TrialCaseResultDTO(Long caseId, String expect, String actual, String status) {
        this.caseId = caseId;
        this.expect = expect;
        this.actual = actual;
        this.status = status;
    }
}
