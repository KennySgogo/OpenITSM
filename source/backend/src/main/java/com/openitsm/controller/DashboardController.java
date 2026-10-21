package com.openitsm.controller;

import com.openitsm.common.response.ApiResponse;
import com.openitsm.config.RequestAuthentication;
import com.openitsm.service.DashboardService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {
    private final DashboardService service;
    public DashboardController(DashboardService service) { this.service = service; }
    @GetMapping("/metrics")
    public ApiResponse<?> metrics(@RequestParam(required = false) String createdFrom,
        @RequestParam(required = false) String createdTo, HttpServletRequest request) {
        return ApiResponse.of(request, service.metrics(RequestAuthentication.from(request), createdFrom, createdTo));
    }
}
