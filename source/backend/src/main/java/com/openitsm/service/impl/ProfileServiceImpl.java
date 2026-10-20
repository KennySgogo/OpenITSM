package com.openitsm.service.impl;

import com.openitsm.common.exception.BusinessException;
import com.openitsm.common.utils.Values;
import com.openitsm.dto.UpdateProfileRequest;
import com.openitsm.entity.UserEntity;
import com.openitsm.mapper.UserMapper;
import com.openitsm.service.AuthContext;
import com.openitsm.service.IdempotencyService;
import com.openitsm.service.ProfileService;
import com.openitsm.service.SessionService;
import com.openitsm.vo.ActionVO;
import com.openitsm.vo.ProfileVO;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileServiceImpl implements ProfileService {
    private final UserMapper users;
    private final IdempotencyService idem;
    private final SessionService sessions;
    private final Clock clock;

    public ProfileServiceImpl(UserMapper users, IdempotencyService idem, SessionService sessions, Clock clock) {
        this.users = users; this.idem = idem; this.sessions = sessions; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProfileVO detail(AuthContext context) {
        UserEntity user = users.selectById(context.user().id);
        requireSession(context, user);
        return new ProfileVO(user.id, user.name, user.skillsDescription == null ? "" : user.skillsDescription, user.version);
    }

    @Transactional
    public ActionVO update(AuthContext context, UpdateProfileRequest body, String version, String key) {
        long expected = Values.version(version);
        if (body.name() == null && body.skillsDescription() == null) throw BusinessException.field("body", "至少提供姓名或技能描述");
        String name = body.name() == null ? null : Values.text(body.name(), "name", 1, 80);
        var requestKey = idem.key(key);
        var actor = context.user().id;
        String operation = "PATCH /api/v1/profile", hash = idem.hash(body, version);
        idem.reserve(actor, operation, requestKey);
        var user = users.lock(actor);
        requireSession(context, user);
        var previous = idem.replay(actor, operation, requestKey, hash);
        if (previous != null) return previous;
        if (user.version != expected) throw new BusinessException(409, "VERSION_CONFLICT", "资料已更新，请刷新后重新确认");
        if (name != null) user.name = name;
        if (body.skillsDescription() != null) user.skillsDescription = body.skillsDescription();
        user.version++; user.updatedAt = clock.instant();
        users.updateById(user);
        sessions.touch(context.session().hash());
        return idem.save(actor, operation, requestKey, hash, actor, user.version, 200);
    }

    private void requireSession(AuthContext context, UserEntity user) {
        if (user == null || !"ACTIVE".equals(user.status) || user.authVersion != context.session().authVersion())
            throw new BusinessException(401, "SESSION_EXPIRED", "会话已失效，请重新登录");
    }
}
