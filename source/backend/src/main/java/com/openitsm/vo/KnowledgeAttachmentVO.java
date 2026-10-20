package com.openitsm.vo;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeAttachmentVO(UUID id, String fileName, long size, UUID uploaderId, String uploaderName, Instant createdAt) {}
