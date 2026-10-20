package com.openitsm.vo;

import java.time.Instant;
import java.util.UUID;

public record KnowledgeArticleVO(UUID id, String title, String content, String category, String status,
    UUID authorId, String authorName, long version, Instant createdAt, Instant updatedAt, Instant publishedAt) {}
