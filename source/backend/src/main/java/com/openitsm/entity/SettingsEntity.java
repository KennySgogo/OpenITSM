package com.openitsm.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.util.UUID;

@TableName("team_settings")
public class SettingsEntity {
    public static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000014");
    @TableId(type = IdType.INPUT) public UUID id = ID;
    public long version;
    public String teamName;
    public String smtpHost;
    public int smtpPort;
    public String smtpUsername;
    public String smtpFrom;
    public String smtpSecurity;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) public String smtpPassword;
    public String modelBaseUrl;
    public String modelName;
    @TableField(updateStrategy = FieldStrategy.ALWAYS) public String modelApiKey;
    public boolean aiEnabled;
}
