package com.openitsm.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.openitsm.common.utils.Values;

public record CreateTicketReplyRequest(String content) {
    @JsonCreator
    public static CreateTicketReplyRequest fromJson(@JsonProperty("content") JsonNode content) {
        return new CreateTicketReplyRequest(Values.jsonText(content, "content"));
    }
}
