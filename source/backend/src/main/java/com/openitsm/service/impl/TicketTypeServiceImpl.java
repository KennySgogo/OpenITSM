package com.openitsm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.common.utils.Values;
import com.openitsm.dto.SaveTicketTypeRequest;
import com.openitsm.entity.TicketTypeEntity;
import com.openitsm.mapper.TicketTypeMapper;
import com.openitsm.mapper.TransactionLockMapper;
import com.openitsm.mapper.UserMapper;
import com.openitsm.service.*;
import com.openitsm.vo.ActionVO;
import com.openitsm.vo.TicketTypeVO;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketTypeServiceImpl implements TicketTypeService {
    private final TicketTypeMapper types;
    private final UserMapper users;
    private final TransactionLockMapper locks;
    private final IdempotencyService idem;
    private final SessionService sessions;
    public TicketTypeServiceImpl(TicketTypeMapper types, UserMapper users, TransactionLockMapper locks, IdempotencyService idem, SessionService sessions) {
        this.types = types; this.users = users; this.locks = locks; this.idem = idem; this.sessions = sessions;
    }
    @Transactional(readOnly = true)
    public List<TicketTypeVO> list(AuthContext context, String view) {
        if (view != null && !view.equals("MANAGE")) throw new BusinessException(400, "INVALID_QUERY", "视图无效");
        boolean manage = "MANAGE".equals(view);
        if (manage && "EMPLOYEE".equals(context.user().role)) throw forbidden();
        return types.selectList(new QueryWrapper<TicketTypeEntity>().eq("active", true).orderByAsc("name", "id")).stream()
            .map(type -> new TicketTypeVO(type.id, type.name, manage ? type.version : null,
                manage ? ("ADMIN".equals(context.user().role) || context.user().id.equals(type.creatorId)) : null)).toList();
    }
    private void actor(AuthContext context) {
        locks.lock("MEMBERS", "CONTROL");
        var actor = users.lock(context.user().id);
        if (actor == null || !"ACTIVE".equals(actor.status) || actor.authVersion != context.session().authVersion())
            throw new BusinessException(401, "SESSION_EXPIRED", "会话已失效，请重新登录");
        if ("EMPLOYEE".equals(actor.role)) throw forbidden();
    }
    @Transactional
    public com.openitsm.vo.CreatedTicketTypeVO create(AuthContext context, SaveTicketTypeRequest body, String key) {
        String name = requiredName(body);
        var requestKey = idem.key(key); var actorId = context.user().id;
        String operation = "POST /api/v1/ticket-types", hash = idem.hash(body, null);
        idem.reserve(actorId, operation, requestKey); actor(context);
        var previous = idem.replay(actorId, operation, requestKey, hash);
        if (previous != null) {
            return new com.openitsm.vo.CreatedTicketTypeVO(previous.resourceId(), name, previous.acceptedVersion(), true);
        }
        var type = new TicketTypeEntity(); type.id = UUID.randomUUID(); type.name = name; type.creatorId = actorId;
        types.insert(type); sessions.touch(context.session().hash());
        idem.save(actorId, operation, requestKey, hash, type.id, type.version, 201);
        return new com.openitsm.vo.CreatedTicketTypeVO(type.id, type.name, type.version, false);
    }
    @Transactional
    public ActionVO change(AuthContext context, UUID id, SaveTicketTypeRequest body, String version, String key) {
        long expected = Values.version(version);
        String name = body == null ? null : requiredName(body);
        String operation = (body == null ? "DELETE " : "PATCH ") + "/api/v1/ticket-types/" + id;
        var requestKey = idem.key(key); var actorId = context.user().id; String hash = idem.hash(body, version);
        idem.reserve(actorId, operation, requestKey); actor(context);
        var previous = idem.replay(actorId, operation, requestKey, hash);
        if (previous != null) return previous;
        var type = types.lock(id);
        if (type == null || !type.active) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "类型不存在或已移除");
        if (!"ADMIN".equals(context.user().role) && !actorId.equals(type.creatorId)) throw forbidden();
        if (type.version != expected) throw new BusinessException(409, "VERSION_CONFLICT", "类型已更新，请刷新后重新确认");
        if (body == null) type.active = false; else type.name = name;
        type.version++; types.updateById(type); sessions.touch(context.session().hash());
        return idem.save(actorId, operation, requestKey, hash, id, type.version, 200);
    }
    private String requiredName(SaveTicketTypeRequest body) {
        if (body == null || body.name() == null || body.name().isBlank()) throw BusinessException.field("name", "请填写类型名称");
        return body.name().strip();
    }
    private BusinessException forbidden() { return new BusinessException(403, "FORBIDDEN", "无操作权限"); }
}
