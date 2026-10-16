package com.openitsm.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openitsm.mapper.AuditMapper;
import com.openitsm.service.AuditService;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AuditServiceImpl implements AuditService {
    private final AuditMapper mapper;
    private final Clock clock;
    private final ObjectMapper json;
    public AuditServiceImpl(AuditMapper mapper, Clock clock, ObjectMapper json) { this.mapper = mapper; this.clock = clock; this.json = json; }

    public void append(UUID actor, String action, UUID target, Map<String, ?> changes, String reason, UUID requestId) {
        try {
            mapper.insert(actor, action, target, json.writeValueAsString(changes), reason, requestId, clock.instant());
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }

    public void appendTicket(UUID actor, String action, UUID target, Map<String, ?> changes, String reason, UUID requestId) {
        try {
            mapper.insertTicket(UUID.randomUUID(), actor, action, target, json.writeValueAsString(changes), reason, requestId, clock.instant());
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
}
