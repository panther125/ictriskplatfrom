package com.riskstrategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.riskstrategy.entity.NodeEdge;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface NodeEdgeMapper extends BaseMapper<NodeEdge> {

    List<NodeEdge> selectByVersionIdOrderById(@Param("versionId") Long versionId);
}
