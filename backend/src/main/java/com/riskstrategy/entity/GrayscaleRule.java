package com.riskstrategy.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("grayscale_rule")
public class GrayscaleRule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long versionId;
    /** 切流方式：RANDOM 随机切流 / PERCENT 百分比切流 / WHITELIST 白名单切流 */
    private String mode;
    private Integer trafficPct;
    private Integer whitelistUsers;
    private String channel;
    private String userTag;
    private String status;
}
