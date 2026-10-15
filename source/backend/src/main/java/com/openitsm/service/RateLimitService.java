package com.openitsm.service;

import java.time.Duration;

public interface RateLimitService {
    void check(String scope, String key, int limit, Duration window, int cooldownSeconds);
}
