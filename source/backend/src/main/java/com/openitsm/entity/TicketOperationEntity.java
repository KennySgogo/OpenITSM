package com.openitsm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;

@TableName("audit_event")
public class TicketOperationEntity {
    @TableId(type = IdType.INPUT) public UUID id;
    public UUID actorId;
    public String action;
    public String objectType;
    public UUID objectId;
    public String beforeAfter;
    public String reason;
    public String outcome;
    public UUID requestId;
    public Instant createdAt;
}
