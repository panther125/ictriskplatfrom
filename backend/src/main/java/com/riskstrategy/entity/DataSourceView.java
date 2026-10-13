package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("data_source_view")
public class DataSourceView {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String description;
    private String sourceType;
    private String readMode;
    private String version;
    private String status;
    private String owner;
    private String businessDomain;
    private String degradeChain;
    private String scriptContent;
    @JsonIgnore
    private String inputVarsJson;
    @JsonIgnore
    private String outputFieldsJson;
    private Integer refCount;
    private Integer fieldCount;
    private Integer piiCount;
    private java.math.BigDecimal successRate;
    private java.math.BigDecimal latencyMs;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除标记：0=有效；下线时置 1。
     * 若同一编码历史上已下线过，旧记录会被改写为其自身 id，保证唯一索引 (code,deleted) 不冲突。
     */
    @TableLogic
    @TableField("deleted")
    private Long deleted;
}
