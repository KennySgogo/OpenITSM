package com.openitsm.service;

import java.util.UUID;

public interface AccountMailService {
    void requireReady();
    void enqueue(UUID userId, String purpose, String rawToken, long generation);
    void cancel(UUID userId, String purpose);
}
