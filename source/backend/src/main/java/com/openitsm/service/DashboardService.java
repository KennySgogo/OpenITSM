package com.openitsm.service;

import java.util.Map;

public interface DashboardService {
    Map<String, Object> metrics(AuthContext context, String createdFrom, String createdTo);
}
