package com.riskstrategy.dto;

import lombok.Data;

@Data
public class ParsedInputDTO {
    private String name;
    private String type;
    private String source;

    public ParsedInputDTO() {}

    public ParsedInputDTO(String name, String type, String source) {
        this.name = name;
        this.type = type;
        this.source = source;
    }
}
