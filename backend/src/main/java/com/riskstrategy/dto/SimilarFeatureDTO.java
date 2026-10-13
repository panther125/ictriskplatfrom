package com.riskstrategy.dto;

import lombok.Data;

@Data
public class SimilarFeatureDTO {
    private String name;
    private String similarity;

    public SimilarFeatureDTO() {}

    public SimilarFeatureDTO(String name, String similarity) {
        this.name = name;
        this.similarity = similarity;
    }
}
