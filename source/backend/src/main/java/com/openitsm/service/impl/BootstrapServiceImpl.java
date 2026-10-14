package com.openitsm.service.impl;

import com.openitsm.service.AuditService;
import com.openitsm.service.BootstrapService;
import com.openitsm.mapper.BootstrapMapper;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.mapper.TransactionLockMapper;
import com.openitsm.common.utils.Values;
import com.openitsm.entity.UserEntity;
import com.openitsm.mapper.UserMapper;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BootstrapServiceImpl implements BootstrapService {
    private final TransactionLockMapper locks;
    private final UserMapper users;
    private final BootstrapMapper mapper;
    private final PasswordEncoder encoder;
    private final Clock clock;
    private final AuditService audit;
    public BootstrapServiceImpl(TransactionLockMapper locks, UserMapper users, BootstrapMapper mapper, PasswordEncoder encoder, Clock clock, AuditService audit) {
        this.locks = locks; this.users = users; this.mapper = mapper; this.encoder = encoder; this.clock = clock; this.audit = audit;
    }

    @Transactional
    public UUID initialize(String name, String email, String password) {
        name = Values.text(name, "name", 1, 80); email = Values.email(email); Values.password(password, password);
        locks.lock("MEMBERS", "CONTROL");
        if (mapper.completed() || users.selectCount(null) != 0)
            throw new BusinessException(409, "INITIALIZATION_CLOSED", "初始化入口已关闭");
        UserEntity user = new UserEntity();
        user.id = UUID.randomUUID(); user.name = name; user.emailNormalized = email; user.role = "ADMIN"; user.status = "ACTIVE";
        user.passwordHash = encoder.encode(password); user.updatedAt = clock.instant();
        users.insert(user);
        mapper.complete(clock.instant());
        audit.append(user.id, "ADMIN_BOOTSTRAPPED", user.id, Map.of("status", "ACTIVE", "role", "ADMIN"), null, UUID.randomUUID());
        return user.id;
    }
}
