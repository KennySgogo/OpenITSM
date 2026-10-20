package com.openitsm.controller;

import com.openitsm.common.response.ApiResponse;
import com.openitsm.config.RequestAuthentication;
import com.openitsm.dto.SaveTicketTypeRequest;
import com.openitsm.service.TicketTypeService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ticket-types")
public class TicketTypeController {
    private final TicketTypeService service;
    public TicketTypeController(TicketTypeService service) { this.service = service; }
    @GetMapping
    public ApiResponse<?> list(@RequestParam(required = false) String view, HttpServletRequest request) {
        return ApiResponse.of(request, Map.of("items", service.list(RequestAuthentication.from(request), view)));
    }
    @PostMapping
    public ResponseEntity<?> create(@RequestBody SaveTicketTypeRequest body,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        var result = service.create(RequestAuthentication.from(request), body, key);
        var response = ResponseEntity.status(201);
        if (result.replayed()) response.header("Idempotent-Replayed", "true");
        return response.body(ApiResponse.of(request, result));
    }
    @PatchMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable UUID id, @RequestBody SaveTicketTypeRequest body,
        @RequestHeader(value = "If-Match", required = false) String version,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return change(id, body, version, key, request);
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<?> remove(@PathVariable UUID id,
        @RequestHeader(value = "If-Match", required = false) String version,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return change(id, null, version, key, request);
    }
    private ResponseEntity<?> change(UUID id, SaveTicketTypeRequest body, String version, String key, HttpServletRequest request) {
        var result = service.change(RequestAuthentication.from(request), id, body, version, key);
        var response = ResponseEntity.ok().eTag("\"" + result.acceptedVersion() + "\"");
        if (result.replayed()) response.header("Idempotent-Replayed", "true");
        return response.body(ApiResponse.of(request, result));
    }
}
