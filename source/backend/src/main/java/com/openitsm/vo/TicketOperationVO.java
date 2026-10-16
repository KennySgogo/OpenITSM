package com.openitsm.vo;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record TicketOperationVO(UUID id, String action, UUID actorId, String actorName, Instant createdAt,
    Map<String, Object> details) {}
