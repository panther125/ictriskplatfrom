package com.riskstrategy.dto;

import lombok.Data;

/** 新增版本请求：新版本继承「上一个画布」的节点与连线配置。 */
@Data
public class CreateVersionDTO {
    /** 继承源版本号（如 v11）；留空则继承该策略最新的版本。 */
    private String baseVersionNo;
    /** 版本说明，可选。 */
    private String description;
}
