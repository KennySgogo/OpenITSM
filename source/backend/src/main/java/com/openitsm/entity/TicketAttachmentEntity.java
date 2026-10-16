package com.openitsm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;

@TableName("ticket_attachment")
public class TicketAttachmentEntity {
    @TableId(type = IdType.INPUT) public UUID id;
    public UUID ticketId;
    public UUID uploaderId;
    public String fileName;
    public long size;
    public String contentHash;
    public String visibility;
    public Instant createdAt;
}
