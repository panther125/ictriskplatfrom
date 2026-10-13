package com.riskstrategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.riskstrategy.entity.DecisionNode;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface DecisionNodeMapper extends BaseMapper<DecisionNode> {

    List<DecisionNode> selectByVersionIdOrderById(@Param("versionId") Long versionId);
}
