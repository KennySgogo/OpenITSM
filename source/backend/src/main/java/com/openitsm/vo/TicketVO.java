package com.openitsm.vo;

import com.openitsm.entity.TicketEntity;
import java.time.Instant;
import java.util.UUID;

public record TicketVO(UUID id, String number, String title, String description, String businessType,
    String impactScope, UUID requesterId, String requesterName, UUID assigneeId, String assigneeName,
    String status, String priority, long version, Instant createdAt, String resolutionSummary,
    Instant closedAt, Instant cycleStartedAt) {
    public static TicketVO from(TicketEntity ticket, String requesterName, String assigneeName) {
        return new TicketVO(ticket.id, ticket.number, ticket.title, ticket.description, ticket.businessType,
            ticket.impactScope, ticket.requesterId, requesterName, ticket.assigneeId, assigneeName,
            ticket.status, ticket.priority, ticket.version, ticket.createdAt, ticket.resolutionSummary,
            ticket.closedAt, ticket.cycleStartedAt);
    }
}
