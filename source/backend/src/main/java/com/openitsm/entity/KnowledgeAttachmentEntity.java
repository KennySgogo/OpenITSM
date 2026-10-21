package com.openitsm.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;
import java.util.UUID;

@TableName("knowledge_attachment")
public class KnowledgeAttachmentEntity {
    @TableId(type = IdType.INPUT) public UUID id;
    public UUID articleId;
    public UUID uploaderId;
    public String fileName;
    public long size;
    public String contentHash;
    public Instant createdAt;
    public Instant removedAt;
    public UUID removedBy;
}
