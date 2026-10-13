package com.riskstrategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.riskstrategy.entity.TrackStep;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface TrackStepMapper extends BaseMapper<TrackStep> {

    List<TrackStep> selectByTrackIdOrderByStepNo(@Param("trackId") Long trackId);

    int insertBatch(@Param("list") List<TrackStep> steps);
}
