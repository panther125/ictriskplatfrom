package com.riskstrategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.riskstrategy.entity.ReleaseRecord;

import java.util.List;

public interface ReleaseRecordMapper extends BaseMapper<ReleaseRecord> {

    List<ReleaseRecord> selectTop10ByOccurredAtDesc();
}
