package com.openitsm.entity;

import java.time.Instant;
import java.util.UUID;

public record AiConnectionStatusEntity(String kind, long configVersion, String status, Instant occurredAt, UUID attemptId, String outputMode) {}
