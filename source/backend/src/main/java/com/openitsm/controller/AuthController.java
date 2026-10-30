package com.openitsm.controller;

import com.openitsm.dto.PasswordResetRequest;
import com.openitsm.dto.LoginRequest;
import com.openitsm.dto.SetPasswordRequest;
import com.openitsm.service.AuthContext;
import com.openitsm.config.RequestAuthentication;
import com.openitsm.service.AuthService;
import com.openitsm.service.SessionService;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.common.utils.Values;
import com.openitsm.common.response.ApiResponse;
import com.openitsm.vo.CsrfVO;
import com.openitsm.vo.MessageVO;
import jakarta.servlet.http.*;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final SessionService sessions;
    private final AuthService auth;
    private final String cookieName;
    private final boolean secure;

    public AuthController(SessionService sessions, AuthService auth, @Value("${openitsm.cookie-name}") String cookieName,
        @Value("${openitsm.cookie-secure}") boolean secure) {
        this.sessions = sessions; this.auth = auth; this.cookieName = cookieName; this.secure = secure;
    }

    private void cookie(HttpServletResponse res, String raw, boolean clear) {
        var value = ResponseCookie.from(cookieName, clear ? "" : raw).httpOnly(true).secure(secure).sameSite("Lax").path("/");
        if (clear) value.maxAge(0);
        res.addHeader("Set-Cookie", value.build().toString());
    }

    @GetMapping("/csrf")
    public ApiResponse<?> csrf(HttpServletRequest req, HttpServletResponse res) {
        var result = sessions.csrf(RequestAuthentication.from(req), req.getRemoteAddr());
        if (result.cookieChanged()) cookie(res, result.rawToken(), false);
        return ApiResponse.of(req, new CsrfVO(result.csrfToken()));
    }

    @PostMapping("/login")
    public ApiResponse<?> login(@RequestBody LoginRequest body, HttpServletRequest req, HttpServletResponse res) {
        String email = Values.email(body.email());
        if (body.password() == null || body.password().isEmpty()) throw BusinessException.field("password", "请输入密码");
        if (body.returnTo() != null && body.returnTo().length() > 2048) throw BusinessException.field("returnTo", "返回路径过长");
        AuthContext ctx = RequestAuthentication.from(req);
        var result = auth.authenticate(email, body.password(), ctx.session() == null ? null : ctx.session().hash(), req.getRemoteAddr());
        cookie(res, result.rawToken(), false);
        return ApiResponse.of(req, auth.view(result.user(), result.session(), result.rawToken(), body.returnTo()));
    }

    @GetMapping("/me")
    public ApiResponse<?> me(HttpServletRequest req, HttpServletResponse res) {
        AuthContext ctx = RequestAuthentication.from(req);
        return ApiResponse.of(req, auth.view(ctx.user(), ctx.session(), ctx.rawToken(), null));
    }

    @PostMapping("/activity")
    public ResponseEntity<?> activity(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req) {
        RequestAuthentication.emptyBody(body);
        sessions.activity(RequestAuthentication.from(req).session().hash());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest req, HttpServletResponse res) {
        RequestAuthentication.emptyBody(body);
        AuthContext ctx = RequestAuthentication.from(req);
        sessions.logout(ctx);
        cookie(res, null, true);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password-changes")
    public ResponseEntity<?> changePassword(@RequestBody com.openitsm.dto.ChangePasswordRequest body, HttpServletRequest req, HttpServletResponse res) {
        auth.changePassword(RequestAuthentication.from(req), body, UUID.fromString((String) req.getAttribute("requestId")), req.getRemoteAddr());
        cookie(res, null, true);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/password-reset-requests")
    public ResponseEntity<?> requestReset(@RequestBody PasswordResetRequest body, HttpServletRequest req) {
        String email = Values.email(body.email());
        auth.requestResetFromSource(email, req.getRemoteAddr());
        return ResponseEntity.accepted().body(ApiResponse.of(req, new MessageVO("如果该邮箱对应可用账号，你将收到重置邮件")));
    }

    @PostMapping("/password-resets")
    public ApiResponse<?> reset(@RequestBody SetPasswordRequest body, HttpServletRequest req) { return consume(body, req, "RESET"); }

    @PostMapping("/activations")
    public ApiResponse<?> activate(@RequestBody SetPasswordRequest body, HttpServletRequest req) { return consume(body, req, "INVITE"); }

    private ApiResponse<?> consume(SetPasswordRequest body, HttpServletRequest req, String purpose) {
        Values.password(body.password(), body.confirmPassword());
        auth.consumeFromSource(body.token(), body.password(), purpose, UUID.fromString((String) req.getAttribute("requestId")), req.getRemoteAddr());
        return ApiResponse.of(req, new MessageVO(purpose.equals("INVITE") ? "账号已激活，请登录" : "密码已更新，请重新登录"));
    }

}
