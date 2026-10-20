package com.openitsm.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.openitsm.common.utils.Values;

public record SaveKnowledgeArticleRequest(String title, String content, String category) {
    @JsonCreator
    public static SaveKnowledgeArticleRequest fromJson(@JsonProperty("title") JsonNode title,
        @JsonProperty("content") JsonNode content, @JsonProperty("category") JsonNode category) {
        return new SaveKnowledgeArticleRequest(Values.jsonText(title, "title"), Values.jsonText(content, "content"),
            Values.jsonText(category, "category"));
    }
}
