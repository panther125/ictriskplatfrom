package com.riskstrategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.riskstrategy.entity.CrossTreeCell;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface CrossTreeCellMapper extends BaseMapper<CrossTreeCell> {
    List<CrossTreeCell> selectByTreeIdAndSliceName(@Param("treeId") Long treeId,
                                                   @Param("sliceName") String sliceName);
    List<CrossTreeCell> selectByTreeId(@Param("treeId") Long treeId);
    int deleteByTreeId(@Param("treeId") Long treeId);
    int deleteByTreeIdAndSliceName(@Param("treeId") Long treeId, @Param("sliceName") String sliceName);
    int insertBatch(@Param("list") List<CrossTreeCell> cells);
}
