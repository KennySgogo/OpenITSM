package com.openitsm.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.openitsm.common.utils.Values;

public record TicketReasonRequest(String reason) {
    @JsonCreator
    public static TicketReasonRequest fromJson(@JsonProperty("reason") JsonNode reason) {
        return new TicketReasonRequest(Values.jsonText(reason, "reason"));
    }
}
