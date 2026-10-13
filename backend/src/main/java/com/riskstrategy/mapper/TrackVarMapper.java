package com.riskstrategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.riskstrategy.entity.TrackVar;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface TrackVarMapper extends BaseMapper<TrackVar> {

    List<TrackVar> selectByTrackIdOrderByStepNo(@Param("trackId") Long trackId);

    int insertBatch(@Param("list") List<TrackVar> vars);
}
