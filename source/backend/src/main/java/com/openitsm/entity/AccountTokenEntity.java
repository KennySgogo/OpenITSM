package com.openitsm.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import java.util.UUID;

@TableName("account_token")
public record AccountTokenEntity(String hash, UUID userId, String purpose, Instant expiresAt, Instant usedAt, long generation) {}
