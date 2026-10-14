package com.openitsm.service.impl;

import com.openitsm.mapper.RateLimitMapper;
import com.openitsm.service.RateLimitService;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.mapper.TransactionLockMapper;
import com.openitsm.common.utils.TokenCodec;
import java.time.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RateLimitServiceImpl implements RateLimitService {
    private final RateLimitMapper mapper;
    private final TransactionLockMapper locks;
    private final Clock clock;
    public RateLimitServiceImpl(RateLimitMapper mapper, TransactionLockMapper locks, Clock clock) {
        this.mapper = mapper; this.locks = locks; this.clock = clock;
    }
    @Transactional
    public void check(String scope, String key, int limit, Duration window, int cooldownSeconds) {
        String hash = TokenCodec.hash(key);
        locks.lock(scope, hash);
        Instant now = clock.instant();
        int count = mapper.countSince(scope, hash, now.minus(window));
        Instant last = mapper.lastRequest(scope, hash);
        if (count >= limit) throw BusinessException.rate((int) window.toSeconds());
        if (last != null && now.isBefore(last.plusSeconds(cooldownSeconds)))
            throw BusinessException.rate((int) Duration.between(now, last.plusSeconds(cooldownSeconds)).toSeconds());
        mapper.increment(scope, hash, now, now.plus(window));
    }
}
