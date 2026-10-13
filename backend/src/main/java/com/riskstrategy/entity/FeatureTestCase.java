package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("feature_test_case")
public class FeatureTestCase {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long featureId;
    private String caseName;
    private String inputsJson;
    private String expected;
    private String result;
}
