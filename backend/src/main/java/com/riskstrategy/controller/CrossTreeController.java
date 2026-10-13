package com.riskstrategy.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskstrategy.common.ApiResponse;
import com.riskstrategy.entity.CrossTreeCell;
import com.riskstrategy.entity.CrossTreeDimension;
import com.riskstrategy.service.CrossTreeService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cross-trees")
public class CrossTreeController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CrossTreeService crossTreeService;

    public CrossTreeController(CrossTreeService crossTreeService) {
        this.crossTreeService = crossTreeService;
    }

    @GetMapping
    public ApiResponse<?> list(@RequestParam(required = false) Long strategyId) {
        return ApiResponse.ok(crossTreeService.list(strategyId));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> detail(@PathVariable Long id,
                                 @RequestParam(required = false) String slice) {
        return ApiResponse.ok(crossTreeService.detail(id, slice));
    }

    // ---- gap endpoints ----

    @SuppressWarnings("unchecked")
    @PutMapping("/{id}/cells")
    public ApiResponse<?> updateCells(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        List<?> raw = (List<?>) body.get("cells");
        List<CrossTreeCell> cells = JSON.convertValue(raw,
                JSON.getTypeFactory().constructCollectionType(List.class, CrossTreeCell.class));
        crossTreeService.updateCells(id, cells);
        return ApiResponse.ok("单元格已更新");
    }

    @SuppressWarnings("unchecked")
    @PutMapping("/{id}/dimensions")
    public ApiResponse<?> updateDimensions(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        List<?> raw = (List<?>) body.get("dimensions");
        List<CrossTreeDimension> dims = JSON.convertValue(raw,
                JSON.getTypeFactory().constructCollectionType(List.class, CrossTreeDimension.class));
        crossTreeService.updateDimensions(id, dims);
        return ApiResponse.ok("维度已更新");
    }

    @GetMapping("/{id}/compare")
    public ApiResponse<?> compare(@PathVariable Long id,
                                  @RequestParam(required = false) String onlineVersion) {
        return ApiResponse.ok(crossTreeService.compare(id, onlineVersion));
    }

    @GetMapping("/{id}/validate")
    public ApiResponse<?> validate(@PathVariable Long id) {
        return ApiResponse.ok(crossTreeService.validate(id));
    }

    @PostMapping("/{id}/submit-approval")
    public ApiResponse<?> submitApproval(@PathVariable Long id) {
        crossTreeService.submitApproval(id);
        return ApiResponse.ok("审批已提交");
    }
}
