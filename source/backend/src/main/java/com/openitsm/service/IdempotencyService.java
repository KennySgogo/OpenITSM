package com.openitsm.service;

import com.openitsm.vo.ActionVO;
import java.util.UUID;

public interface IdempotencyService {
    UUID key(String raw);
    String hash(Object body, String version);
    ActionVO replay(UUID actor, String operation, UUID key, String hash);
    void reserve(UUID actor, String operation, UUID key);
    ActionVO save(UUID actor, String operation, UUID key, String hash, UUID target, long version, int status);
}
