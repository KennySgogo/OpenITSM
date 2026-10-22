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
    public ResponseEntity<?> create(@RequestBody SaveTicketTypeRequest body, HttpServletRequest request) {
        var result = service.create(RequestAuthentication.from(request), body);
        var response = ResponseEntity.status(201);

        return response.body(ApiResponse.of(request, result));
    }
    @PatchMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable UUID id, @RequestBody SaveTicketTypeRequest body, HttpServletRequest request) {
        return change(id, body, request);
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<?> remove(@PathVariable UUID id, HttpServletRequest request) {
        return change(id, null, request);
    }
    private ResponseEntity<?> change(UUID id, SaveTicketTypeRequest body, HttpServletRequest request) {
        var result = service.change(RequestAuthentication.from(request), id, body);
        var response = ResponseEntity.ok();

        return response.body(ApiResponse.of(request, result));
    }
}
