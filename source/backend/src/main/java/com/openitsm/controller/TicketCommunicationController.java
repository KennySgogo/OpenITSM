package com.openitsm.controller;

import com.openitsm.common.response.ApiResponse;
import com.openitsm.config.RequestAuthentication;
import com.openitsm.dto.CreateTicketReplyRequest;
import com.openitsm.service.TicketCommunicationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tickets/{id}")
public class TicketCommunicationController {
    private final TicketCommunicationService service;

    public TicketCommunicationController(TicketCommunicationService service) { this.service = service; }

    @GetMapping("/replies")
    public ApiResponse<?> replies(@PathVariable UUID id, @RequestParam(required = false) String page, HttpServletRequest request) {
        return ApiResponse.of(request, service.replies(RequestAuthentication.from(request), id, page));
    }

    @GetMapping("/operations")
    public ApiResponse<?> operations(@PathVariable UUID id, @RequestParam(required = false) String page, HttpServletRequest request) {
        return ApiResponse.of(request, service.operations(RequestAuthentication.from(request), id, page));
    }

    @PostMapping("/replies")
    public ResponseEntity<?> reply(@PathVariable UUID id, @RequestBody CreateTicketReplyRequest body,
        @RequestHeader(value = "If-Match", required = false) String version,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return append(id, false, body, version, key, request);
    }

    @PostMapping("/internal-notes")
    public ResponseEntity<?> note(@PathVariable UUID id, @RequestBody CreateTicketReplyRequest body,
        @RequestHeader(value = "If-Match", required = false) String version,
        @RequestHeader(value = "Idempotency-Key", required = false) String key, HttpServletRequest request) {
        return append(id, true, body, version, key, request);
    }

    private ResponseEntity<?> append(UUID id, boolean internal, CreateTicketReplyRequest body, String version, String key,
        HttpServletRequest request) {
        var result = service.append(RequestAuthentication.from(request), id, internal, body, version, key,
            UUID.fromString((String) request.getAttribute("requestId")));
        var response = ResponseEntity.ok().eTag("\"" + result.acceptedVersion() + "\"");
        if (result.replayed()) response.header("Idempotent-Replayed", "true");
        return response.body(ApiResponse.of(request, result));
    }
}
