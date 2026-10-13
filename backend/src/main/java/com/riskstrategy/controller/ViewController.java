package com.riskstrategy.controller;

import com.riskstrategy.common.ApiResponse;
import com.riskstrategy.dto.ViewCreateRequest;
import com.riskstrategy.dto.ViewDegradeConfigDTO;
import com.riskstrategy.dto.ViewValidateRequest;
import com.riskstrategy.entity.DataSourceView;
import com.riskstrategy.entity.ViewField;
import com.riskstrategy.entity.ViewInputVar;
import com.riskstrategy.service.ViewFileService;
import com.riskstrategy.service.ViewService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/views")
public class ViewController {

    private final ViewService viewService;
    private final ViewFileService viewFileService;

    public ViewController(ViewService viewService, ViewFileService viewFileService) {
        this.viewService = viewService;
        this.viewFileService = viewFileService;
    }

    @GetMapping
    public ApiResponse<?> list(@RequestParam(defaultValue = "1") int page,
                               @RequestParam(defaultValue = "10") int size,
                               @RequestParam(required = false) String status) {
        return ApiResponse.ok(viewService.list(page, size, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> detail(@PathVariable Long id) {
        return ApiResponse.ok(viewService.detail(id));
    }

    @PostMapping
    public ApiResponse<?> create(@RequestBody ViewCreateRequest req) {
        return ApiResponse.ok(viewService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<?> update(@PathVariable Long id, @RequestBody DataSourceView view) {
        return ApiResponse.ok(viewService.update(id, view));
    }

    @PutMapping("/{id}/fields")
    public ApiResponse<?> updateFields(@PathVariable Long id, @RequestBody List<ViewField> fields) {
        return ApiResponse.ok(viewService.updateFields(id, fields));
    }

    @PutMapping("/{id}/inputs")
    public ApiResponse<?> updateInputs(@PathVariable Long id, @RequestBody List<ViewInputVar> inputs) {
        return ApiResponse.ok(viewService.updateInputs(id, inputs));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<?> publish(@PathVariable Long id) {
        viewService.publish(id);
        return ApiResponse.ok("视图已发布");
    }

    @PostMapping("/{id}/offline")
    public ApiResponse<?> offline(@PathVariable Long id) {
        viewService.offline(id);
        return ApiResponse.ok("视图已下线（逻辑删除）");
    }

    // ---- gap endpoints ----

    @GetMapping("/datasource-health")
    public ApiResponse<?> datasourceHealth(@RequestParam(required = false, defaultValue = "1h") String range) {
        return ApiResponse.ok(viewService.getDatasourceHealth(range));
    }

    @GetMapping("/{id}/lineage")
    public ApiResponse<?> lineage(@PathVariable Long id) {
        return ApiResponse.ok(viewService.getLineage(id));
    }

    @GetMapping("/{id}/degrade-config")
    public ApiResponse<?> getDegradeConfig(@PathVariable Long id) {
        return ApiResponse.ok(viewService.getDegradeConfig(id));
    }

    @PutMapping("/{id}/degrade-config")
    public ApiResponse<?> updateDegradeConfig(@PathVariable Long id,
                                              @RequestBody ViewDegradeConfigDTO dto) {
        return ApiResponse.ok(viewService.updateDegradeConfig(id, dto));
    }

    @PostMapping("/parse-script")
    public ApiResponse<?> parseScript(@RequestBody Map<String, String> body) {
        return ApiResponse.ok(viewService.parseScript(body.get("sourceType"), body.get("scriptContent")));
    }

    /** CSV / PMML 视图文件上传：解析并把文件内容内嵌进脚本 JSON（不落盘） */
    @PostMapping("/upload")
    public ApiResponse<?> upload(@RequestParam("file") MultipartFile file,
                                 @RequestParam("sourceType") String sourceType) {
        return ApiResponse.ok(viewFileService.upload(file, sourceType));
    }

    @PostMapping("/validate")
    public ApiResponse<?> validate(@RequestBody ViewValidateRequest req) {
        return ApiResponse.ok(viewService.validate(req));
    }
}
