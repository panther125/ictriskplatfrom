package com.riskstrategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.riskstrategy.entity.StrategyVersion;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface StrategyVersionMapper extends BaseMapper<StrategyVersion> {

    List<StrategyVersion> selectByStrategyIdOrderById(@Param("strategyId") Long strategyId);
}
