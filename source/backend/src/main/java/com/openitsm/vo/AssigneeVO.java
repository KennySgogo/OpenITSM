package com.openitsm.vo;

import java.util.UUID;
public record AssigneeVO(UUID id, String name, long unresolvedCount, long pendingConfirmationCount) {}
