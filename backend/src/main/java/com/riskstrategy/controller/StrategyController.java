package com.riskstrategy.controller;

import com.riskstrategy.common.ApiResponse;
import com.riskstrategy.dto.StrategyTrafficDTO;
import com.riskstrategy.service.StrategyService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/strategies")
public class StrategyController {

    private final StrategyService strategyService;

    public StrategyController(StrategyService strategyService) {
        this.strategyService = strategyService;
    }

    /** 策略管理列表：只暴露 草稿 / 已发布 两种状态 */
    @GetMapping
    public ApiResponse<?> list() {
        return ApiResponse.ok(strategyService.manageList());
    }

    /** 策略内部版本列表：草稿 / 已发布 / 已下线 三态 + 流量比例 + 切流方式 */
    @GetMapping("/{strategyId}/versions")
    public ApiResponse<?> versions(@PathVariable Long strategyId) {
        return ApiResponse.ok(strategyService.versionsWithTraffic(strategyId));
    }

    @GetMapping("/versions/{versionId}/graph")
    public ApiResponse<?> graph(@PathVariable Long versionId) {
        return ApiResponse.ok(strategyService.graph(versionId));
    }

    /** 决策级切流配置：一次给决策下多个版本分配流量份额（随机 / 百分比合计必须 100%） */
    @PutMapping("/{strategyId}/traffic")
    public ApiResponse<?> updateStrategyTraffic(@PathVariable Long strategyId, @RequestBody StrategyTrafficDTO body) {
        strategyService.updateStrategyTraffic(strategyId, body);
        return ApiResponse.ok("切流配置已更新");
    }

    /** 发布该版本：同策略其它线上版本自动下线 */
    @PostMapping("/versions/{versionId}/publish")
    public ApiResponse<?> publish(@PathVariable Long versionId) {
        strategyService.publishVersion(versionId);
        return ApiResponse.ok("版本已发布");
    }

    /** 下线该版本 */
    @PostMapping("/versions/{versionId}/offline")
    public ApiResponse<?> offline(@PathVariable Long versionId) {
        strategyService.offlineVersion(versionId);
        return ApiResponse.ok("版本已下线");
    }
}
