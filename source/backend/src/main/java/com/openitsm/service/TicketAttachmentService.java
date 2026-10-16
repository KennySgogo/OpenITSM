package com.openitsm.service;

import com.openitsm.dto.UploadTicketAttachmentRequest;
import com.openitsm.vo.ActionVO;
import com.openitsm.vo.AttachmentDownloadVO;
import com.openitsm.vo.TicketAttachmentPageVO;
import java.util.UUID;

public interface TicketAttachmentService {
    ActionVO upload(AuthContext context, UUID ticketId, UploadTicketAttachmentRequest body, String version, String key, UUID requestId);
    TicketAttachmentPageVO list(AuthContext context, UUID ticketId, String page);
    AttachmentDownloadVO download(AuthContext context, UUID attachmentId);
    boolean storageReady();
}
