package com.openitsm.service;

/** Server-only immutable runtime snapshot. Never serialize or log credentials. */
public record SettingsSnapshot(long version, String teamName, Smtp smtp, Model model, boolean aiEnabled) {
    public record Smtp(String host, int port, String username, String from, String security, String password) {
        @Override public String toString() { return "Smtp[redacted]"; }
    }
    public record Model(String baseUrl, String model, String apiKey) {
        @Override public String toString() { return "Model[redacted]"; }
        public boolean configured() { return !baseUrl.isBlank() && !model.isBlank() && apiKey != null && !apiKey.isBlank(); }
    }
    @Override public String toString() { return "SettingsSnapshot[version=" + version + "]"; }
}
