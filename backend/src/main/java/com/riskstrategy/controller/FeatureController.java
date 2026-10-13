package com.riskstrategy.controller;

import com.riskstrategy.common.ApiResponse;
import com.riskstrategy.dto.FeatureResolveInputsRequest;
import com.riskstrategy.entity.Feature;
import com.riskstrategy.entity.FeatureInputVar;
import com.riskstrategy.entity.FeatureOutputVar;
import com.riskstrategy.service.FeatureService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/features")
public class FeatureController {

    private final FeatureService featureService;

    public FeatureController(FeatureService featureService) {
        this.featureService = featureService;
    }

    @GetMapping
    public ApiResponse<?> list(@RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(featureService.list(page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> detail(@PathVariable Long id) {
        return ApiResponse.ok(featureService.detail(id));
    }

    @PostMapping
    public ApiResponse<?> create(@RequestBody Feature feature) {
        return ApiResponse.ok(featureService.create(feature));
    }

    @PutMapping("/{id}")
    public ApiResponse<?> update(@PathVariable Long id, @RequestBody Feature feature) {
        return ApiResponse.ok(featureService.update(id, feature));
    }

    // ---- gap endpoints ----

    @GetMapping("/stats")
    public ApiResponse<?> stats() {
        return ApiResponse.ok(featureService.getStats());
    }

    @PostMapping("/batch-verify")
    public ApiResponse<?> batchVerify(@RequestBody Map<String, List<Long>> body) {
        featureService.batchVerify(body.get("ids"));
        return ApiResponse.ok("批量验证完成");
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<?> publish(@PathVariable Long id) {
        featureService.publish(id);
        return ApiResponse.ok("特征已发布");
    }

    @PostMapping("/{id}/offline")
    public ApiResponse<?> offline(@PathVariable Long id) {
        featureService.offline(id);
        return ApiResponse.ok("特征已下线");
    }

    @PostMapping("/validate-name")
    public ApiResponse<?> validateName(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(featureService.validateName(body.get("code"), body.get("domain")));
    }

    @GetMapping("/similar")
    public ApiResponse<?> similar(@RequestParam String code) {
        return ApiResponse.ok(featureService.findSimilar(code));
    }

    @GetMapping("/{id}/impact")
    public ApiResponse<?> impact(@PathVariable Long id) {
        return ApiResponse.ok(featureService.getImpact(id));
    }

    @PostMapping("/parse-inputs")
    public ApiResponse<?> parseInputs(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(featureService.parseInputs(body.get("sql")));
    }

    // ---- 特征绑定：基础特征（引用视图出参）/ 衍生特征（@@ 引用基础特征） ----

    /** 可用于 @@ 引用的基础特征清单 */
    @GetMapping("/base-features")
    public ApiResponse<?> baseFeatures(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(featureService.listBaseFeatures(limit));
    }

    /** 解析衍生特征表达式里的 @@ 引用（含每个引用带入的入参） */
    @PostMapping("/resolve-refs")
    public ApiResponse<?> resolveRefs(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(featureService.resolveRefs(body.get("expr")));
    }

    /** 按表单态自动解析验证所需入参：基础特征→视图入参；衍生特征→决策入参 + @@ 引用带入的入参 */
    @PostMapping("/resolve-inputs")
    public ApiResponse<?> resolveInputs(@RequestBody FeatureResolveInputsRequest req) {
        return ApiResponse.ok(featureService.resolveInputs(req));
    }

    /** 已创建特征当前应填的入参清单（验证步骤用） */
    @GetMapping("/{id}/resolved-inputs")
    public ApiResponse<?> resolvedInputs(@PathVariable Long id) {
        return ApiResponse.ok(featureService.resolvedInputById(id));
    }

    /** 入参登记：按当前绑定重新解析并写入 feature.input_vars_json */
    @PostMapping("/{id}/sync-inputs")
    public ApiResponse<?> syncInputs(@PathVariable Long id) {
        return ApiResponse.ok(featureService.syncInputVars(id));
    }

    /** 入参定义：保存用户在步骤 2 编辑后的完整入参清单（含移除标记与手工补充行） */
    @PostMapping("/{id}/inputs")
    public ApiResponse<?> saveInputs(@PathVariable Long id, @RequestBody List<FeatureInputVar> rows) {
        return ApiResponse.ok(featureService.saveInputVarRows(id, rows));
    }

    /** 出参定义：保存该特征的出参清单（衍生特征可多个，基础特征固定 1 个） */
    @PostMapping("/{id}/outputs")
    public ApiResponse<?> saveOutputs(@PathVariable Long id, @RequestBody List<FeatureOutputVar> rows) {
        return ApiResponse.ok(featureService.saveOutputVars(id, rows));
    }

    /** 特征改名：修改编码并同步所有按编码引用它的下游配置（衍生特征 @@ 引用 / 策略节点 / 规则 / 交叉树） */
    @PostMapping("/{id}/rename")
    public ApiResponse<?> rename(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(featureService.renameCode(id, body.get("code")));
    }

    @PostMapping("/{id}/trial")
    public ApiResponse<?> trial(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(featureService.trial(id, body));
    }

    @GetMapping("/{id}/precheck")
    public ApiResponse<?> precheck(@PathVariable Long id) {
        return ApiResponse.ok(featureService.precheck(id));
    }

    @PostMapping("/{id}/submit-approval")
    public ApiResponse<?> submitApproval(@PathVariable Long id, @RequestBody Map<String, String> body) {
        featureService.submitApproval(id, body.get("remark"));
        return ApiResponse.ok("审批已提交");
    }
}
