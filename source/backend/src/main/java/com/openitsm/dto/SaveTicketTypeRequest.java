package com.openitsm.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.openitsm.common.utils.Values;
import com.openitsm.common.exception.BusinessException;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SaveTicketTypeRequest(String name, String description) {
    @JsonCreator
    public static SaveTicketTypeRequest fromJson(@JsonProperty("name") JsonNode name,
        @JsonProperty("description") JsonNode description) {
        if (description != null && description.isNull())
            throw BusinessException.field("description", "说明须为字符串，留空可清除");
        return new SaveTicketTypeRequest(Values.jsonText(name, "name"), Values.jsonText(description, "description"));
    }
}
