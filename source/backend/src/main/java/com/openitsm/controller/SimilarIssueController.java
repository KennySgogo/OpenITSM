package com.openitsm.controller;

import com.openitsm.common.response.ApiResponse;
import com.openitsm.config.RequestAuthentication;
import com.openitsm.service.SimilarIssueService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class SimilarIssueController {
    private final SimilarIssueService similar;
    public SimilarIssueController(SimilarIssueService similar) { this.similar = similar; }

    @PostMapping("/api/v1/tickets/{id}/ai/similar-issues")
    public ApiResponse<?> search(@PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.of(request, similar.search(RequestAuthentication.from(request), id));
    }
}
