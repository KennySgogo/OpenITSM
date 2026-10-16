package com.openitsm.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.openitsm.common.utils.Values;

public record ChangeTicketPriorityRequest(String priority, String reason) {
    @JsonCreator
    public static ChangeTicketPriorityRequest fromJson(@JsonProperty("priority") JsonNode priority,
        @JsonProperty("reason") JsonNode reason) {
        return new ChangeTicketPriorityRequest(Values.jsonText(priority, "priority"), Values.jsonText(reason, "reason"));
    }
}
