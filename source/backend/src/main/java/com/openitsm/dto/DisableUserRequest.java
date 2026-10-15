package com.openitsm.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name="Disable")
public record DisableUserRequest(String reason) {}
