package com.openitsm.service;

import com.openitsm.dto.SaveKnowledgeArticleRequest;
import com.openitsm.vo.ActionVO;
import com.openitsm.vo.KnowledgeArticlePageVO;
import com.openitsm.vo.KnowledgeArticleVO;
import java.util.UUID;

public interface KnowledgeArticleService {
    KnowledgeArticlePageVO list(AuthContext context, String view, String page, String q, String category);
    KnowledgeArticleVO detail(AuthContext context, UUID id);
    ActionVO create(AuthContext context, SaveKnowledgeArticleRequest body, String key);
    ActionVO change(AuthContext context, UUID id, String action, SaveKnowledgeArticleRequest body, String version, String key);
}
