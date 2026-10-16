package com.openitsm.vo;

import java.time.Instant;
import java.util.UUID;

public record TicketAttachmentVO(UUID id, String fileName, long size, String visibility, UUID uploaderId,
    String uploaderName, Instant createdAt) {}
