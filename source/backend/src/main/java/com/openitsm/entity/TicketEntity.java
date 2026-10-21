package com.openitsm.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import java.time.Instant;
import java.util.UUID;

@TableName("ticket")
public class TicketEntity {
    @TableId(type = IdType.INPUT) public UUID id;
    public String number;
    public String title;
    public String description;
    public String businessType;
    public UUID businessTypeId;
    public String impactScope;
    public UUID requesterId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) public UUID assigneeId;
    public String status;
    public long version;
    public Instant createdAt;
    public String priority;
    public String resolutionSummary;
    public String rejectionReason;
    public Instant rejectedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) public Instant closedAt;
    public Instant cycleStartedAt;
    public boolean slaTracked;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) public Instant firstRespondedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) public Instant resolvedAt;
}
