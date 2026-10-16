package com.openitsm.vo;

import java.util.List;

public record TicketPageVO(List<TicketVO> items, int page, int pageSize, long total) {}
