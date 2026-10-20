package com.openitsm.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;

@TableName("knowledge_article")
public class KnowledgeArticleEntity {
    @TableId(type = IdType.INPUT) public UUID id;
    public String title;
    public String content;
    public String category;
    public String status;
    public UUID authorId;
    public long version;
    public Instant createdAt;
    public Instant updatedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) public Instant publishedAt;
}
