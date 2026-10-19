package com.openitsm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.common.utils.Values;
import com.openitsm.dto.CreateTicketReplyRequest;
import com.openitsm.entity.TicketEntity;
import com.openitsm.entity.TicketOperationEntity;
import com.openitsm.entity.TicketReplyEntity;
import com.openitsm.entity.UserEntity;
import com.openitsm.mapper.TicketMapper;
import com.openitsm.mapper.TicketOperationMapper;
import com.openitsm.mapper.TicketReplyMapper;
import com.openitsm.mapper.UserMapper;
import com.openitsm.service.AuditService;
import com.openitsm.service.AuthContext;
import com.openitsm.service.IdempotencyService;
import com.openitsm.service.SessionService;
import com.openitsm.service.TicketCommunicationService;
import com.openitsm.vo.ActionVO;
import com.openitsm.vo.TicketOperationPageVO;
import com.openitsm.vo.TicketOperationVO;
import com.openitsm.vo.TicketReplyPageVO;
import com.openitsm.vo.TicketReplyVO;
import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketCommunicationServiceImpl implements TicketCommunicationService {
    private final TicketMapper tickets;
    private final TicketReplyMapper replies;
    private final TicketOperationMapper operations;
    private final UserMapper users;
    private final IdempotencyService idem;
    private final AuditService audit;
    private final SessionService sessions;
    private final Clock clock;
    private final ObjectMapper json;

    public TicketCommunicationServiceImpl(TicketMapper tickets, TicketReplyMapper replies, TicketOperationMapper operations,
        UserMapper users, IdempotencyService idem, AuditService audit, SessionService sessions, Clock clock, ObjectMapper json) {
        this.tickets = tickets; this.replies = replies; this.operations = operations; this.users = users;
        this.idem = idem; this.audit = audit; this.sessions = sessions; this.clock = clock; this.json = json;
    }

    @Override
    @Transactional
    public ActionVO append(AuthContext context, UUID id, boolean internal, CreateTicketReplyRequest body,
        String version, String key, UUID requestId) {
        long expectedVersion = Values.version(version);
        UUID requestKey = idem.key(key);
        if (body == null || body.content() == null || body.content().isBlank())
            throw BusinessException.field("content", "请填写回复内容");
        String operation = "POST /api/v1/tickets/" + id + (internal ? "/internal-notes" : "/replies");
        String hash = idem.hash(body, version);
        UUID actorId = context.user().id;
        idem.reserve(actorId, operation, requestKey);
        // Same member -> ticket lock order as existing ticket writes; protects role changes and closure races.
        var actor = users.lock(actorId);
        if (actor == null || !"ACTIVE".equals(actor.status) || actor.authVersion != context.session().authVersion())
            throw new BusinessException(401, "SESSION_EXPIRED", "会话已失效，请重新登录");
        var ticket = tickets.lock(id);
        requireVisible(actor, ticket);
        if (internal && "EMPLOYEE".equals(actor.role))
            throw new BusinessException(403, "FORBIDDEN", "无操作权限");
        var previous = idem.replay(actorId, operation, requestKey, hash);
        if (previous != null) return previous;
        if (ticket.version != expectedVersion)
            throw new BusinessException(409, "VERSION_CONFLICT", "工单已更新，请刷新后重试");
        if ("CLOSED".equals(ticket.status))
            throw new BusinessException(409, "INVALID_STATE", "工单已关闭，不能追加回复，请新建工单");
        var reply = new TicketReplyEntity();
        reply.id = UUID.randomUUID(); reply.ticketId = id; reply.authorId = actorId;
        reply.content = body.content().strip(); reply.visibility = internal ? "INTERNAL" : "PUBLIC";
        reply.createdAt = clock.instant();
        replies.insert(reply);
        if (!internal && ticket.firstRespondedAt == null
            && Set.of("ENGINEER", "ADMIN").contains(actor.role)) {
            ticket.firstRespondedAt = reply.createdAt;
        }
        ticket.version++;
        tickets.updateById(ticket);
        audit.appendTicket(actorId, internal ? "TICKET_INTERNAL_NOTE" : "TICKET_REPLY", id,
            Map.of("replyId", reply.id), null, requestId);
        sessions.touch(context.session().hash());
        return idem.save(actorId, operation, requestKey, hash, id, ticket.version, 200);
    }

    @Override
    @Transactional(readOnly = true)
    public TicketReplyPageVO replies(AuthContext context, UUID id, String page) {
        requireVisible(context.user(), tickets.selectById(id));
        int pageNumber = pageNumber(page);
        var result = replies.findPage(id, "EMPLOYEE".equals(context.user().role), pageNumber);
        var names = names(result.getRecords().stream().map(reply -> reply.authorId).toList());
        var items = result.getRecords().stream().map(reply -> new TicketReplyVO(reply.id, reply.content, reply.visibility,
            reply.authorId, names.get(reply.authorId), reply.createdAt)).toList();
        return new TicketReplyPageVO(items, pageNumber, 20, result.getTotal());
    }

    @Override
    @Transactional(readOnly = true)
    public TicketOperationPageVO operations(AuthContext context, UUID id, String page) {
        requireVisible(context.user(), tickets.selectById(id));
        int pageNumber = pageNumber(page);
        boolean employee = "EMPLOYEE".equals(context.user().role);
        var result = operations.findPage(id, employee, pageNumber);
        var names = names(result.getRecords().stream().map(event -> event.actorId).filter(java.util.Objects::nonNull).toList());
        var items = result.getRecords().stream().map(event -> new TicketOperationVO(event.id, event.action, event.actorId,
            event.actorId == null ? null : names.get(event.actorId), event.createdAt, details(event, employee))).toList();
        return new TicketOperationPageVO(items, pageNumber, 20, result.getTotal());
    }

    private Map<String, Object> details(TicketOperationEntity event, boolean employee) {
        try {
            var source = json.readTree(event.beforeAfter);
            var result = new LinkedHashMap<String, Object>();
            if (Set.of("TICKET_REPLY", "TICKET_INTERNAL_NOTE").contains(event.action)) {
                if (source.has("replyId")) result.put("replyId", source.get("replyId").asText());
            } else if (Set.of("TICKET_ATTACHMENT", "TICKET_INTERNAL_ATTACHMENT").contains(event.action)) {
                for (String field : List.of("attachmentId", "fileName", "size")) {
                    var value = source.get(field);
                    if (value != null) result.put(field, value.isNumber() ? value.longValue() : value.asText());
                }
            } else {
                // Explicit public projection: never return raw audit payloads or internal reasons to employees.
                for (String side : List.of("before", "after")) {
                    var snapshot = new LinkedHashMap<String, Object>();
                    for (String field : List.of("status", "assigneeId", "priority", "summary", "closedAt", "cycleStartedAt")) {
                        var value = source.path(side).get(field);
                        if (value != null) snapshot.put(field, value.isNull() ? null : value.asText());
                    }
                    result.put(side, snapshot);
                }
                if (!employee && event.reason != null) result.put("reason", event.reason);
            }
            return result;
        } catch (JsonProcessingException ex) { throw new IllegalStateException("Invalid ticket audit payload", ex); }
    }

    private Map<UUID, String> names(Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        return users.selectList(new QueryWrapper<UserEntity>().in("id", ids)).stream()
            .collect(Collectors.toMap(user -> user.id, user -> user.name));
    }

    private void requireVisible(UserEntity actor, TicketEntity ticket) {
        if (ticket == null || ("EMPLOYEE".equals(actor.role) && !actor.id.equals(ticket.requesterId)))
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "工单不存在或不可访问");
    }

    private int pageNumber(String page) {
        try {
            int number = page == null ? 1 : Integer.parseInt(page);
            if (number >= 1) return number;
        } catch (NumberFormatException ignored) { }
        throw new BusinessException(400, "INVALID_QUERY", "查询参数无效");
    }
}
