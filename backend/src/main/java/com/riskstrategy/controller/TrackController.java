package com.riskstrategy.controller;

import com.riskstrategy.common.ApiResponse;
import com.riskstrategy.entity.TrackSamplingConfig;
import com.riskstrategy.service.TrackService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/track")
public class TrackController {

    private final TrackService trackService;

    public TrackController(TrackService trackService) {
        this.trackService = trackService;
    }

    @PostMapping("/invoke")
    public ApiResponse<?> invoke(@RequestBody Map<String, Object> params) {
        return ApiResponse.ok(trackService.invoke(params));
    }

    @PostMapping("/trial")
    public ApiResponse<?> trial(@RequestBody Map<String, Object> params) {
        return ApiResponse.ok(trackService.trial(params));
    }

    @PostMapping("/{id}/replay")
    public ApiResponse<?> replay(@PathVariable Long id) {
        return ApiResponse.ok(trackService.replay(id));
    }

    @GetMapping("/recent")
    public ApiResponse<?> recent() {
        return ApiResponse.ok(trackService.recent());
    }

    @GetMapping("/search")
    public ApiResponse<?> search(@RequestParam(required = false) String traceId,
                                 @RequestParam(required = false) Long strategyId,
                                 @RequestParam(required = false) String conclusion,
                                 @RequestParam(required = false) String version) {
        return ApiResponse.ok(trackService.search(traceId, strategyId, conclusion, version));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> detail(@PathVariable Long id) {
        return ApiResponse.ok(trackService.detail(id));
    }

    /** 只读：该 trace 对应的策略画布（节点 / 连线），前端据此回看运行路线并高亮 */
    @GetMapping("/{id}/canvas")
    public ApiResponse<?> canvas(@PathVariable Long id) {
        return ApiResponse.ok(trackService.canvasOf(id));
    }

    // ---- gap endpoints ----

    @GetMapping("/stats")
    public ApiResponse<?> stats(@RequestParam(required = false, defaultValue = "1h") String range) {
        return ApiResponse.ok(trackService.getStats(range));
    }

    @GetMapping("/sampling-config")
    public ApiResponse<?> getSamplingConfig() {
        return ApiResponse.ok(trackService.getSamplingConfig());
    }

    @PutMapping("/sampling-config")
    public ApiResponse<?> updateSamplingConfig(@RequestBody TrackSamplingConfig config) {
        return ApiResponse.ok(trackService.updateSamplingConfig(config));
    }

    @GetMapping("/degrade-sources")
    public ApiResponse<?> degradeSources(@RequestParam(required = false, defaultValue = "1h") String range) {
        return ApiResponse.ok(trackService.getDegradeSources(range));
    }
}
