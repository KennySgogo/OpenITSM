package com.openitsm.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.common.utils.Values;

public record UpdateProfileRequest(String name, String skillsDescription) {
    @JsonCreator
    public static UpdateProfileRequest fromJson(@JsonProperty("name") JsonNode name,
        @JsonProperty("skillsDescription") JsonNode skillsDescription) {
        if (name != null && !name.isTextual()) throw BusinessException.field("name", "字段必须为字符串");
        if (skillsDescription != null && !skillsDescription.isTextual()) throw BusinessException.field("skillsDescription", "字段必须为字符串");
        return new UpdateProfileRequest(Values.jsonText(name, "name"), Values.jsonText(skillsDescription, "skillsDescription"));
    }
}
