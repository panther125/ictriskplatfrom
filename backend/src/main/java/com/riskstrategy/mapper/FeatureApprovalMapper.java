package com.riskstrategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.riskstrategy.entity.FeatureApproval;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface FeatureApprovalMapper extends BaseMapper<FeatureApproval> {
    List<FeatureApproval> selectByFeatureId(@Param("featureId") Long featureId);
}
