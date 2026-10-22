package com.openitsm.service.impl;

import com.openitsm.mapper.NotificationMapper;
import com.openitsm.service.AccountMailService;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AccountMailServiceImpl implements AccountMailService {
    private final NotificationMapper mapper;
    private final Clock clock;
    public AccountMailServiceImpl(NotificationMapper mapper, Clock clock) {
        this.mapper = mapper; this.clock = clock;
    }

    public void enqueue(UUID userId, String purpose, String rawToken, long generation) {
        UUID id = UUID.randomUUID();
        mapper.insertAccountTask(id, userId, purpose, clock.instant(), rawToken, generation);
    }
    public void cancel(UUID userId, String purpose) { mapper.cancelAccountTasks(userId, purpose); }
}
