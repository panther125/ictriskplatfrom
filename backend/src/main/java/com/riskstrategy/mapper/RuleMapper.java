package com.riskstrategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.riskstrategy.entity.Rule;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface RuleMapper extends BaseMapper<Rule> {

    List<Rule> selectByDecisionIdOrderById(@Param("decisionId") Long decisionId);
}
