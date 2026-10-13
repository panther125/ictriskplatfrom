package com.riskstrategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.riskstrategy.entity.Decision;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface DecisionMapper extends BaseMapper<Decision> {

    List<Decision> selectByStrategyId(@Param("strategyId") Long strategyId);
}
