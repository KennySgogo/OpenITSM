package com.openitsm.controller;

import com.openitsm.common.response.ApiResponse;
import com.openitsm.config.RequestAuthentication;
import com.openitsm.dto.UpdateProfileRequest;
import com.openitsm.service.ProfileService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {
    private final ProfileService service;
    public ProfileController(ProfileService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<?> detail(HttpServletRequest request) {
        var result = service.detail(RequestAuthentication.from(request));
        return ResponseEntity.ok().body(ApiResponse.of(request, result));
    }

    @PatchMapping
    public ResponseEntity<?> update(@RequestBody UpdateProfileRequest body, HttpServletRequest request) {
        var result = service.update(RequestAuthentication.from(request), body);
        var response = ResponseEntity.ok();

        return response.body(ApiResponse.of(request, result));
    }
}
