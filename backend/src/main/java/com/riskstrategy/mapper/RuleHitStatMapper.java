package com.riskstrategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.riskstrategy.entity.RuleHitStat;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface RuleHitStatMapper extends BaseMapper<RuleHitStat> {

    List<RuleHitStat> selectByPeriodOrderByRank(@Param("period") String period);
}
