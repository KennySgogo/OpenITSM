package com.openitsm.vo;

import java.util.List;

public record TicketOperationPageVO(List<TicketOperationVO> items, int page, int pageSize, long total) {}
