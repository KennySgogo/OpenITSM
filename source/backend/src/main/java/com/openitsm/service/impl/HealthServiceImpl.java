package com.openitsm.service.impl;

import com.openitsm.mapper.HealthMapper;
import com.openitsm.service.HealthService;
import com.openitsm.service.TicketAttachmentService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class HealthServiceImpl implements HealthService {
    private final HealthMapper mapper;
    private final TicketAttachmentService attachments;
    public HealthServiceImpl(HealthMapper mapper, TicketAttachmentService attachments) { this.mapper = mapper; this.attachments = attachments; }
    public boolean ready() {
        try { mapper.ping(); return attachments.storageReady(); }
        catch (DataAccessException ex) { return false; }
    }
}
