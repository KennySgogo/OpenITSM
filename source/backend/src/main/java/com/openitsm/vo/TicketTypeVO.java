package com.openitsm.vo;

import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TicketTypeVO(UUID id, String name, Long version, Boolean canManage) {}
