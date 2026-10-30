package com.openitsm.service.impl;

import com.openitsm.service.AuditService;
import com.openitsm.entity.AccountTokenEntity;
import com.openitsm.entity.AuthSessionEntity;
import com.openitsm.service.LoginSession;
import com.openitsm.vo.SessionVO;
import com.openitsm.service.AccountMailService;
import com.openitsm.service.AccountTokenService;
import com.openitsm.service.AuthService;
import com.openitsm.service.RateLimitService;
import com.openitsm.service.SessionService;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.common.utils.TokenCodec;
import com.openitsm.mapper.TicketMapper;
import com.openitsm.mapper.KnowledgeArticleMapper;
import com.openitsm.entity.UserEntity;
import com.openitsm.mapper.UserMapper;
import com.openitsm.vo.UserVO;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthServiceImpl implements AuthService {
    private final UserMapper users;
    private final SessionService sessions;
    private final AccountTokenService tokens;
    private final RateLimitService rates;
    private final org.springframework.transaction.support.TransactionTemplate transactions;
    private final AccountMailService mail;
    private final AuditService audit;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final String dummyHash;
    private final TicketMapper tickets;
    private final KnowledgeArticleMapper knowledge;

    public AuthServiceImpl(UserMapper users, SessionService sessions, AccountTokenService tokens, RateLimitService rates, org.springframework.transaction.PlatformTransactionManager transactionManager, AccountMailService mail, AuditService audit, PasswordEncoder encoder, Clock clock, TicketMapper tickets, KnowledgeArticleMapper knowledge) {
        this.users = users; this.sessions = sessions; this.tokens = tokens; this.rates = rates;
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(transactionManager); this.mail = mail; this.audit = audit; this.encoder = encoder; this.clock = clock;
        this.tickets = tickets;
        this.knowledge = knowledge;
        this.dummyHash = encoder.encode(TokenCodec.token());
    }

    // Rate limits commit separately; credential failure counts commit before the uniform error is raised.
    // Explicit templates avoid self-invocation losing the transaction around the existing core operations.
    public LoginSession authenticate(String email, String password, String oldHash, String source) {
        rates.check("LOGIN_SOURCE", source, 60, Duration.ofMinutes(1), 0);
        LoginSession result = transactions.execute(status -> login(email, password, oldHash));
        if (result == null) throw invalidLogin();
        return result;
    }

    public void requestResetFromSource(String email, String source) {
        rates.check("RESET_SOURCE", source, 60, Duration.ofMinutes(1), 0);
        rates.check("RESET_EMAIL", email, 5, Duration.ofHours(1), 60);
        transactions.executeWithoutResult(status -> requestReset(email));
    }

    public void consumeFromSource(String raw, String password, String purpose, UUID requestId, String source) {
        rates.check("TOKEN_SOURCE", source, 60, Duration.ofMinutes(1), 0);
        transactions.executeWithoutResult(status -> consume(raw, password, purpose, requestId));
    }

    private BusinessException invalidLogin() { return new BusinessException(401, "AUTH_INVALID", "邮箱或密码错误，或账号暂不可用"); }

    public void changePassword(com.openitsm.service.AuthContext context, com.openitsm.dto.ChangePasswordRequest body, UUID requestId, String source) {
        rates.check("PASSWORD_CHANGE_SOURCE", source, 60, Duration.ofMinutes(1), 0);
        rates.check("PASSWORD_CHANGE_MEMBER", context.user().id.toString(), 5, Duration.ofMinutes(15), 0);
        com.openitsm.common.utils.Values.password(body.password(), body.confirmPassword());
        if (body.oldPassword() == null || body.oldPassword().isEmpty() || body.oldPassword().codePointCount(0, body.oldPassword().length()) > 64)
            throw BusinessException.field("oldPassword", "请输入有效的旧密码");
        transactions.executeWithoutResult(status -> {
            var user = users.lock(context.user().id);
            if (user == null || !"ACTIVE".equals(user.status) || user.authVersion != context.session().authVersion())
                throw new BusinessException(401, "SESSION_EXPIRED", "会话已失效，请重新登录");
            if (!encoder.matches(body.oldPassword(), user.passwordHash)) throw BusinessException.field("oldPassword", "旧密码不正确");
            user.passwordHash = encoder.encode(body.password());
            user.authVersion++; user.version++; user.updatedAt = clock.instant();
            user.failedCount = 0; user.lockedUntil = null;
            users.updateById(user);
            tokens.invalidate(user.id, "RESET"); tokens.invalidate(user.id, "INVITE");
            sessions.revokeAll(user.id);
            audit.append(user.id, "PASSWORD_CHANGED", user.id, Map.of("password", "已更新"), null, requestId);
        });
    }

    @Transactional
    public LoginSession login(String email, String password, String oldHash) {
        UserEntity user = users.lockEmail(email);
        Instant now = clock.instant();
        if (user == null || !"ACTIVE".equals(user.status)) {
            encoder.matches(password, dummyHash);
            return null;
        }
        if (user.lockedUntil != null && user.lockedUntil.isAfter(now)) {
            encoder.matches(password, dummyHash);
            return null;
        }
        if (user.lockedUntil != null) { user.lockedUntil = null; user.failedCount = 0; }
        //校验密码
        if (!encoder.matches(password, user.passwordHash)) {
            user.failedCount++;
            if (user.failedCount >= 5) user.lockedUntil = now.plusSeconds(15 * 60);
            user.updatedAt = now;
            users.updateById(user);
            // A normal return commits the failed count; authenticate emits 401 after this transaction commits.
            return null;
        }
        user.failedCount = 0;
        user.lockedUntil = null;
        user.updatedAt = now;
        users.updateById(user);
        if (oldHash != null) sessions.revoke(oldHash);
        String raw = TokenCodec.token();
        return new LoginSession(raw, sessions.create(raw, user.id, user.authVersion), user);
    }

    public SessionVO view(UserEntity user, AuthSessionEntity session, String raw, String returnTo) {
        String fallback = switch (user.role) {
            case "ADMIN" -> "/dashboard";
            case "ENGINEER" -> "/tickets?view=ASSIGNED";
            default -> "/tickets?view=MINE";
        };
        String redirect = allowedPath(user, returnTo) ? returnTo : fallback;
        boolean rejected = returnTo != null && !returnTo.equals(redirect);
        return new SessionVO(UserVO.from(user), fallback, session.expiresAt(), TokenCodec.csrf(raw), redirect,
            rejected ? "原页面无访问权限，已返回默认页面" : "登录成功");
    }

    private boolean allowedPath(UserEntity user, String value) {
        if (value == null || value.length() > 2048) return false;
        if (value.equals("/profile")) return true;
        if (value.equals("/ticket-types")) return !"EMPLOYEE".equals(user.role);
        if (value.equals("/knowledge/new")) return user.role.equals("ENGINEER");
        if (value.startsWith("/knowledge?")) {
            try {
                String view = "PUBLISHED";
                for (String item : java.net.URI.create(value).getRawQuery().split("&")) {
                    if (item.startsWith("view=")) view = java.net.URLDecoder.decode(item.substring(5), java.nio.charset.StandardCharsets.UTF_8);
                }
                return view.equals("PUBLISHED") || (view.equals("MINE") && user.role.equals("ENGINEER"))
                    || (view.equals("ALL") && user.role.equals("ADMIN"));
            } catch (IllegalArgumentException ex) { return false; }
        }
        if (value.matches("/knowledge/[0-9a-fA-F-]{36}")) {
            try { return knowledge.visible(UUID.fromString(value.substring("/knowledge/".length())), user.id, user.role); }
            catch (IllegalArgumentException ex) { return false; }
        }
        if (value.equals("/knowledge") || value.equals("/tickets") || value.equals("/tickets/new") || value.equals("/tickets?view=MINE")) return true;
        if (value.matches("/tickets/[0-9a-fA-F-]{36}")) {
            try { return user.role.equals("EMPLOYEE")
                ? tickets.existsForRequester(UUID.fromString(value.substring("/tickets/".length())), user.id)
                : tickets.existsById(UUID.fromString(value.substring("/tickets/".length()))); }
            catch (IllegalArgumentException ex) { return false; }
        }
        if (!user.role.equals("EMPLOYEE") && value.matches("/tickets\\?view=(ASSIGNED|UNASSIGNED|ALL)")) return true;
        return user.role.equals("ADMIN") && (value.equals("/dashboard") || value.equals("/admin/users") || value.equals("/settings"));
    }

    @Transactional
    public void requestReset(String email) {
        // Check configuration for all addresses before looking up identity: same failure surface.
        UserEntity user = users.lockEmail(email);
        if (user == null || !"ACTIVE".equals(user.status)) return;
        tokens.issue(user.id, "RESET", Duration.ofMinutes(30));
    }

    @Transactional
    public void consume(String raw, String password, String purpose, UUID requestId) {
        AccountTokenEntity token = tokens.find(raw, purpose);
        if (token == null) throw invalidToken(purpose);
        UserEntity user = users.lock(token.userId());
        // Re-read under the user lock, shared with generation and password mutations.
        token = tokens.find(raw, purpose);
        if (token == null || token.usedAt() != null || !token.expiresAt().isAfter(clock.instant()) || user == null
            || !(purpose.equals("INVITE") ? "INVITED" : "ACTIVE").equals(user.status)) throw invalidToken(purpose);
        user.passwordHash = encoder.encode(password);
        user.status = "ACTIVE";
        user.failedCount = 0;
        user.lockedUntil = null;
        user.authVersion++;
        user.version++;
        user.updatedAt = clock.instant();
        users.updateById(user);
        tokens.invalidate(user.id, "INVITE");
        tokens.invalidate(user.id, "RESET");
        sessions.revokeAll(user.id);
        audit.append(user.id, purpose.equals("INVITE") ? "ACCOUNT_ACTIVATED" : "PASSWORD_RESET", user.id,
            Map.of("password", "已更新"), null, requestId);
    }

    private BusinessException invalidToken(String purpose) {
        return new BusinessException(409, "TOKEN_INVALID", purpose.equals("INVITE") ? "链接已失效，请联系管理员" : "链接已失效，请重新申请重置密码");
    }
}
