package com.openitsm.service;

import com.openitsm.entity.AccountTokenEntity;
import java.time.Duration;
import java.util.UUID;

public interface AccountTokenService {
    AccountTokenEntity find(String raw, String purpose);
    void issue(UUID userId, String purpose, Duration ttl);
    void invalidate(UUID userId, String purpose);
}
