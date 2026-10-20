package com.openitsm.service;

import com.openitsm.dto.CreateTicketRequest;
import com.openitsm.vo.CreatedTicketVO;
import com.openitsm.vo.ActionVO;
import com.openitsm.vo.TicketPageVO;
import com.openitsm.vo.TicketVO;
import java.util.UUID;

public interface TicketService {
    TicketPageVO list(AuthContext context, String view, String page, String status, String businessType, String q);
    TicketPageVO list(AuthContext context, String view, String page, String status, String businessType, String q, com.openitsm.dto.TicketQueryRequest filters);
    java.util.Map<String, Object> filterOptions(AuthContext context);
    TicketVO detail(AuthContext context, UUID id);
    CreatedTicketVO create(AuthContext context, CreateTicketRequest body, String keyHeader, UUID requestId);
    ActionVO change(AuthContext context, UUID id, String action, Object body, String versionHeader, String keyHeader, UUID requestId);
}
