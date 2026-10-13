package com.riskstrategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.riskstrategy.entity.CrossTree;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface CrossTreeMapper extends BaseMapper<CrossTree> {

    List<CrossTree> selectByStrategyId(@Param("strategyId") Long strategyId);
}
