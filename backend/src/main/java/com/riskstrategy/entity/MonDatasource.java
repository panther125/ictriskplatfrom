package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 外部数据源健康度：银行风控最大的耗时来源与故障源 */
@Data
@TableName("mon_datasource")
public class MonDatasource {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String dsCode;
    private String dsName;
    private String dsType;
    private Long calls;
    private BigDecimal successPct;
    private BigDecimal timeoutPct;
    private BigDecimal p99Ms;
    private BigDecimal cacheHitPct;
    private Integer staleMin;
    private BigDecimal quotaPct;
    private Integer degradeCnt;
    private String status;
    private String note;
    private LocalDateTime updatedAt;
}
