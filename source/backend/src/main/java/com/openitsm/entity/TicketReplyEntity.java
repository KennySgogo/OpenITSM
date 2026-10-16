package com.openitsm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;

@TableName("ticket_reply")
public class TicketReplyEntity {
    @TableId(type = IdType.INPUT) public UUID id;
    public UUID ticketId;
    public UUID authorId;
    public String content;
    public String visibility;
    public Instant createdAt;
}
