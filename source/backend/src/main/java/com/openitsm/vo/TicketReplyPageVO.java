package com.openitsm.vo;

import java.util.List;

public record TicketReplyPageVO(List<TicketReplyVO> items, int page, int pageSize, long total) {}
