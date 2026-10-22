package com.openitsm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.openitsm.common.exception.BusinessException;
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
    private final SessionService sessions;
    public TicketTypeServiceImpl(TicketTypeMapper types, UserMapper users, TransactionLockMapper locks, SessionService sessions) {
        this.types = types; this.users = users; this.locks = locks; this.sessions = sessions;
    }
    @Transactional(readOnly = true)
    public List<TicketTypeVO> list(AuthContext context, String view) {
        if (view != null && !view.equals("MANAGE")) throw new BusinessException(400, "INVALID_QUERY", "视图无效");
        boolean manage = "MANAGE".equals(view);
        if (manage && "EMPLOYEE".equals(context.user().role)) throw forbidden();
        return types.selectList(new QueryWrapper<TicketTypeEntity>().eq("active", true).orderByAsc("name", "id")).stream()
            .map(type -> new TicketTypeVO(type.id, type.name, type.description, manage ? type.version : null,
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
    public com.openitsm.vo.CreatedTicketTypeVO create(AuthContext context, SaveTicketTypeRequest body) {
        String name = requiredName(body);
        String description = description(body);
        UUID actorId = context.user().id;
        actor(context);
        var type = new TicketTypeEntity(); type.id = UUID.randomUUID(); type.name = name; type.creatorId = actorId;
        type.description = description;
        types.insert(type); sessions.touch(context.session().hash());
        return new com.openitsm.vo.CreatedTicketTypeVO(type.id, type.name);
    }
    @Transactional
    public ActionVO change(AuthContext context, UUID id, SaveTicketTypeRequest body) {
        String name = body == null ? null : requiredName(body);
        String description = description(body);
        UUID actorId = context.user().id;
        actor(context);
        var type = types.lock(id);
        if (type == null || !type.active) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "类型不存在或已移除");
        if (!"ADMIN".equals(context.user().role) && !actorId.equals(type.creatorId)) throw forbidden();
        if (body == null) type.active = false; else type.name = name;
        if (body != null && body.description() != null) type.description = description;
        type.version++; types.updateById(type); sessions.touch(context.session().hash());
        return new ActionVO(id);
    }
    private String requiredName(SaveTicketTypeRequest body) {
        if (body == null || body.name() == null || body.name().isBlank()) throw BusinessException.field("name", "请填写类型名称");
        return body.name().strip();
    }
    private BusinessException forbidden() { return new BusinessException(403, "FORBIDDEN", "无操作权限"); }
    private String description(SaveTicketTypeRequest body) {
        if (body == null || body.description() == null) return null;
        String value = body.description().strip();
        if (value.codePointCount(0, value.length()) > 500)
            throw BusinessException.field("description", "说明最多 500 字");
        return value.isEmpty() ? null : value;
    }
}
