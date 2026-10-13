package com.riskstrategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.riskstrategy.entity.GrayscaleRule;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface GrayscaleRuleMapper extends BaseMapper<GrayscaleRule> {

    List<GrayscaleRule> selectByVersionId(@Param("versionId") Long versionId);
}
