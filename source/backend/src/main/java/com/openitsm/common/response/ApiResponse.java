package com.openitsm.common.response;

import jakarta.servlet.http.HttpServletRequest;

public record ApiResponse<T>(T data, String requestId) {
    public static <T> ApiResponse<T> of(HttpServletRequest request, T data) {
        return new ApiResponse<>(data, (String) request.getAttribute("requestId"));
    }
}
