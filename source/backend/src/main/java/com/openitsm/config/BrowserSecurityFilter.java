package com.openitsm.config;

import com.openitsm.service.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openitsm.entity.AuthSessionEntity;
import com.openitsm.service.AuthContext;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.common.utils.TokenCodec;
import com.openitsm.common.exception.GlobalExceptionHandler;
import com.openitsm.common.utils.BoundedJsonRequest;
import com.openitsm.entity.UserEntity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.InetAddress;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class BrowserSecurityFilter extends OncePerRequestFilter {
    private final SessionService sessions;
    private final ObjectMapper json;
    private final String cookieName;
    private final String origin;
    private final boolean secure;

    public BrowserSecurityFilter(SessionService sessions, ObjectMapper json, String cookieName, String origin, boolean secure) {
        this.sessions = sessions; this.json = json;
        this.cookieName = cookieName; this.origin = origin; this.secure = secure;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String id = req.getHeader("X-Request-ID");
        try { id = UUID.fromString(id).toString(); } catch (Exception ex) { id = UUID.randomUUID().toString(); }
        req.setAttribute("requestId", id);
        res.setHeader("X-Request-ID", id);
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("X-Content-Type-Options", "nosniff");
        MDC.put("requestId", id);
        try {
            String path = req.getRequestURI();
            if (path.startsWith("/health/")) {
                if (!InetAddress.getByName(req.getRemoteAddr()).isLoopbackAddress()) throw new BusinessException(403, "FORBIDDEN", "无访问权限");
                chain.doFilter(req, res); return;
            }
            if (!path.startsWith("/api/")) { chain.doFilter(req, res); return; }
            if (!secure && !InetAddress.getByName(req.getRemoteAddr()).isLoopbackAddress()) throw new BusinessException(403, "FORBIDDEN", "开发会话只允许本机访问");
            boolean attachmentUpload = req.getMethod().equals("POST")
                && path.matches("/api/v1/tickets/[0-9a-fA-F-]{36}/attachments")
                && req.getContentType() != null && req.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("multipart/form-data;");
            long limit = attachmentUpload ? AttachmentConfig.MAX_REQUEST_SIZE : 256 * 1024;
            if (req.getContentLengthLong() > limit) throw new BusinessException(413, "REQUEST_TOO_LARGE",
                attachmentUpload ? "附件上传请求过大" : "请求正文超过 256 KiB");
            String raw = cookie(req);
            var resolved = sessions.resolve(raw);
            AuthSessionEntity session = resolved.context().session();
            UserEntity user = resolved.context().user();
            boolean expired = resolved.expired();
            boolean write = !List.of("GET", "HEAD", "OPTIONS").contains(req.getMethod());
            boolean logout = path.equals("/api/v1/auth/logout");
            boolean anon = List.of("/api/v1/auth/csrf", "/api/v1/auth/login", "/api/v1/auth/password-reset-requests",
                "/api/v1/auth/password-resets", "/api/v1/auth/activations", "/api/v1/auth/logout").contains(path);
            if (!anon && (expired || user == null)) throw new BusinessException(401, "SESSION_EXPIRED", "会话已失效，请重新登录");
            if (write) {
                String csrf = req.getHeader("X-CSRF-Token");
                if (!origin.equals(req.getHeader("Origin")) || session == null || (!logout && expired)
                    || csrf == null || csrf.length() != 64 || !TokenCodec.sameHash(session.csrfHash(), TokenCodec.hash(csrf)))
                    throw new BusinessException(403, "CSRF_INVALID", "请求验证失败，请刷新后重试");
            }
            if (!expired && user != null) {
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.id, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.role))));
            }
            req.setAttribute("auth", new AuthContext(expired && !logout ? null : session, expired ? null : user, raw));
            if (write && !attachmentUpload) req = BoundedJsonRequest.read(req);
            chain.doFilter(req, res);
        } catch (BusinessException ex) {
            GlobalExceptionHandler.write(json, req, res, ex);
        } catch (DataAccessException ex) {
            logger.error("database unavailable requestId=" + id);
            GlobalExceptionHandler.write(json, req, res, new BusinessException(503, "DEPENDENCY_UNAVAILABLE", "登录暂不可用，请稍后重试"));
        } finally { MDC.remove("requestId"); }
    }

    private String cookie(HttpServletRequest req) {
        if (req.getCookies() != null) for (Cookie cookie : req.getCookies()) if (cookieName.equals(cookie.getName())) return cookie.getValue();
        return null;
    }
}
