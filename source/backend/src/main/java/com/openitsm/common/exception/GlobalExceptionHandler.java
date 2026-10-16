package com.openitsm.common.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openitsm.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<?> uploadSize(Exception ex, HttpServletRequest req) {
        return known(new BusinessException(413, "REQUEST_TOO_LARGE", "请求正文超过大小限制"), req);
    }

    @ExceptionHandler({org.springframework.web.multipart.MultipartException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class})
    ResponseEntity<?> multipart(Exception ex, HttpServletRequest req) {
        return known(new BusinessException(400, "VALIDATION_FAILED", "请求格式或字段不正确"), req);
    }

    public static Map<String, Object> body(HttpServletRequest req, BusinessException ex) {
        return Map.of("error", Map.of("code", ex.code, "message", ex.getMessage(),
            "fieldErrors", ex.fields, "retryable", ex.status == 429 || ex.status == 503),
            "requestId", String.valueOf(req.getAttribute("requestId")));
    }

    public static void write(ObjectMapper json, HttpServletRequest req, HttpServletResponse res, BusinessException ex) throws IOException {
        res.setStatus(ex.status);
        res.setContentType("application/json;charset=UTF-8");
        if (ex.retryAfter != null) res.setHeader("Retry-After", ex.retryAfter.toString());
        json.writeValue(res.getOutputStream(), body(req, ex));
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<?> known(BusinessException ex, HttpServletRequest req) {
        var builder = ResponseEntity.status(ex.status);
        if (ex.retryAfter != null) builder.header("Retry-After", ex.retryAfter.toString());
        return builder.body(body(req, ex));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> invalid(Exception ex, HttpServletRequest req) {
        return known(new BusinessException(400, "VALIDATION_FAILED", "请求格式或字段不正确"), req);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<?> missing(Exception ex, HttpServletRequest req) {
        return known(new BusinessException(404, "RESOURCE_NOT_FOUND", "资源不存在或不可访问"), req);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> method(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return ResponseEntity.status(405).header("Allow", String.join(", ", ex.getSupportedMethods() == null ? new String[0] : ex.getSupportedMethods()))
            .body(body(req, new BusinessException(405, "METHOD_NOT_ALLOWED", "请求方法不支持")));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<?> media(Exception ex, HttpServletRequest req) {
        return known(new BusinessException(415, "MEDIA_TYPE_UNSUPPORTED", "请求正文类型不支持"), req);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> database(Exception ex, HttpServletRequest req) {
        LOG.error("dependency failure requestId={}", req.getAttribute("requestId"));
        return known(new BusinessException(503, "DEPENDENCY_UNAVAILABLE", "登录暂不可用，请稍后重试"), req);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception ex, HttpServletRequest req) {
        LOG.error("request failed requestId={} type={}", req.getAttribute("requestId"), ex.getClass().getSimpleName());
        return known(new BusinessException(500, "INTERNAL_ERROR", "操作未完成，请稍后重试"), req);
    }
}
