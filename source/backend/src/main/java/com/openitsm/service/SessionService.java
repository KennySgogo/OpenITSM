package com.openitsm.service;

import com.openitsm.entity.AuthSessionEntity;
import java.util.UUID;

public interface SessionService {
    SessionResolution resolve(String raw);
    CsrfSession csrf(AuthContext context, String source);
    AuthSessionEntity create(String raw, UUID userId, Long authVersion);
    void revoke(String hash);
    void revokeAll(UUID userId);
    void touch(String hash);
    void logout(AuthContext context);
    void activity(String hash);
}
