package com.openitsm.service.impl;

import com.openitsm.common.exception.BusinessException;
import com.openitsm.dto.CreateTicketRequest;
import com.openitsm.dto.AssignTicketRequest;
import com.openitsm.dto.TicketReasonRequest;
import com.openitsm.dto.ResolveTicketRequest;
import com.openitsm.dto.ChangeTicketPriorityRequest;
import com.openitsm.common.utils.Values;
import com.openitsm.entity.TicketEntity;
import com.openitsm.mapper.TicketMapper;
import com.openitsm.mapper.UserMapper;
import com.openitsm.mapper.TransactionLockMapper;
import com.openitsm.service.AuditService;
import com.openitsm.service.AuthContext;
import com.openitsm.service.IdempotencyService;
import com.openitsm.service.SessionService;
import com.openitsm.service.TicketService;
import com.openitsm.vo.CreatedTicketVO;
import com.openitsm.vo.ActionVO;
import com.openitsm.vo.TicketPageVO;
import com.openitsm.vo.TicketVO;
import com.openitsm.entity.UserEntity;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.List;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketServiceImpl implements TicketService {
    private final TicketMapper tickets;
    private final UserMapper users;
    private final IdempotencyService idem;
    private final SessionService sessions;
    private final Clock clock;
    private final TransactionLockMapper locks;
    private final AuditService audit;

    public TicketServiceImpl(TicketMapper tickets, UserMapper users, IdempotencyService idem, SessionService sessions, Clock clock,
        TransactionLockMapper locks, AuditService audit) {
        this.tickets = tickets; this.users = users; this.idem = idem; this.sessions = sessions; this.clock = clock;
        this.locks = locks; this.audit = audit;
    }

    @Override
    @Transactional(readOnly = true)
    public TicketPageVO list(AuthContext context, String view, String page, String status, String businessType, String q) {
        view = view == null ? "MINE" : view;
        if (!Set.of("MINE", "ASSIGNED", "UNASSIGNED", "ALL").contains(view)) throw invalidQuery();
        if ("EMPLOYEE".equals(context.user().role) && !view.equals("MINE"))
            throw new BusinessException(403, "FORBIDDEN", "无访问权限");
        int pageNumber;
        try { pageNumber = page == null ? 1 : Integer.parseInt(page); }
        catch (NumberFormatException ex) { throw invalidQuery(); }
        if (pageNumber < 1) throw invalidQuery();
        if (status != null && !Set.of("NEW", "IN_PROGRESS", "ON_HOLD", "RESOLVED", "CLOSED").contains(status)) throw invalidQuery();
        if (businessType != null) { businessType = businessType.strip(); if (businessType.isEmpty()) throw invalidQuery(); }
        if (q != null) {
            q = q.strip();
            if (q.isEmpty() || q.codePointCount(0, q.length()) > 100) throw invalidQuery();
        }
        var result = tickets.findPage(context.user().id, view, pageNumber, status, businessType, q);
        return new TicketPageVO(views(result.getRecords()), pageNumber, 20, result.getTotal());
    }

    @Override
    @Transactional(readOnly = true)
    public TicketVO detail(AuthContext context, UUID id) {
        var ticket = tickets.selectById(id);
        if (ticket == null || ("EMPLOYEE".equals(context.user().role) && !context.user().id.equals(ticket.requesterId)))
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "工单不存在或不可访问");
        return views(List.of(ticket)).getFirst();
    }

    private List<TicketVO> views(List<TicketEntity> records) {
        if (records.isEmpty()) return List.of();
        var ids = new HashSet<UUID>();
        for (var ticket : records) {
            ids.add(ticket.requesterId);
            if (ticket.assigneeId != null) ids.add(ticket.assigneeId);
        }
        var names = users.selectList(new QueryWrapper<UserEntity>().in("id", ids)).stream()
            .collect(Collectors.toMap(user -> user.id, user -> user.name));
        return records.stream().map(ticket -> TicketVO.from(ticket, names.get(ticket.requesterId),
            ticket.assigneeId == null ? null : names.get(ticket.assigneeId))).toList();
    }

    private BusinessException invalidQuery() { return new BusinessException(400, "INVALID_QUERY", "查询参数无效"); }

    @Override
    @Transactional
    public CreatedTicketVO create(AuthContext context, CreateTicketRequest body, String keyHeader) {
        String title = requiredText(body.title(), "title");
        String description = requiredText(body.description(), "description");
        String businessType = requiredText(body.businessType(), "businessType");
        String impactScope = requiredText(body.impactScope(), "impactScope");
        UUID key = idem.key(keyHeader);
        String operation = "POST /api/v1/tickets";
        String hash = idem.hash(body, null);
        UUID actorId = context.user().id;
        idem.reserve(actorId, operation, key);
        var actor = users.lock(actorId);
        if (actor == null || !"ACTIVE".equals(actor.status) || actor.authVersion != context.session().authVersion())
            throw new BusinessException(401, "SESSION_EXPIRED", "会话已失效，请重新登录");
        var previous = idem.replay(actorId, operation, key, hash);
        if (previous != null) {
            var ticket = tickets.selectById(previous.resourceId());
            if (ticket == null) throw new IllegalStateException("Idempotent ticket is missing");
            return new CreatedTicketVO(ticket.id, ticket.number, previous.replayed());
        }
        TicketEntity ticket = new TicketEntity();
        ticket.id = UUID.randomUUID();
        ticket.number = tickets.nextNumber();
        ticket.title = title; ticket.description = description;
        ticket.businessType = businessType; ticket.impactScope = impactScope;
        ticket.requesterId = actorId; ticket.status = "NEW"; ticket.createdAt = clock.instant();
        ticket.priority = "P3"; ticket.cycleStartedAt = ticket.createdAt;
        tickets.insert(ticket);
        var accepted = idem.save(actorId, operation, key, hash, ticket.id, ticket.version, 201);
        sessions.touch(context.session().hash());
        return new CreatedTicketVO(ticket.id, ticket.number, accepted.replayed());
    }

    @Override
    @Transactional
    public ActionVO change(AuthContext context, UUID id, String action, Object body,
        String versionHeader, String keyHeader, UUID requestId) {
        long expectedVersion = Values.version(versionHeader);
        UUID key = idem.key(keyHeader);
        String operation = "POST /api/v1/tickets/" + id + "/" + action;
        String hash = idem.hash(body, versionHeader);
        UUID actorId = context.user().id;
        idem.reserve(actorId, operation, key);
        // Match M01's member-control -> member rows order before locking the ticket.
        // This keeps assignment and member disable/demotion mutually consistent.
        locks.lock("MEMBERS", "CONTROL");
        var actor = users.lock(actorId);
        if (actor == null || !"ACTIVE".equals(actor.status) || actor.authVersion != context.session().authVersion())
            throw new BusinessException(401, "SESSION_EXPIRED", "会话已失效，请重新登录");
        var target = body instanceof AssignTicketRequest assignment && assignment.assigneeId() != null
            ? users.lock(assignment.assigneeId()) : null;
        TicketEntity ticket = tickets.lock(id);
        if (ticket == null || ("EMPLOYEE".equals(actor.role) && !actorId.equals(ticket.requesterId)))
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "工单不存在或不可访问");
        var previous = idem.replay(actorId, operation, key, hash);
        if (previous != null) return previous;
        if (ticket.version != expectedVersion) throw new BusinessException(409, "VERSION_CONFLICT", "工单已更新，请刷新后重试");
        boolean admin = "ADMIN".equals(actor.role);
        boolean engineer = "ENGINEER".equals(actor.role);
        boolean owner = engineer && actorId.equals(ticket.assigneeId);
        boolean requester = actorId.equals(ticket.requesterId);
        Map<String, Object> before = snapshot(ticket);
        String reason = body instanceof TicketReasonRequest value ? value.reason()
            : body instanceof ChangeTicketPriorityRequest value ? value.reason() : null;
        if (reason != null) reason = requiredText(reason, "reason");
        switch (action) {
            case "claim" -> {
                requirePermission(engineer && (ticket.assigneeId == null || owner));
                requireState(ticket, "NEW");
                ticket.assigneeId = actorId; ticket.status = "IN_PROGRESS";
            }
            case "assignments", "transfers" -> {
                requirePermission(action.equals("assignments") ? admin : admin || owner);
                requireState(ticket, "NEW", "IN_PROGRESS", "ON_HOLD");
                if (target == null || !"ACTIVE".equals(target.status) || !"ENGINEER".equals(target.role))
                    throw BusinessException.field("assigneeId", "请选择有效的在职工程师");
                if (action.equals("transfers") && target.id.equals(ticket.assigneeId))
                    throw BusinessException.field("assigneeId", "请选择其他工程师");
                ticket.assigneeId = target.id;
            }
            case "suspensions" -> {
                requirePermission(owner); requireState(ticket, "IN_PROGRESS");
                reason = requiredText(reason, "reason"); ticket.status = "ON_HOLD";
            }
            case "resume" -> {
                requirePermission(owner); requireState(ticket, "ON_HOLD"); ticket.status = "IN_PROGRESS";
            }
            case "resolutions" -> {
                requirePermission(owner); requireState(ticket, "IN_PROGRESS");
                ticket.resolutionSummary = requiredText(((ResolveTicketRequest) body).summary(), "summary");
                ticket.status = "RESOLVED";
            }
            case "close" -> {
                requirePermission(requester || engineer || admin); requireState(ticket, "RESOLVED");
                if (!requester) reason = requiredText(reason, "reason");
                ticket.status = "CLOSED"; ticket.closedAt = clock.instant();
            }
            case "reopen" -> {
                requirePermission(requester || owner || admin); requireState(ticket, "RESOLVED", "CLOSED");
                if ("CLOSED".equals(ticket.status) && (ticket.closedAt == null
                    || clock.instant().isAfter(ticket.closedAt.plus(Duration.ofDays(7)))))
                    throw new BusinessException(409, "INVALID_STATE", "工单关闭已超过 7 天，请新建工单");
                reason = requiredText(reason, "reason");
                if (ticket.assigneeId != null) {
                    // Member role/status mutations share the member-control lock held above.
                    var assignee = users.selectById(ticket.assigneeId);
                    if (assignee == null || !"ACTIVE".equals(assignee.status) || !"ENGINEER".equals(assignee.role))
                        ticket.assigneeId = null;
                }
                ticket.status = "IN_PROGRESS"; ticket.closedAt = null; ticket.cycleStartedAt = clock.instant();
            }
            case "priority-changes" -> {
                requirePermission(admin);
                String priority = ((ChangeTicketPriorityRequest) body).priority();
                if (priority == null || !Set.of("P1", "P2", "P3", "P4").contains(priority))
                    throw BusinessException.field("priority", "请选择 P1～P4 优先级");
                reason = requiredText(reason, "reason"); ticket.priority = priority;
            }
            default -> throw new IllegalArgumentException("Unknown ticket action");
        }
        ticket.version++;
        tickets.updateById(ticket);
        audit.appendTicket(actorId, "TICKET_" + action.toUpperCase(Locale.ROOT).replace('-', '_'), id,
            Map.of("before", before, "after", snapshot(ticket)), reason, requestId);
        sessions.touch(context.session().hash());
        return idem.save(actorId, operation, key, hash, id, ticket.version, 200);
    }

    private void requirePermission(boolean permitted) {
        if (!permitted) throw new BusinessException(403, "FORBIDDEN", "无操作权限");
    }

    private void requireState(TicketEntity ticket, String... allowed) {
        if (!Set.of(allowed).contains(ticket.status)) throw new BusinessException(409, "INVALID_STATE", "当前工单状态不允许此操作");
    }

    private Map<String, Object> snapshot(TicketEntity ticket) {
        var value = new LinkedHashMap<String, Object>();
        value.put("status", ticket.status); value.put("assigneeId", ticket.assigneeId);
        value.put("priority", ticket.priority); value.put("summary", ticket.resolutionSummary);
        value.put("closedAt", ticket.closedAt); value.put("cycleStartedAt", ticket.cycleStartedAt);
        return value;
    }

    private String requiredText(String input, String field) {
        if (input == null || input.isBlank()) throw BusinessException.field(field, "请填写 " + field);
        return input.strip();
    }
}
