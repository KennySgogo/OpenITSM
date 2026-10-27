package com.openitsm.controller;

import com.openitsm.common.response.ApiResponse;
import com.openitsm.config.RequestAuthentication;
import com.openitsm.service.ReplyDraftService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReplyDraftController {
    private final ReplyDraftService drafts;
    public ReplyDraftController(ReplyDraftService drafts) { this.drafts = drafts; }

    @PostMapping("/api/v1/tickets/{id}/ai/reply-drafts")
    public ApiResponse<?> generate(@PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.of(request, drafts.generate(RequestAuthentication.from(request), id));
    }
}
