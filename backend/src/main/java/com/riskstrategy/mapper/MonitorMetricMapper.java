package com.riskstrategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.riskstrategy.entity.MonitorMetric;

import java.util.List;

public interface MonitorMetricMapper extends BaseMapper<MonitorMetric> {

    List<MonitorMetric> selectTop8BySnapshotAtDesc();
}
