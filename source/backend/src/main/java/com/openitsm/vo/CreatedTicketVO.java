package com.openitsm.vo;

import java.util.UUID;

public record CreatedTicketVO(UUID id, String number, boolean replayed) {}
