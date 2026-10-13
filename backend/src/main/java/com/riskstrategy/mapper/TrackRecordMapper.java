package com.riskstrategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.riskstrategy.entity.TrackRecord;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.util.Map;

public interface TrackRecordMapper extends BaseMapper<TrackRecord> {
    List<TrackRecord> selectTop50ByCreatedAtDesc();

    List<TrackRecord> search(@Param("traceId") String traceId,
                             @Param("strategyId") Long strategyId,
                             @Param("conclusion") String conclusion,
                             @Param("version") String version);

    int countByTimeRange(@Param("minutes") int minutes);
    int countDegradedByTimeRange(@Param("minutes") int minutes);
    int countSlowByTimeRange(@Param("minutes") int minutes, @Param("thresholdMs") int thresholdMs);
    int countExceptionsByTimeRange(@Param("minutes") int minutes);
    List<Map<String, Object>> selectDegradeSources(@Param("minutes") int minutes, @Param("limit") int limit);
}
