package com.openitsm.service;

public interface SmtpMailService {
    void send(String recipient, String purpose, String rawToken);
}
