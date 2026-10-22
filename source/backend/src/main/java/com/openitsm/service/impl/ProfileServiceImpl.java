package com.openitsm.service.impl;

import com.openitsm.common.exception.BusinessException;
import com.openitsm.common.utils.Values;
import com.openitsm.dto.UpdateProfileRequest;
import com.openitsm.entity.UserEntity;
import com.openitsm.mapper.UserMapper;
import com.openitsm.service.AuthContext;
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
    private final SessionService sessions;
    private final Clock clock;

    public ProfileServiceImpl(UserMapper users, SessionService sessions, Clock clock) {
        this.users = users; this.sessions = sessions; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProfileVO detail(AuthContext context) {
        UserEntity user = users.selectById(context.user().id);
        requireSession(context, user);
        return new ProfileVO(user.id, user.name, user.skillsDescription == null ? "" : user.skillsDescription, user.version);
    }

    @Transactional
    public ActionVO update(AuthContext context, UpdateProfileRequest body) {
        if (body.name() == null && body.skillsDescription() == null) throw BusinessException.field("body", "至少提供姓名或技能描述");
        String name = body.name() == null ? null : Values.text(body.name(), "name", 1, 80);
        var actor = context.user().id;
        var user = users.lock(actor);
        requireSession(context, user);
        if (name != null) user.name = name;
        if (body.skillsDescription() != null) user.skillsDescription = body.skillsDescription();
        user.version++; user.updatedAt = clock.instant();
        users.updateById(user);
        sessions.touch(context.session().hash());
        return new ActionVO(actor);
    }

    private void requireSession(AuthContext context, UserEntity user) {
        if (user == null || !"ACTIVE".equals(user.status) || user.authVersion != context.session().authVersion())
            throw new BusinessException(401, "SESSION_EXPIRED", "会话已失效，请重新登录");
    }
}
