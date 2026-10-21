package com.openitsm.controller;

import com.openitsm.common.response.ApiResponse;
import com.openitsm.config.RequestAuthentication;
import com.openitsm.dto.SaveKnowledgeArticleRequest;
import com.openitsm.service.KnowledgeArticleService;
import com.openitsm.vo.ActionVO;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/knowledge-articles")
public class KnowledgeArticleController {
    private final KnowledgeArticleService service;

    public KnowledgeArticleController(KnowledgeArticleService service) { this.service = service; }

    @GetMapping
    public ApiResponse<?> list(@RequestParam(required = false) String view, @RequestParam(required = false) String page,
        @RequestParam(required = false) String q, @RequestParam(required = false) String category, HttpServletRequest request) {
        return ApiResponse.of(request, service.list(RequestAuthentication.from(request), view, page, q, category));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> detail(@PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.of(request, service.detail(RequestAuthentication.from(request), id));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody SaveKnowledgeArticleRequest body,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return response(request, service.create(RequestAuthentication.from(request), body, key), 201);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> edit(@PathVariable UUID id, @RequestBody SaveKnowledgeArticleRequest body,
        @RequestHeader(value = "If-Match", required = false) String version,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return response(request, service.change(RequestAuthentication.from(request), id, "edit", body, version, key), 200);
    }

    @PostMapping({"/{id}/publish", "/{id}/unpublish"})
    public ResponseEntity<?> publish(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body,
        @RequestHeader(value = "If-Match", required = false) String version,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        RequestAuthentication.emptyBody(body);
        String action = request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/') + 1);
        return response(request, service.change(RequestAuthentication.from(request), id, action, null, version, key), 200);
    }

    private ResponseEntity<?> response(HttpServletRequest request, ActionVO result, int status) {
        var response = ResponseEntity.status(status).eTag("\"" + result.acceptedVersion() + "\"");
        if (result.replayed()) response.header("Idempotent-Replayed", "true");
        return response.body(ApiResponse.of(request, result));
    }
}
