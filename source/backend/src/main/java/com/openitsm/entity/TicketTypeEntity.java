package com.openitsm.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.util.UUID;

@TableName("ticket_type")
public class TicketTypeEntity {
    @TableId(type = IdType.INPUT) public UUID id;
    public String name;
    public UUID creatorId;
    public boolean active = true;
    public long version;
}
