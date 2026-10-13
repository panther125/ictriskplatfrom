package com.riskstrategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.riskstrategy.entity.DataSourceView;
import org.apache.ibatis.annotations.Param;

public interface DataSourceViewMapper extends BaseMapper<DataSourceView> {

    boolean existsByCode(@Param("code") String code);

    /** 按编码取有效视图（不含已下线） */
    DataSourceView selectByCode(@Param("code") String code);

    /** 发布：状态置 PUBLISHED */
    int publishById(@Param("id") Long id);

    /** 下线：逻辑删除(deleted=id)并置 OFFLINE */
    int offlineById(@Param("id") Long id);
}
