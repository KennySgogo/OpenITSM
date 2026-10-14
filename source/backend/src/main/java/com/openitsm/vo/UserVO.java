package com.openitsm.vo;

import com.openitsm.entity.UserEntity;
import java.util.UUID;
public record UserVO(UUID id, String name, String email, String role, String status, long version) {
    public static UserVO from(UserEntity user) { return new UserVO(user.id, user.name, user.emailNormalized, user.role, user.status, user.version); }
}
