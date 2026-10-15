package com.openitsm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;

@TableName("auth_session")
public record AuthSessionEntity(String hash, UUID userId, Long authVersion, Instant createdAt,
    Instant activityAt, Instant lastActivityPingAt, Instant expiresAt, Instant revokedAt, String csrfHash) {}
