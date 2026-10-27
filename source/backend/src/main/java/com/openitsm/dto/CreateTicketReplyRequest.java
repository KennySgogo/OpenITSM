package com.openitsm.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.openitsm.common.utils.Values;

public record CreateTicketReplyRequest(String content, Long basedOnVersion) {
    @JsonCreator
    public static CreateTicketReplyRequest fromJson(@JsonProperty("content") JsonNode content,
        @JsonProperty("basedOnVersion") JsonNode version) {
        if (version != null && (!version.isIntegralNumber() || !version.canConvertToLong() || version.longValue() < 0))
            throw com.openitsm.common.exception.BusinessException.field("basedOnVersion", "草稿版本必须为非负整数");
        return new CreateTicketReplyRequest(Values.jsonText(content, "content"), version == null ? null : version.longValue());
    }
}
