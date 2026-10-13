package com.riskstrategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.riskstrategy.entity.FeatureTestCase;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface FeatureTestCaseMapper extends BaseMapper<FeatureTestCase> {

    List<FeatureTestCase> selectByFeatureId(@Param("featureId") Long featureId);
}
