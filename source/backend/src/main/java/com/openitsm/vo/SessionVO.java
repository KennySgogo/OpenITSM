package com.openitsm.vo;

import com.openitsm.vo.UserVO;
import java.time.Instant;
public record SessionVO(UserVO user, String defaultPath, Instant absoluteExpiresAt, String csrfToken, String redirectPath, String message) {}
