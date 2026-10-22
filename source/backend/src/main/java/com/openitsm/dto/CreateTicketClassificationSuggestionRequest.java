package com.openitsm.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.common.utils.Values;

public record CreateTicketClassificationSuggestionRequest(String title, String description, String impactScope) {
    @JsonCreator
    public static CreateTicketClassificationSuggestionRequest fromJson(@JsonProperty("title") JsonNode title,
        @JsonProperty("description") JsonNode description, @JsonProperty("impactScope") JsonNode impactScope) {
        if (impactScope != null && !impactScope.isTextual())
            throw BusinessException.field("impactScope", "字段必须为字符串");
        return new CreateTicketClassificationSuggestionRequest(
            Values.text(Values.jsonText(title, "title"), "title", 1, 1000),
            Values.text(Values.jsonText(description, "description"), "description", 1, 60000),
            Values.text(Values.jsonText(impactScope, "impactScope"), "impactScope", 0, 4000));
    }
}
