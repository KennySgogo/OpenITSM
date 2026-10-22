package com.openitsm.controller;

import com.openitsm.config.RequestAuthentication;
import com.openitsm.vo.ActionVO;
import com.openitsm.common.response.ApiResponse;
import com.openitsm.dto.DisableUserRequest;
import com.openitsm.dto.InviteUserRequest;
import com.openitsm.dto.UpdateUserRequest;
import com.openitsm.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService service;
    public UserController(UserService service) { this.service = service; }

    @GetMapping
    public ApiResponse<?> list(@RequestParam(defaultValue = "1") int page, @RequestParam(required = false) String q,
        @RequestParam(required = false) String role, @RequestParam(required = false) String status, HttpServletRequest req) {
        return ApiResponse.of(req, service.list(page, q, role, status));
    }

    @GetMapping("/assignees")
    public ApiResponse<?> assignees(HttpServletRequest req) { return ApiResponse.of(req, service.assignees()); }

    @PostMapping
    public ResponseEntity<?> invite(@RequestBody InviteUserRequest body, HttpServletRequest req) {
        var ack = service.invite(RequestAuthentication.from(req), body, requestId(req));
        return response(req, ack, 201);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> patch(@PathVariable UUID id, @RequestBody UpdateUserRequest body, HttpServletRequest req) {
        return response(req, service.change(RequestAuthentication.from(req), id, "PATCH", body, null, requestId(req)), 200);
    }

    @PostMapping("/{id}/disable")
    public ResponseEntity<?> disable(@PathVariable UUID id, @RequestBody DisableUserRequest body, HttpServletRequest req) {
        return response(req, service.change(RequestAuthentication.from(req), id, "disable", null, body.reason(), requestId(req)), 200);
    }

    @PostMapping({"/{id}/unlock", "/{id}/invitation-resend"})
    public ResponseEntity<?> action(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        RequestAuthentication.emptyBody(body);
        String action = req.getRequestURI().substring(req.getRequestURI().lastIndexOf('/') + 1);
        return response(req, service.change(RequestAuthentication.from(req), id, action, null, null, requestId(req)), action.equals("unlock") ? 200 : 202);
    }

    private UUID requestId(HttpServletRequest req) { return UUID.fromString((String) req.getAttribute("requestId")); }

    private ResponseEntity<?> response(HttpServletRequest req, ActionVO ack, int status) {
        var result = ResponseEntity.status(status);
        if (status == 201) result.location(URI.create("/api/v1/users?resourceId=" + ack.resourceId()));

        return result.body(ApiResponse.of(req, ack));
    }
}
