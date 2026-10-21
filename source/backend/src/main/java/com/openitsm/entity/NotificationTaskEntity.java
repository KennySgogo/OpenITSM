package com.openitsm.entity;

import java.time.Instant;
import java.util.UUID;

public record NotificationTaskEntity(UUID id, UUID recipientId, String template, String sealedPayload,
                                     long tokenGeneration, int attempts, Instant leaseUntil,
                                     UUID ticketId, String ticketAction) {}
