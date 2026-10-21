package com.openitsm.vo;

import java.util.List;

public record KnowledgeAttachmentPageVO(List<KnowledgeAttachmentVO> items, int page, int pageSize, long total) {}
