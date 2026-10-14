package com.openitsm.service;

import java.util.UUID;

public interface BootstrapService {
    UUID initialize(String name, String email, String password);
}
