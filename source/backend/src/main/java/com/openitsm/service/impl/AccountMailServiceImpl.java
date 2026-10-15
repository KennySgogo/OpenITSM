package com.openitsm.service.impl;

import com.openitsm.mapper.NotificationMapper;
import com.openitsm.service.AccountMailService;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AccountMailServiceImpl implements AccountMailService {
    private final NotificationMapper mapper;
    private final AccountMailCipher cipher;
    private final Clock clock;
    public AccountMailServiceImpl(NotificationMapper mapper, AccountMailCipher cipher, Clock clock) {
        this.mapper = mapper; this.cipher = cipher; this.clock = clock;
    }
    public void requireReady() { cipher.requireReady(); }
    public void enqueue(UUID userId, String purpose, String rawToken, long generation) {
        UUID id = UUID.randomUUID();
        String sealed = cipher.seal(rawToken, userId + ":" + purpose + ":" + generation);
        mapper.insertAccountTask(id, userId, purpose, clock.instant(), sealed, generation);
    }
    public void cancel(UUID userId, String purpose) { mapper.cancelAccountTasks(userId, purpose); }
}
