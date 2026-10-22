package com.openitsm.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.common.response.ApiResponse;
import com.openitsm.config.RequestAuthentication;
import com.openitsm.service.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {
    private final SettingsService service;
    public SettingsController(SettingsService service) { this.service = service; }
    @GetMapping
    public ResponseEntity<?> detail(HttpServletRequest request) {
        var value = service.detail(RequestAuthentication.from(request));
        return ResponseEntity.ok().body(ApiResponse.of(request, value));
    }
    @PatchMapping
    public ResponseEntity<?> update(@RequestBody JsonNode body, HttpServletRequest request) {
        var value = service.update(RequestAuthentication.from(request), body);
        var response = ResponseEntity.ok();

        return response.body(ApiResponse.of(request, value));
    }
    @PostMapping("/model-connection-tests")
    public Object test(@RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        if (body != null && (!body.isObject() || !body.isEmpty())) throw BusinessException.field("body", "连接测试不接受自定义内容");
        return ApiResponse.of(request, service.testModel(RequestAuthentication.from(request)));
    }
}
