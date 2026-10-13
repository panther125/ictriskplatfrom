package com.riskstrategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.riskstrategy.entity.P99Trend;

import java.util.List;

public interface P99TrendMapper extends BaseMapper<P99Trend> {

    List<P99Trend> selectTop30ByTsAsc();
}
