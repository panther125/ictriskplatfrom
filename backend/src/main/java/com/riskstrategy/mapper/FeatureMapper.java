package com.riskstrategy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.riskstrategy.entity.Feature;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import java.util.List;
import java.util.Map;

public interface FeatureMapper extends BaseMapper<Feature> {
    List<Feature> selectSimilarByCode(@Param("code") String code, @Param("limit") int limit);

    /** 按编码精确取一个有效特征（不含已下线），用于 @@ 引用解析 */
    Feature selectByCode(@Param("code") String code);

    /** 按编码取特征（含已下线：offline 只置 status=OFFLINE 并把 deleted 置为自身 id），用于试算「解析入参」对下线引用的告警 */
    Feature selectByCodeIncludeOffline(@Param("code") String code);

    /** 列出可用于 @@ 引用的基础特征 */
    List<Feature> selectBaseFeatures(@Param("limit") int limit);

    List<Map<String, Object>> countByVerifyStatus();
    int batchUpdateVerifyStatus(@Param("ids") List<Long> ids, @Param("status") String status);
    int publishById(@Param("id") Long id);
    int offlineById(@Param("id") Long id);

    // =========================================================================
    //  特征改名：把下游配置里按编码写死的引用一并改掉。
    //  用 MySQL 8 的 REGEXP_REPLACE 做「带词边界」的精确替换，避免 abc 误伤 abcd；
    //  pat / rep 由 Service 组装（Java 侧转义后经 JDBC 参数传入，不会被 SQL 再转义一次）。
    //  只覆盖配置态引用；决策日志 / 追踪回放属运行态历史记录，保持原样。
    // =========================================================================

    @Update("UPDATE feature SET expr = REGEXP_REPLACE(expr, #{pat}, #{rep}) "
            + "WHERE deleted = 0 AND expr REGEXP #{pat}")
    int renameInFeatureExpr(@Param("pat") String pat, @Param("rep") String rep);

    @Update("UPDATE feature SET sql_expr = REGEXP_REPLACE(sql_expr, #{pat}, #{rep}) "
            + "WHERE deleted = 0 AND sql_expr REGEXP #{pat}")
    int renameInFeatureSqlExpr(@Param("pat") String pat, @Param("rep") String rep);

    @Update("UPDATE decision_node SET condition_expr = REGEXP_REPLACE(condition_expr, #{pat}, #{rep}) "
            + "WHERE condition_expr REGEXP #{pat}")
    int renameInNodeCondition(@Param("pat") String pat, @Param("rep") String rep);

    @Update("UPDATE decision_node SET ref_vars_json = REGEXP_REPLACE(ref_vars_json, #{pat}, #{rep}) "
            + "WHERE ref_vars_json REGEXP #{pat}")
    int renameInNodeRefVars(@Param("pat") String pat, @Param("rep") String rep);

    @Update("UPDATE `rule` SET config_json = REGEXP_REPLACE(config_json, #{pat}, #{rep}) "
            + "WHERE config_json REGEXP #{pat}")
    int renameInRuleConfig(@Param("pat") String pat, @Param("rep") String rep);

    @Update("UPDATE cross_tree_dimension SET expr = REGEXP_REPLACE(expr, #{pat}, #{rep}) "
            + "WHERE expr REGEXP #{pat}")
    int renameInCrossTreeDim(@Param("pat") String pat, @Param("rep") String rep);
}
