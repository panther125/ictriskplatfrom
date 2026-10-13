package com.riskstrategy.dto;

import lombok.Data;

import java.util.List;

@Data
public class ViewCreateRequest {
    private String code;
    private String name;
    private String description;
    private String sourceType;
    private String readMode;
    private String owner;
    private String businessDomain;
    private String scriptContent;

    private List<InputVarDTO> inputs;
    private List<OutputFieldDTO> outputs;

    @Data
    public static class InputVarDTO {
        private String varCode;
        private String varType;
        private Boolean required;
        private String defaultValue;
    }

    @Data
    public static class OutputFieldDTO {
        private String fieldName;
        private String fieldType;
        private String description;
        private String defaultValue;
        private Integer sort;
    }
}
