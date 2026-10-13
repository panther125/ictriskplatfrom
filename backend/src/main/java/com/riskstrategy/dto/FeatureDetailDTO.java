package com.riskstrategy.dto;

import com.riskstrategy.entity.Feature;
import com.riskstrategy.entity.FeatureInputVar;
import com.riskstrategy.entity.FeatureOutputVar;
import com.riskstrategy.entity.FeatureTestCase;
import lombok.Data;
import java.util.List;

@Data
public class FeatureDetailDTO {
    private Feature feature;
    private List<FeatureInputVar> inputVars;
    /** 出参定义（衍生特征可多个；基础特征为引用视图字段的那 1 个） */
    private List<FeatureOutputVar> outputVars;
    private List<FeatureTestCase> testCases;

    public FeatureDetailDTO(Feature feature, List<FeatureInputVar> inputVars, List<FeatureTestCase> testCases) {
        this(feature, inputVars, null, testCases);
    }

    public FeatureDetailDTO(Feature feature, List<FeatureInputVar> inputVars,
                            List<FeatureOutputVar> outputVars, List<FeatureTestCase> testCases) {
        this.feature = feature;
        this.inputVars = inputVars;
        this.outputVars = outputVars;
        this.testCases = testCases;
    }
}
