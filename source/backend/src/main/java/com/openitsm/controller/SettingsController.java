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
        return ResponseEntity.ok().eTag("\"" + value.version() + "\"").body(ApiResponse.of(request, value));
    }
    @PatchMapping
    public ResponseEntity<?> update(@RequestBody JsonNode body,
        @RequestHeader(value = "If-Match", required = false) String version,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        var value = service.update(RequestAuthentication.from(request), body, version, key);
        var response = ResponseEntity.ok().eTag("\"" + value.acceptedVersion() + "\"");
        if (value.replayed()) response.header("Idempotent-Replayed", "true");
        return response.body(ApiResponse.of(request, value));
    }
    @PostMapping("/model-connection-tests")
    public Object test(@RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        if (body != null && (!body.isObject() || !body.isEmpty())) throw BusinessException.field("body", "连接测试不接受自定义内容");
        return ApiResponse.of(request, service.testModel(RequestAuthentication.from(request)));
    }
}
