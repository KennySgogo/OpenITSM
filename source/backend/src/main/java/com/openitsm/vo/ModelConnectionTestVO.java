package com.openitsm.vo;

import java.time.Instant;

public record ModelConnectionTestVO(String status, long testedVersion, Instant occurredAt, boolean current) {}
