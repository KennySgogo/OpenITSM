package com.openitsm.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name="Email")
public record PasswordResetRequest(String email) {}
