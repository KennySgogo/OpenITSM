package com.openitsm.service.impl;

import com.openitsm.entity.AccountTokenEntity;
import com.openitsm.mapper.AuthMapper;
import com.openitsm.service.AccountMailService;
import com.openitsm.service.AccountTokenService;
import com.openitsm.common.utils.TokenCodec;
import java.time.*;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Called inside the member/password transaction, after the owning member row has been locked. */
@Service
public class AccountTokenServiceImpl implements AccountTokenService {
    private final AuthMapper mapper;
    private final AccountMailService mail;
    private final Clock clock;
    public AccountTokenServiceImpl(AuthMapper mapper, AccountMailService mail, Clock clock) {
        this.mapper = mapper; this.mail = mail; this.clock = clock;
    }
    public AccountTokenEntity find(String raw, String purpose) {
        return raw == null || raw.length() != 43 ? null : mapper.findToken(TokenCodec.hash(raw), purpose);
    }
    public void issue(UUID userId, String purpose, Duration ttl) {
        String raw = TokenCodec.token();
        long generation = mapper.nextGeneration(userId, purpose);
        invalidate(userId, purpose);
        mapper.insertToken(new AccountTokenEntity(TokenCodec.hash(raw), userId, purpose, clock.instant().plus(ttl), null, generation));
        mail.enqueue(userId, purpose, raw, generation);
    }
    public void invalidate(UUID userId, String purpose) {
        mapper.invalidateTokens(userId, purpose, clock.instant());
        mail.cancel(userId, purpose);
    }
}
