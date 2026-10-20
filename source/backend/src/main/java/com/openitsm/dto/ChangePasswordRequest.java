package com.openitsm.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.openitsm.common.utils.Values;

public record ChangePasswordRequest(String oldPassword, String password, String confirmPassword) {
    @JsonCreator
    public static ChangePasswordRequest fromJson(@JsonProperty("oldPassword") JsonNode oldPassword,
        @JsonProperty("password") JsonNode password, @JsonProperty("confirmPassword") JsonNode confirmPassword) {
        return new ChangePasswordRequest(Values.jsonText(oldPassword, "oldPassword"), Values.jsonText(password, "password"),
            Values.jsonText(confirmPassword, "confirmPassword"));
    }
}
