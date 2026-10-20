package com.openitsm.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;
import java.util.UUID;

@TableName("app_user")
public class UserEntity {
    @TableId(type = IdType.INPUT) public UUID id;
    public String emailNormalized;
    public String name;
    public String skillsDescription;
    public String role;
    public String status;
    public String passwordHash;
    public int failedCount;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) public Instant lockedUntil;
    public long authVersion;
    public long version;
    public Instant updatedAt;

}
