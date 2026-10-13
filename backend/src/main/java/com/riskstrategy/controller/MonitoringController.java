package com.riskstrategy.controller;

import com.riskstrategy.common.ApiResponse;
import com.riskstrategy.service.MonitoringService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/monitoring")
public class MonitoringController {

    private final MonitoringService monitoringService;

    public MonitoringController(MonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    @GetMapping
    public ApiResponse<?> monitoring() {
        return ApiResponse.ok(monitoringService.build());
    }
}
