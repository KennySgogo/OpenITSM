package com.openitsm.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.openitsm.common.exception.BusinessException;

public record CreateTicketRequest(String title, String description, String businessType, String impactScope) {
    @JsonCreator
    public static CreateTicketRequest fromJson(@JsonProperty("title") JsonNode title,
        @JsonProperty("description") JsonNode description, @JsonProperty("businessType") JsonNode businessType,
        @JsonProperty("impactScope") JsonNode impactScope) {
        return new CreateTicketRequest(text(title, "title"), text(description, "description"),
            text(businessType, "businessType"), text(impactScope, "impactScope"));
    }

    private static String text(JsonNode value, String field) {
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw BusinessException.field(field, "字段必须为字符串");
        return value.textValue();
    }
}
