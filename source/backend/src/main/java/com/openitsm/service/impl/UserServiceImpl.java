package com.openitsm.service.impl;

import com.openitsm.service.AuditService;
import com.openitsm.service.AuthContext;
import com.openitsm.service.AccountTokenService;
import com.openitsm.service.RateLimitService;
import com.openitsm.service.SessionService;
import com.openitsm.service.IdempotencyService;
import com.openitsm.service.UserService;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.mapper.TransactionLockMapper;
import com.openitsm.common.utils.Values;
import com.openitsm.vo.ActionVO;
import com.openitsm.mapper.TicketMapper;
import com.openitsm.dto.InviteUserRequest;
import com.openitsm.dto.UpdateUserRequest;
import com.openitsm.entity.UserEntity;
import com.openitsm.mapper.UserMapper;
import com.openitsm.vo.AssigneeVO;
import com.openitsm.vo.AssigneeListVO;
import com.openitsm.vo.UserPageVO;
import com.openitsm.vo.UserVO;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserServiceImpl implements UserService {
    private final UserMapper users;
    private final SessionService sessions;
    private final AccountTokenService tokens;
    private final RateLimitService rates;
    private final TransactionLockMapper locks;
    private final IdempotencyService idem;
    private final TicketMapper tickets;
    private final AuditService audit;
    private final Clock clock;

    public UserServiceImpl(UserMapper users, SessionService sessions, AccountTokenService tokens, RateLimitService rates, TransactionLockMapper locks, IdempotencyService idem, TicketMapper tickets, AuditService audit, Clock clock) {
        this.users = users; this.sessions = sessions; this.tokens = tokens; this.rates = rates; this.locks = locks; this.idem = idem; this.tickets = tickets;
        this.audit = audit; this.clock = clock;
    }

    public String role(String value) {
        if (!Set.of("EMPLOYEE", "ENGINEER", "ADMIN").contains(value == null ? "" : value))
            throw BusinessException.field("role", "请选择有效角色");
        return value;
    }

    public UserPageVO list(int page, String q, String role, String status) {
        if (page < 1) throw new BusinessException(400, "INVALID_QUERY", "页码须从 1 开始");
        if (q != null) q = Values.text(q, "q", 1, 100);
        if (role != null) role = role(role);
        if (status != null && !Set.of("INVITED", "ACTIVE", "DISABLED").contains(status)) throw new BusinessException(400, "INVALID_QUERY", "状态无效");
        var result = users.findPage(page, q, role, status);
        return new UserPageVO(result.getRecords().stream().map(UserVO::from).toList(), page, 20, result.getTotal());
    }

    public AssigneeListVO assignees() {
        var items = users.activeEngineers()
            .stream().map(u -> new AssigneeVO(u.id, u.name, tickets.count(u.id, false), tickets.count(u.id, true))).toList();
        return new AssigneeListVO(items, clock.instant());
    }

    private UserEntity actor(AuthContext ctx) {
        // All member mutations and bootstrap share this lock: prevents last-admin write skew.
        locks.lock("MEMBERS", "CONTROL");
        UserEntity actor = users.lock(ctx.user().id);
        if (actor == null || !"ACTIVE".equals(actor.status) || !"ADMIN".equals(actor.role)
            || actor.authVersion != ctx.session().authVersion()) throw new BusinessException(403, "FORBIDDEN", "无操作权限");
        return actor;
    }

    @Transactional
    public ActionVO invite(AuthContext ctx, InviteUserRequest body, String keyHeader, UUID requestId) {
        String name = Values.text(body.name(), "name", 1, 80);
        String email = Values.email(body.email());
        String role = role(body.role());
        UUID key = idem.key(keyHeader);
        String operation = "POST /api/v1/users";
        String hash = idem.hash(body, null);
        idem.reserve(ctx.user().id, operation, key);
        UserEntity actor = actor(ctx);
        var previous = idem.replay(actor.id, operation, key, hash);
        if (previous != null) return previous;
        if (users.lockEmail(email) != null) throw new BusinessException(409, "EMAIL_EXISTS", "该邮箱已存在");
        rates.check("INVITE_EMAIL", email, 5, Duration.ofHours(1), 60);
        UserEntity user = new UserEntity();
        user.id = UUID.randomUUID(); user.name = name; user.emailNormalized = email; user.role = role; user.status = "INVITED";
        user.updatedAt = clock.instant();
        users.insert(user);
        invitation(user);
        audit.append(actor.id, "MEMBER_INVITED", user.id, Map.of("role", role, "status", "INVITED"), null, requestId);
        sessions.touch(ctx.session().hash());
        return idem.save(actor.id, operation, key, hash, user.id, user.version, 201);
    }

    @Transactional
    public ActionVO change(AuthContext ctx, UUID id, String action, UpdateUserRequest patch, String reason,
        String versionHeader, String keyHeader, UUID requestId) {
        long expectedVersion = Values.version(versionHeader);
        UUID key = idem.key(keyHeader);
        String operation = (action.equals("PATCH") ? "PATCH " : "POST ") + "/api/v1/users/" + id + (action.equals("PATCH") ? "" : "/" + action);
        String hash = idem.hash(patch == null ? reason : patch, versionHeader);
        idem.reserve(ctx.user().id, operation, key);
        UserEntity actor = actor(ctx);
        UserEntity user = users.lock(id);
        if (user == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "资源不存在或不可访问");
        var previous = idem.replay(actor.id, operation, key, hash);
        if (previous != null) return previous;
        if (user.version != expectedVersion) throw new BusinessException(409, "VERSION_CONFLICT", "成员已更新，请刷新后重试");
        var changes = new LinkedHashMap<String, Object>();
        switch (action) {
            case "PATCH" -> {
                if (patch == null || (patch.name() == null && patch.role() == null)) throw BusinessException.field("body", "至少提供姓名或角色");
                if (patch.name() != null) { String name = Values.text(patch.name(), "name", 1, 80); changes.put("name", List.of(user.name, name)); user.name = name; }
                if (patch.role() != null) {
                    String newRole = role(patch.role());
                    if (!newRole.equals(user.role)) {
                        lastAdmin(user);
                        if (Set.of("ENGINEER", "ADMIN").contains(user.role) && newRole.equals("EMPLOYEE")) handoff(user);
                        changes.put("role", List.of(user.role, newRole));
                        user.role = newRole; user.authVersion++; sessions.revokeAll(id);
                    }
                }
            }
            case "disable" -> {
                reason = Values.text(reason, "reason", 5, 500);
                if (user.status.equals("DISABLED")) throw new BusinessException(409, "INVALID_STATE", "成员已停用");
                lastAdmin(user); handoff(user);
                changes.put("status", List.of(user.status, "DISABLED"));
                user.status = "DISABLED"; user.authVersion++; sessions.revokeAll(id);
                tokens.invalidate(id, "INVITE"); tokens.invalidate(id, "RESET");
            }
            case "unlock" -> {
                user.failedCount = 0; user.lockedUntil = null; changes.put("lock", "已解除");
            }
            case "invitation-resend" -> {
                if (!user.status.equals("INVITED")) throw new BusinessException(409, "INVALID_STATE", "成员不处于待激活状态");
                rates.check("INVITE_EMAIL", user.emailNormalized, 5, Duration.ofHours(1), 60);
                invitation(user); changes.put("invitation", "已更新");
            }
            default -> throw new IllegalArgumentException("unknown member action");
        }
        user.version++; user.updatedAt = clock.instant();
        users.updateById(user);
        audit.append(actor.id, "MEMBER_" + action.toUpperCase(Locale.ROOT).replace('-', '_'), id, changes, reason, requestId);
        sessions.touch(ctx.session().hash());
        return idem.save(actor.id, operation, key, hash, id, user.version, action.equals("invitation-resend") ? 202 : 200);
    }

    private void lastAdmin(UserEntity user) {
        if (user.role.equals("ADMIN") && user.status.equals("ACTIVE") && users.activeAdmins() <= 1)
            throw new BusinessException(409, "LAST_ADMIN", "最后一名管理员不能停用或降级");
    }

    private void handoff(UserEntity user) {
        long count = tickets.assignedNotClosed(user.id);
        if (count > 0) throw new BusinessException(409, "MEMBER_HAS_OPEN_TICKETS", "成员仍有 " + count + " 张未交接工单，请逐单转移后重试");
    }

    private void invitation(UserEntity user) {
        tokens.issue(user.id, "INVITE", Duration.ofHours(48));
    }
}
