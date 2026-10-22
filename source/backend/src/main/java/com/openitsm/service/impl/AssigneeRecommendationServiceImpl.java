package com.openitsm.service.impl;

import com.openitsm.ai.client.PythonAiClient;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.dto.AssigneeRecommendationRequest;
import com.openitsm.entity.UserEntity;
import com.openitsm.mapper.*;
import com.openitsm.service.*;
import com.openitsm.vo.AssigneeRecommendationVO;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class AssigneeRecommendationServiceImpl implements AssigneeRecommendationService {
    private final UserMapper users;
    private final TicketMapper tickets;
    private final TicketTypeMapper types;
    private final SessionService sessions;
    private final RuntimeSettingsService runtime;
    private final PythonAiClient python;
    private final AiConnectionStatusMapper statuses;
    private final Clock clock;

    public AssigneeRecommendationServiceImpl(UserMapper users, TicketMapper tickets, TicketTypeMapper types,
        SessionService sessions, RuntimeSettingsService runtime, PythonAiClient python, AiConnectionStatusMapper statuses, Clock clock) {
        this.users = users; this.tickets = tickets; this.types = types; this.sessions = sessions;
        this.runtime = runtime; this.python = python; this.statuses = statuses; this.clock = clock;
    }

    private void actor(AuthContext context, boolean staff) {
        var resolved = sessions.resolve(context.rawToken());
        if (resolved.expired() || resolved.context().user() == null)
            throw new BusinessException(401, "SESSION_EXPIRED", "会话已失效，请重新登录");
        if (staff && !Set.of("ENGINEER", "ADMIN").contains(resolved.context().user().role))
            throw new BusinessException(403, "FORBIDDEN", "仅工程师和管理员可请求工单处理人推荐");
    }

    @Override
    public AssigneeRecommendationVO recommend(AuthContext context, UUID ticketId) {
        actor(context, true);
        var ticket = tickets.selectById(ticketId);
        if (ticket == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "工单不存在");
        return compute(context, new AssigneeRecommendationRequest(ticket.title, ticket.description, ticket.businessTypeId, ticket.impactScope), ticketId);
    }

    @Override
    public AssigneeRecommendationVO recommend(AuthContext context, AssigneeRecommendationRequest body) {
        actor(context, false);
        return compute(context, body, null);
    }

    private AssigneeRecommendationVO compute(AuthContext context, AssigneeRecommendationRequest body, UUID ticketId) {
        var type = types.selectById(body.businessTypeId());
        if (type == null || (ticketId == null && !type.active))
            throw BusinessException.field("businessTypeId", "业务类型已不可用，请重新选择");
        var settings = runtime.requireAiConnection();
        var candidates = users.activeEngineers();
        if (candidates.isEmpty()) return new AssigneeRecommendationVO(List.of());
        var materials = candidates.stream().map(user -> new PythonAiClient.AssigneeCandidate(user.id,
            user.skillsDescription == null ? "" : user.skillsDescription,
            tickets.countAssignedNotClosed(user.id), tickets.countTypeExperience(user.id, type.id))).toList();
        UUID attempt = UUID.randomUUID();
        statuses.begin("CALL", settings.version(), clock.instant(), attempt);
        try {
            var result = python.recommendAssignees(settings.model(), body.title(), body.description(), body.impactScope(), type.name, materials);
            runtime.recheckAiConnection(settings.version());
            actor(context, ticketId != null);
            var currentType = types.selectById(type.id);
            if (currentType == null || (ticketId == null && !currentType.active))
                throw BusinessException.field("businessTypeId", "业务类型已不可用，请重新选择");
            if (ticketId != null) {
                var current = tickets.selectById(ticketId);
                if (current == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "工单不存在");
                if (!Objects.equals(current.businessTypeId, type.id))
                    throw new BusinessException(409, "INVALID_STATE", "业务类型已变化，请重新生成推荐");
            }
            var allowed = new HashSet<UUID>();
            candidates.forEach(user -> allowed.add(user.id));
            var items = new ArrayList<AssigneeRecommendationVO.Item>();
            if (result.items() == null || result.items().size() > 3) throw invalid();
            for (var item : result.items()) {
                if (item == null || !allowed.remove(item.assigneeId())) throw invalid();
                var user = users.selectById(item.assigneeId());
                if (user != null && "ACTIVE".equals(user.status) && Set.of("ADMIN", "ENGINEER").contains(user.role))
                    items.add(new AssigneeRecommendationVO.Item(user.id, user.name, item.reason()));
            }
            statuses.finish("CALL", attempt, "SUCCESS", clock.instant());
            return new AssigneeRecommendationVO(items);
        } catch (BusinessException ex) {
            statuses.finish("CALL", attempt, ex.code, clock.instant());
            throw ex;
        }
    }

    private BusinessException invalid() { return new BusinessException(503, "AI_INVALID_RESPONSE", "模型返回候选无效"); }
}
