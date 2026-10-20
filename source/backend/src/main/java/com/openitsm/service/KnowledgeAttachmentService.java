package com.openitsm.service;

import com.openitsm.dto.UploadKnowledgeAttachmentRequest;
import com.openitsm.vo.ActionVO;
import com.openitsm.vo.AttachmentDownloadVO;
import com.openitsm.vo.KnowledgeAttachmentPageVO;
import java.util.UUID;

public interface KnowledgeAttachmentService {
    boolean storageReady();
    ActionVO upload(AuthContext context, UUID articleId, UploadKnowledgeAttachmentRequest body, String version, String key);
    ActionVO remove(AuthContext context, UUID articleId, UUID attachmentId, String version, String key);
    KnowledgeAttachmentPageVO list(AuthContext context, UUID articleId, String page);
    AttachmentDownloadVO download(AuthContext context, UUID attachmentId);
}
