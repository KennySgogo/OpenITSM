package com.openitsm.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name="Password")
public record SetPasswordRequest(String token, String password, String confirmPassword) {}
