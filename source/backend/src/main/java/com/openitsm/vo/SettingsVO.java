package com.openitsm.vo;

import java.time.Instant;
import java.util.UUID;

public record SettingsVO(UUID id, long version, String teamName, SmtpVO smtp, ModelVO model,
    boolean aiEnabled, StatusVO lastTest, StatusVO lastCall) {
    public record SmtpVO(String host, int port, String username, String from, String security, boolean passwordConfigured) {}
    public record ModelVO(String baseUrl, String model, boolean apiKeyConfigured) {}
    public record StatusVO(String status, Long testedVersion, Instant occurredAt, boolean current, String outputMode) {}
}
