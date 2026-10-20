package com.openitsm.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.openitsm.common.utils.Values;
import com.openitsm.common.exception.BusinessException;
import java.util.UUID;

public record CreateTicketRequest(String title, String description, String businessType, String impactScope,
    UUID businessTypeId, UUID assigneeId, String priority) {
    public CreateTicketRequest(String title, String description, String businessType, String impactScope) {
        this(title, description, businessType, impactScope, null, null, null);
    }
    @JsonCreator
    public static CreateTicketRequest fromJson(@JsonProperty("title") JsonNode title,
        @JsonProperty("description") JsonNode description, @JsonProperty("businessType") JsonNode businessType,
        @JsonProperty("impactScope") JsonNode impactScope, @JsonProperty("businessTypeId") JsonNode businessTypeId,
        @JsonProperty("assigneeId") JsonNode assigneeId, @JsonProperty("priority") JsonNode priority) {
        if (priority != null && !priority.isTextual()) throw BusinessException.field("priority", "字段必须为字符串");
        return new CreateTicketRequest(Values.jsonText(title, "title"), Values.jsonText(description, "description"),
            Values.jsonText(businessType, "businessType"), Values.jsonText(impactScope, "impactScope"),
            uuid(businessTypeId, "businessTypeId"), uuid(assigneeId, "assigneeId"), Values.jsonText(priority, "priority"));
    }
    private static UUID uuid(JsonNode value, String field) {
        if (value == null || value.isNull()) return null;
        try { return UUID.fromString(Values.jsonText(value, field)); }
        catch (IllegalArgumentException ex) { throw BusinessException.field(field, "请提供有效 UUID"); }
    }
}
