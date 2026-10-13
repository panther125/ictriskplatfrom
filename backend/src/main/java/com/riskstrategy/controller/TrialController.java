package com.riskstrategy.controller;

import com.riskstrategy.common.ApiResponse;
import com.riskstrategy.entity.TrialCase;
import com.riskstrategy.service.TrialService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trial")
public class TrialController {

    private final TrialService trialService;

    public TrialController(TrialService trialService) {
        this.trialService = trialService;
    }

    @PostMapping("/execute")
    public ApiResponse<?> execute(@RequestBody Map<String, Object> params) {
        return ApiResponse.ok(trialService.execute(params));
    }

    /** 解析入参：按所选策略/版本扫描决策图引用，生成试算输入模板（策略自定义入参 + 被引用特征的入参），纯查询 */
    @PostMapping("/resolve-inputs")
    public ApiResponse<?> resolveInputs(@RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(trialService.resolveInputs(body));
    }

    /**
     * 试算可选的策略与版本清单。
     * 前端据此渲染「策略编码」下拉，试算时把 code 透传回来（{@code strategy_code}）。
     */
    @GetMapping("/strategies")
    public ApiResponse<?> strategies() {
        return ApiResponse.ok(trialService.listStrategyOptions());
    }

    @GetMapping("/recent")
    public ApiResponse<?> recent() {
        return ApiResponse.ok(trialService.recent());
    }

    @GetMapping("/{id}")
    public ApiResponse<?> detail(@PathVariable Long id) {
        return ApiResponse.ok(trialService.detail(id));
    }

    // ---- gap: trial case CRUD ----

    @GetMapping("/cases")
    public ApiResponse<?> listCases(@RequestParam(required = false) Long strategyId) {
        return ApiResponse.ok(trialService.listCases(strategyId));
    }

    @PostMapping("/cases")
    public ApiResponse<?> createCase(@RequestBody TrialCase trialCase) {
        return ApiResponse.ok(trialService.createCase(trialCase));
    }

    @PutMapping("/cases/{id}")
    public ApiResponse<?> updateCase(@PathVariable Long id, @RequestBody TrialCase trialCase) {
        return ApiResponse.ok(trialService.updateCase(id, trialCase));
    }

    @DeleteMapping("/cases/{id}")
    public ApiResponse<?> deleteCase(@PathVariable Long id) {
        trialService.deleteCase(id);
        return ApiResponse.ok("用例已删除");
    }

    /** 跑用例集；body 可带 strategyId 只跑该策略的用例（不传则跑全部） */
    @PostMapping("/cases/run-all")
    public ApiResponse<?> runAllCases(@RequestBody(required = false) Map<String, Object> body) {
        Long strategyId = body == null || body.get("strategyId") == null
                ? null : Long.valueOf(body.get("strategyId").toString());
        return ApiResponse.ok(trialService.runAllCases(strategyId));
    }

    // ---- gap: batch-replay ----

    @PostMapping("/batch-replay")
    public ApiResponse<?> batchReplay(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> traceIds = (List<String>) body.get("traceIds");
        Long strategyId = body.get("strategyId") != null ? Long.valueOf(body.get("strategyId").toString()) : null;
        String version = (String) body.get("version");
        return ApiResponse.ok(trialService.batchReplay(traceIds, strategyId, version));
    }

    // ---- gap: shadow-compare ----

    @PostMapping("/shadow-compare")
    public ApiResponse<?> shadowCompare(@RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> inputs = (Map<String, Object>) body.get("inputs");
        Long strategyId = body.get("strategyId") != null ? Long.valueOf(body.get("strategyId").toString()) : null;
        return ApiResponse.ok(trialService.shadowCompare(inputs, strategyId));
    }
}
