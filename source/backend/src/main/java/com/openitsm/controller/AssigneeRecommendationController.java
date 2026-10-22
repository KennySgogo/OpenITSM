package com.openitsm.controller;

import com.openitsm.common.response.ApiResponse;
import com.openitsm.config.RequestAuthentication;
import com.openitsm.dto.AssigneeRecommendationRequest;
import com.openitsm.service.AssigneeRecommendationService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
public class AssigneeRecommendationController {
    private final AssigneeRecommendationService recommendations;
    public AssigneeRecommendationController(AssigneeRecommendationService recommendations) { this.recommendations = recommendations; }

    @PostMapping("/api/v1/ai/assignee-recommendations")
    public ApiResponse<?> create(@RequestBody AssigneeRecommendationRequest body, HttpServletRequest request) {
        return ApiResponse.of(request, recommendations.recommend(RequestAuthentication.from(request), body));
    }

    @PostMapping("/api/v1/tickets/{id}/ai/assignee-recommendations")
    public ApiResponse<?> ticket(@PathVariable UUID id, HttpServletRequest request) {
        return ApiResponse.of(request, recommendations.recommend(RequestAuthentication.from(request), id));
    }
}
