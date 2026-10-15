package com.openitsm.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name="Login")
public record LoginRequest(String email, String password, String returnTo) {}
