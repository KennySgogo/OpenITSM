package com.openitsm.service.impl;

import com.openitsm.mapper.HealthMapper;
import com.openitsm.service.HealthService;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class HealthServiceImpl implements HealthService {
    private final HealthMapper mapper;
    public HealthServiceImpl(HealthMapper mapper) { this.mapper = mapper; }
    public boolean ready() {
        try { mapper.ping(); return true; }
        catch (DataAccessException ex) { return false; }
    }
}
