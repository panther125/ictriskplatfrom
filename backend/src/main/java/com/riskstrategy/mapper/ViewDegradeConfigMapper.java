package com.riskstrategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.riskstrategy.entity.ViewDegradeConfig;
import org.apache.ibatis.annotations.Param;

public interface ViewDegradeConfigMapper extends BaseMapper<ViewDegradeConfig> {
    ViewDegradeConfig selectByViewId(@Param("viewId") Long viewId);
}
