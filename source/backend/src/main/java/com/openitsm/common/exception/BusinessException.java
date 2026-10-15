package com.openitsm.common.exception;

import java.util.List;

public class BusinessException extends RuntimeException {
    public final int status;
    public final String code;
    public final List<FieldError> fields;
    public final Integer retryAfter;

    public record FieldError(String field, String code, String message) {}

    public BusinessException(int status, String code, String message) {
        this(status, code, message, List.of(), null);
    }

    public BusinessException(int status, String code, String message, List<FieldError> fields, Integer retryAfter) {
        super(message);
        this.status = status;
        this.code = code;
        this.fields = fields;
        this.retryAfter = retryAfter;
    }

    public static BusinessException field(String field, String message) {
        return new BusinessException(400, "VALIDATION_FAILED", message,
            List.of(new FieldError(field, "INVALID", message)), null);
    }

    public static BusinessException rate(int seconds) {
        return new BusinessException(429, "RATE_LIMITED", "请求过于频繁，请稍后重试", List.of(), Math.max(1, seconds));
    }
}
