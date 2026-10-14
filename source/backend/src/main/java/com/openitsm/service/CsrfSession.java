package com.openitsm.service;

public record CsrfSession(String rawToken, String csrfToken, boolean cookieChanged) {}
