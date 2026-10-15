package com.openitsm.config;

import com.openitsm.service.AuthContext;
import com.openitsm.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;

public final class RequestAuthentication {
    private RequestAuthentication() {}
    public static AuthContext from(HttpServletRequest request) { return (AuthContext) request.getAttribute("auth"); }
    public static void emptyBody(Map<String, Object> body) {
        if (body != null) throw new BusinessException(400, "VALIDATION_FAILED", "此接口不接受正文");
    }
}
