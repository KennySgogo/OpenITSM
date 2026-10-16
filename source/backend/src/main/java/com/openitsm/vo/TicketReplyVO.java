package com.openitsm.vo;

import java.time.Instant;
import java.util.UUID;

public record TicketReplyVO(UUID id, String content, String visibility, UUID authorId, String authorName, Instant createdAt) {}
