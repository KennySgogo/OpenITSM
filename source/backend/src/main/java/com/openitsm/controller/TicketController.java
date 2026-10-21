package com.openitsm.controller;

import com.openitsm.common.response.ApiResponse;
import com.openitsm.config.RequestAuthentication;
import com.openitsm.dto.CreateTicketRequest;
import com.openitsm.dto.AssignTicketRequest;
import com.openitsm.dto.TicketReasonRequest;
import com.openitsm.dto.ResolveTicketRequest;
import com.openitsm.dto.ChangeTicketPriorityRequest;
import com.openitsm.dto.ConfirmTicketClassificationRequest;
import com.openitsm.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.openitsm.service.TicketService;
import com.openitsm.service.ClassificationSuggestionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ModelAttribute;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {
    private final TicketService service;
    private final ClassificationSuggestionService suggestions;

    public TicketController(TicketService service, ClassificationSuggestionService suggestions) {
        this.service = service; this.suggestions = suggestions;
    }

    @GetMapping
    public ApiResponse<?> list(@RequestParam(required = false) String view, @RequestParam(required = false) String page,
        @RequestParam(required = false) String status, @RequestParam(required = false) String businessType,
        @RequestParam(required = false) String q, @ModelAttribute com.openitsm.dto.TicketQueryRequest filters, HttpServletRequest request) {
        return ApiResponse.of(request, service.list(RequestAuthentication.from(request), view, page, status, businessType, q, filters));
    }

    @GetMapping("/filter-options")
    public ApiResponse<?> options(HttpServletRequest request) {
        return ApiResponse.of(request, service.filterOptions(RequestAuthentication.from(request)));
    }

    @GetMapping("/{id}")
    public ApiResponse<?> detail(@PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.of(request, service.detail(RequestAuthentication.from(request), id));
    }

    @PostMapping({"/{id}/claim", "/{id}/resume"})
    public ResponseEntity<?> emptyAction(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body,
        @RequestHeader(value = "If-Match", required = false) String version,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        RequestAuthentication.emptyBody(body);
        return change(id, null, version, key, request);
    }

    @PostMapping({"/{id}/assignments", "/{id}/transfers"})
    public ResponseEntity<?> assign(@PathVariable UUID id, @RequestBody AssignTicketRequest body,
        @RequestHeader(value = "If-Match", required = false) String version,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return change(id, body, version, key, request);
    }

    @PostMapping({"/{id}/suspensions", "/{id}/reopen", "/{id}/rejections"})
    public ResponseEntity<?> reasonAction(@PathVariable UUID id, @RequestBody TicketReasonRequest body,
        @RequestHeader(value = "If-Match", required = false) String version,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return change(id, body, version, key, request);
    }

    @PostMapping("/{id}/close")
    public ResponseEntity<?> close(@PathVariable UUID id, @RequestBody(required = false) TicketReasonRequest body,
        @RequestHeader(value = "If-Match", required = false) String version,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return change(id, body, version, key, request);
    }

    @PostMapping("/{id}/resolutions")
    public ResponseEntity<?> resolve(@PathVariable UUID id, @RequestBody ResolveTicketRequest body,
        @RequestHeader(value = "If-Match", required = false) String version,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return change(id, body, version, key, request);
    }

    @PostMapping("/{id}/priority-changes")
    public ResponseEntity<?> priority(@PathVariable UUID id, @RequestBody ChangeTicketPriorityRequest body,
        @RequestHeader(value = "If-Match", required = false) String version,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return change(id, body, version, key, request);
    }

    @PostMapping("/{id}/classifications")
    public ResponseEntity<?> classification(@PathVariable UUID id, @RequestBody JsonNode body,
        @RequestHeader(value = "If-Match", required = false) String version,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        if (body == null || !body.isObject() || body.size() != 1 || !body.path("businessTypeId").isTextual())
            throw BusinessException.field("businessTypeId", "请仅提交已有业务类型 ID");
        String raw = body.path("businessTypeId").textValue();
        UUID typeId;
        try { typeId = UUID.fromString(raw); }
        catch (IllegalArgumentException ex) { throw BusinessException.field("businessTypeId", "业务类型 ID 无效"); }
        if (!raw.equalsIgnoreCase(typeId.toString())) throw BusinessException.field("businessTypeId", "业务类型 ID 无效");
        return change(id, new ConfirmTicketClassificationRequest(typeId), version, key, request);
    }

    @PostMapping("/{id}/ai/classification-suggestions")
    public ApiResponse<?> classificationSuggestion(@PathVariable UUID id,
        @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        RequestAuthentication.emptyBody(body);
        return ApiResponse.of(request, suggestions.suggest(RequestAuthentication.from(request), id));
    }

    private ResponseEntity<?> change(UUID id, Object body, String version, String key, HttpServletRequest request) {
        String action = request.getRequestURI().substring(request.getRequestURI().lastIndexOf('/') + 1);
        var result = service.change(RequestAuthentication.from(request), id, action, body, version, key,
            UUID.fromString((String) request.getAttribute("requestId")));
        var response = ResponseEntity.ok().eTag("\"" + result.acceptedVersion() + "\"");
        if (result.replayed()) response.header("Idempotent-Replayed", "true");
        return response.body(ApiResponse.of(request, result));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateTicketRequest body,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        var ticket = service.create(RequestAuthentication.from(request), body, key, UUID.fromString((String) request.getAttribute("requestId")));
        var response = ResponseEntity.status(201);
        if (ticket.replayed()) response.header("Idempotent-Replayed", "true");
        return response.body(ApiResponse.of(request, ticket));
    }
}
