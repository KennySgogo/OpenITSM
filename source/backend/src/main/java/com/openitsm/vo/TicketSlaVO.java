package com.openitsm.vo;

import java.time.Instant;

public record TicketSlaVO(Instant responseDueAt, Instant resolutionDueAt, Instant firstRespondedAt,
    Instant resolvedAt, boolean responseOverdue, boolean resolutionOverdue) {}
