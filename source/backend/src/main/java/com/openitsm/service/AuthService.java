package com.openitsm.service;

import com.openitsm.entity.AuthSessionEntity;
import com.openitsm.entity.UserEntity;
import com.openitsm.vo.SessionVO;
import java.util.UUID;

public interface AuthService {
    LoginSession authenticate(String email, String password, String oldHash, String source);
    void requestResetFromSource(String email, String source);
    void consumeFromSource(String raw, String password, String purpose, UUID requestId, String source);
    LoginSession login(String email, String password, String oldHash);
    SessionVO view(UserEntity user, AuthSessionEntity session, String raw, String returnTo);
    void requestReset(String email);
    void consume(String raw, String password, String purpose, UUID requestId);
}
