package com.openitsm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.openitsm.vo.ActionVO;
import com.openitsm.vo.SettingsVO;
import com.openitsm.vo.ModelConnectionTestVO;

public interface SettingsService {
    SettingsVO detail(AuthContext context);
    ActionVO update(AuthContext context, JsonNode body, String version, String key);
    ModelConnectionTestVO testModel(AuthContext context);
}
