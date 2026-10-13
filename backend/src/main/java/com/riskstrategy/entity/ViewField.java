package com.riskstrategy.entity;

import lombok.Data;

@Data
public class ViewField {
    private Long id;
    private Long viewId;
    private String fieldName;
    private String fieldType;
    private String description;
    private String defaultValue;
    private String category;
    private String piiLevel;
    private String masking;
    private Integer isPrimary;
    private Integer sort;
}
