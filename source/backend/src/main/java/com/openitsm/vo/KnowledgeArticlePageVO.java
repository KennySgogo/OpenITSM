package com.openitsm.vo;

import java.util.List;

public record KnowledgeArticlePageVO(List<KnowledgeArticleVO> items, int page, int pageSize, long total) {}
