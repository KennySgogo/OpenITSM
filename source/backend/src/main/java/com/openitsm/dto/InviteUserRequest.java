package com.openitsm.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name="Invite")
public record InviteUserRequest(String name, String email, String role) {}
