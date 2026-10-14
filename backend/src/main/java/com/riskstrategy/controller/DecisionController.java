package com.riskstrategy.controller;

import com.riskstrategy.common.ApiResponse;
import com.riskstrategy.dto.SaveDecisionGraphDTO;
import com.riskstrategy.service.DecisionService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/decisions")
public class DecisionController {

    private final DecisionService decisionService;

    public DecisionController(DecisionService decisionService) {
        this.decisionService = decisionService;
    }

    /**
     * 打开编排画布。
     * 不带 versionId：取该策略最新草稿（无则新建并种入 开始/结束）。
     * 带 versionId：打开该版本的画布；该版本没留存画布时自动派生新草稿承接（返回 derivedFromVersionNo）。
     */
    @GetMapping("/by-strategy/{strategyId}")
    public ApiResponse<?> byStrategy(@PathVariable Long strategyId,
                                     @RequestParam(required = false) Long versionId) {
        return ApiResponse.ok(decisionService.draftGraph(strategyId, versionId));
    }

    /** 全量保存画布：节点（rule）与连线（decision_branch） */
    @PutMapping("/{decisionId}/graph")
    public ApiResponse<?> saveGraph(@PathVariable Long decisionId, @RequestBody SaveDecisionGraphDTO body) {
        return ApiResponse.ok(decisionService.saveGraph(decisionId, body));
    }
}
