package com.openitsm.service;

import com.openitsm.entity.AuthSessionEntity;
import com.openitsm.entity.UserEntity;

public record AuthContext(AuthSessionEntity session, UserEntity user, String rawToken) {}
