package com.riskstrategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.riskstrategy.entity.CrossTreeDimension;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface CrossTreeDimensionMapper extends BaseMapper<CrossTreeDimension> {
    List<CrossTreeDimension> selectByTreeIdOrderByDimNo(@Param("treeId") Long treeId);
    int deleteByTreeId(@Param("treeId") Long treeId);
    int insertBatch(@Param("list") List<CrossTreeDimension> dims);
}
