package com.riskstrategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.riskstrategy.entity.TrialExecutionStep;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface TrialExecutionStepMapper extends BaseMapper<TrialExecutionStep> {

    List<TrialExecutionStep> selectByTrialIdOrderByStepNo(@Param("trialId") Long trialId);
}
