package com.openitsm.service;

import com.openitsm.dto.CreateTicketReplyRequest;
import com.openitsm.vo.ActionVO;
import com.openitsm.vo.TicketOperationPageVO;
import com.openitsm.vo.TicketReplyPageVO;
import java.util.UUID;

public interface TicketCommunicationService {
    ActionVO append(AuthContext context, UUID id, boolean internal, CreateTicketReplyRequest body,
        UUID requestId);
    TicketReplyPageVO replies(AuthContext context, UUID id, String page);
    TicketOperationPageVO operations(AuthContext context, UUID id, String page);
}
