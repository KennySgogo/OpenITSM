package com.openitsm.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.openitsm.common.utils.Values;

public record SaveTicketTypeRequest(String name) {
    @JsonCreator
    public static SaveTicketTypeRequest fromJson(@JsonProperty("name") JsonNode name) {
        return new SaveTicketTypeRequest(Values.jsonText(name, "name"));
    }
}
