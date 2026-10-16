package com.openitsm.service;

import java.util.Map;
import java.util.UUID;

public interface AuditService {
    void append(UUID actor, String action, UUID target, Map<String, ?> changes, String reason, UUID requestId);
    void appendTicket(UUID actor, String action, UUID target, Map<String, ?> changes, String reason, UUID requestId);
}
