package com.openitsm.controller;

import com.openitsm.common.response.ApiResponse;
import com.openitsm.config.RequestAuthentication;
import com.openitsm.dto.CreateTicketClassificationSuggestionRequest;
import com.openitsm.service.ClassificationSuggestionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ClassificationSuggestionController {
    private final ClassificationSuggestionService suggestions;

    public ClassificationSuggestionController(ClassificationSuggestionService suggestions) {
        this.suggestions = suggestions;
    }

    @PostMapping("/api/v1/ai/classification-suggestions")
    public ApiResponse<?> suggest(@RequestBody CreateTicketClassificationSuggestionRequest body, HttpServletRequest request) {
        return ApiResponse.of(request, suggestions.suggestBeforeCreate(RequestAuthentication.from(request), body));
    }
}
