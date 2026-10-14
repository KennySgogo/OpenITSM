package com.openitsm.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name="Patch")
public record UpdateUserRequest(String name, String role) {}
