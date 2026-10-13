package com.riskstrategy.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/** 画布节点（rule 表一行）：坐标 + 节点内规则配置 JSON。 */
@Data
public class GraphNodeDTO {
    private String nodeKey;
    private String nodeType;
    private String title;
    private Integer x;
    private Integer y;
    private JsonNode config;
}
