package com.riskstrategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.riskstrategy.entity.DecisionBranch;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface DecisionBranchMapper extends BaseMapper<DecisionBranch> {

    List<DecisionBranch> selectByDecisionIdOrderById(@Param("decisionId") Long decisionId);
}
