package com.openitsm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;

@TableName("idempotency_record")
public record IdempotencyRecordEntity(UUID actorId, String operation, UUID key, String requestHash,
    String state, UUID resourceId, long acceptedVersion, int responseStatus, Instant createdAt) {}
