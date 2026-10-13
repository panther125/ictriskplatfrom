package com.riskstrategy.entity;

import lombok.Data;

@Data
public class ViewInputVar {
    private String varCode;
    private String varType;
    private Boolean required;
    private String defaultValue;
    private Integer sort;
}
