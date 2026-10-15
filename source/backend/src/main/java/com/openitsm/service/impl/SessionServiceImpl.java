package com.openitsm.service.impl;

import com.openitsm.entity.AuthSessionEntity;
import com.openitsm.service.AuthContext;
import com.openitsm.service.CsrfSession;
import com.openitsm.service.SessionResolution;
import com.openitsm.mapper.AuthMapper;
import com.openitsm.service.RateLimitService;
import com.openitsm.service.SessionService;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.common.utils.TokenCodec;
import com.openitsm.entity.UserEntity;
import com.openitsm.mapper.UserMapper;
import java.time.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionServiceImpl implements SessionService {
    private final AuthMapper mapper;
    private final UserMapper users;
    private final RateLimitService rates;
    private final Clock clock;
    public SessionServiceImpl(AuthMapper mapper, UserMapper users, RateLimitService rates, Clock clock) {
        this.mapper = mapper; this.users = users; this.rates = rates; this.clock = clock;
    }

    public SessionResolution resolve(String raw) {
        AuthSessionEntity session = raw == null || raw.length() != 43 ? null : mapper.findSession(TokenCodec.hash(raw));
        UserEntity user = session == null || session.userId() == null ? null : users.selectById(session.userId());
        boolean expired = session == null || session.revokedAt() != null || !session.expiresAt().isAfter(clock.instant())
            || !session.activityAt().plus(Duration.ofHours(2)).isAfter(clock.instant())
            || (session.userId() != null && (user == null || !"ACTIVE".equals(user.status) || user.authVersion != session.authVersion()));
        return new SessionResolution(new AuthContext(session, user, raw), expired);
    }

    public CsrfSession csrf(AuthContext ctx, String source) {
        rates.check("CSRF_SOURCE", source, 60, Duration.ofMinutes(1), 0);
        String raw = ctx.rawToken();
        boolean changed = ctx.session() == null;
        if (changed) { raw = TokenCodec.token(); create(raw, null, null); }
        return new CsrfSession(raw, TokenCodec.csrf(raw), changed);
    }

    public AuthSessionEntity create(String raw, UUID userId, Long authVersion) {
        Instant now = clock.instant();
        AuthSessionEntity session = new AuthSessionEntity(TokenCodec.hash(raw), userId, authVersion, now, now, null,
            now.plus(userId == null ? Duration.ofHours(2) : Duration.ofHours(24)), null, TokenCodec.hash(TokenCodec.csrf(raw)));
        mapper.insertSession(session);
        return session;
    }
    public void revoke(String hash) { mapper.revoke(hash, clock.instant()); }
    public void revokeAll(UUID userId) { mapper.revokeAll(userId, clock.instant()); }
    public void touch(String hash) { mapper.touch(hash, clock.instant()); }
    public void logout(AuthContext ctx) { if (ctx.session() != null) revoke(ctx.session().hash()); }

    @Transactional
    public void activity(String hash) {
        Instant now = clock.instant();
        if (!mapper.updateActivity(hash, now, now.minusSeconds(60))) throw BusinessException.rate(60);
    }
}
