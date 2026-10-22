package com.openitsm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.openitsm.ai.client.PythonAiClient;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.entity.TicketEntity;
import com.openitsm.entity.TicketTypeEntity;
import com.openitsm.entity.UserEntity;
import com.openitsm.dto.CreateTicketClassificationSuggestionRequest;
import com.openitsm.mapper.AiConnectionStatusMapper;
import com.openitsm.mapper.TicketMapper;
import com.openitsm.mapper.TicketTypeMapper;
import com.openitsm.mapper.UserMapper;
import com.openitsm.service.AuthContext;
import com.openitsm.service.ClassificationSuggestionService;
import com.openitsm.service.RuntimeSettingsService;
import com.openitsm.service.SessionService;
import com.openitsm.vo.ClassificationSuggestionVO;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ClassificationSuggestionServiceImpl implements ClassificationSuggestionService {
    private final TicketMapper tickets;
    private final TicketTypeMapper types;
    private final UserMapper users;
    private final SessionService sessions;
    private final RuntimeSettingsService runtime;
    private final PythonAiClient python;
    private final AiConnectionStatusMapper statuses;
    private final Clock clock;

    public ClassificationSuggestionServiceImpl(TicketMapper tickets, TicketTypeMapper types, UserMapper users,
        SessionService sessions, RuntimeSettingsService runtime, PythonAiClient python,
        AiConnectionStatusMapper statuses, Clock clock) {
        this.tickets = tickets; this.types = types; this.users = users; this.sessions = sessions;
        this.runtime = runtime; this.python = python; this.statuses = statuses; this.clock = clock;
    }

    @Override
    public ClassificationSuggestionVO suggest(AuthContext context, UUID ticketId) {
        var ticket = eligibleTicket(context, ticketId);
        return compute(context, ticketId, ticket.title, ticket.description, ticket.impactScope);
    }

    @Override
    public ClassificationSuggestionVO suggestBeforeCreate(AuthContext context, CreateTicketClassificationSuggestionRequest body) {
        activeUser(context);
        return compute(context, null, body.title(), body.description(), body.impactScope());
    }

    private ClassificationSuggestionVO compute(AuthContext context, UUID ticketId, String title, String description, String impactScope) {
        var settings = runtime.requireAiConnection();
        List<TicketTypeEntity> candidates = types.selectList(new QueryWrapper<TicketTypeEntity>()
            .eq("active", true).orderByAsc("name", "id"));
        if (candidates.isEmpty()) {
            runtime.recheckAiConnection(settings.version());
            recheckAccess(context, ticketId);
            return new ClassificationSuggestionVO(null, null, null);
        }
        UUID attempt = UUID.randomUUID();
        statuses.begin("CALL", settings.version(), clock.instant(), attempt);
        try {
            var suggestion = python.suggestClassification(settings.model(), title, description, impactScope,
                candidates.stream().map(type -> new PythonAiClient.BusinessTypeCandidate(type.id, type.name, type.description)).toList());
            runtime.recheckAiConnection(settings.version());
            recheckAccess(context, ticketId);
            if (suggestion.businessTypeId() != null) {
                var selected = types.selectById(suggestion.businessTypeId());
                if (selected == null || !selected.active)
                    throw new BusinessException(503, "AI_INVALID_RESPONSE", "建议类型已失效，请人工选择或重新生成");
            }
            statuses.finish("CALL", attempt, "SUCCESS", clock.instant());
            return suggestion;
        } catch (BusinessException ex) {
            statuses.finish("CALL", attempt, ex.code, clock.instant());
            throw ex;
        }
    }

    private void recheckAccess(AuthContext context, UUID ticketId) {
        if (ticketId == null) activeUser(context);
        else eligibleTicket(context, ticketId);
    }

    private UserEntity activeUser(AuthContext context) {
        var resolved = sessions.resolve(context.rawToken());
        if (resolved.expired() || resolved.context().user() == null)
            throw new BusinessException(401, "SESSION_EXPIRED", "会话已失效，请重新登录");
        var actor = users.selectById(context.user().id);
        if (actor == null || !"ACTIVE".equals(actor.status) || actor.authVersion != context.session().authVersion())
            throw new BusinessException(401, "SESSION_EXPIRED", "会话已失效，请重新登录");
        return actor;
    }

    private TicketEntity eligibleTicket(AuthContext context, UUID id) {
        var actor = activeUser(context);
        var ticket = tickets.selectById(id);
        if (ticket == null || ("EMPLOYEE".equals(actor.role) && !actor.id.equals(ticket.requesterId)))
            throw new BusinessException(404, "RESOURCE_NOT_FOUND", "工单不存在或不可访问");
        if (!Set.of("NEW", "IN_PROGRESS", "ON_HOLD").contains(ticket.status))
            throw new BusinessException(409, "INVALID_STATE", "当前工单状态不允许生成类型建议");
        boolean owner = Set.of("ENGINEER", "ADMIN").contains(actor.role) && actor.id.equals(ticket.assigneeId);
        if (!"ADMIN".equals(actor.role) && !owner && !("NEW".equals(ticket.status) && actor.id.equals(ticket.requesterId)))
            throw new BusinessException(403, "FORBIDDEN", "无操作权限");
        return ticket;
    }
}
