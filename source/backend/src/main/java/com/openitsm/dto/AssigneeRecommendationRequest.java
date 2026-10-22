package com.openitsm.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.common.utils.Values;
import java.util.UUID;

public record AssigneeRecommendationRequest(String title, String description, UUID businessTypeId, String impactScope) {
    @JsonCreator
    public static AssigneeRecommendationRequest fromJson(@JsonProperty("title") JsonNode title,
        @JsonProperty("description") JsonNode description, @JsonProperty("businessTypeId") JsonNode type,
        @JsonProperty("impactScope") JsonNode impact) {
        UUID id;
        try { id = UUID.fromString(Values.jsonText(type, "businessTypeId")); }
        catch (IllegalArgumentException | NullPointerException ex) { throw BusinessException.field("businessTypeId", "请选择有效业务类型"); }
        return new AssigneeRecommendationRequest(Values.text(Values.jsonText(title, "title"), "title", 1, 1000),
            Values.text(Values.jsonText(description, "description"), "description", 1, 60000), id,
            Values.text(Values.jsonText(impact, "impactScope"), "impactScope", 1, 4000));
    }
}
