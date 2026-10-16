package com.openitsm.vo;

import java.util.List;

public record TicketAttachmentPageVO(List<TicketAttachmentVO> items, int page, int pageSize, long total) {}
