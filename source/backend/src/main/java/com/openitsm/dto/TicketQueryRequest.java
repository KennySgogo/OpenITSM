package com.openitsm.dto;

public record TicketQueryRequest(String businessTypeId, String lifecycle, String createdFrom, String createdTo,
    String priority, String requesterId, String assigneeId) {}
