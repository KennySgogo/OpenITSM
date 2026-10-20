package com.openitsm.service;

import com.openitsm.entity.TicketEntity;
import java.util.UUID;

public interface TicketNotificationService {
    void enqueue(TicketEntity ticket, UUID actorId, String action);
}
