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

    /** 取某策略的最新编排草稿（无则新建并种入 开始/结束），返回完整画布 */
    @GetMapping("/by-strategy/{strategyId}")
    public ApiResponse<?> byStrategy(@PathVariable Long strategyId) {
        return ApiResponse.ok(decisionService.draftGraph(strategyId));
    }

    /** 全量保存画布：节点（rule）与连线（decision_branch） */
    @PutMapping("/{decisionId}/graph")
    public ApiResponse<?> saveGraph(@PathVariable Long decisionId, @RequestBody SaveDecisionGraphDTO body) {
        return ApiResponse.ok(decisionService.saveGraph(decisionId, body));
    }
}
