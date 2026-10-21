package com.openitsm.service;

public interface SmtpMailService {
    void send(String recipient, String purpose, String rawToken);
    void sendTicket(String recipient, java.util.UUID ticketId, String number, String action);
}
