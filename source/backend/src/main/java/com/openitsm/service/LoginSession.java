package com.openitsm.service;

import com.openitsm.entity.AuthSessionEntity;
import com.openitsm.entity.UserEntity;

public record LoginSession(String rawToken, AuthSessionEntity session, UserEntity user) {}
