package com.riskstrategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.riskstrategy.entity.TrialRecord;

import java.util.List;

public interface TrialRecordMapper extends BaseMapper<TrialRecord> {

    List<TrialRecord> selectTop20ByCreatedAtDesc();
}
