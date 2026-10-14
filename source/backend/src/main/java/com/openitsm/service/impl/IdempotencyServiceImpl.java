package com.openitsm.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openitsm.mapper.IdempotencyMapper;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.mapper.TransactionLockMapper;
import com.openitsm.common.utils.TokenCodec;
import com.openitsm.vo.ActionVO;
import com.openitsm.service.IdempotencyService;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class IdempotencyServiceImpl implements IdempotencyService {
    private final IdempotencyMapper mapper;
    private final TransactionLockMapper locks;
    private final Clock clock;
    private final ObjectMapper json;
    public IdempotencyServiceImpl(IdempotencyMapper mapper, TransactionLockMapper locks, Clock clock, ObjectMapper json) {
        this.mapper = mapper; this.locks = locks; this.clock = clock; this.json = json;
    }
    public UUID key(String raw) {
        try { return UUID.fromString(raw); } catch (Exception ex) { throw BusinessException.field("Idempotency-Key", "请提供 UUID 幂等键"); }
    }
    public String hash(Object body, String version) {
        try { return TokenCodec.hash(json.writeValueAsString(body) + "\n" + version); }
        catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
    public ActionVO replay(UUID actor, String operation, UUID key, String hash) {
        reserve(actor, operation, key);
        var record = mapper.find(actor, operation, key);
        if (record == null) return null;
        if (!hash.equals(record.requestHash())) throw new BusinessException(409, "IDEMPOTENCY_CONFLICT", "幂等键已用于其他请求");
        return new ActionVO(record.resourceId(), record.acceptedVersion(), true);
    }
    public void reserve(UUID actor, String operation, UUID key) {
        if (!locks.tryLock("IDEMPOTENCY", actor + ":" + operation + ":" + key))
            throw new BusinessException(409, "REQUEST_IN_PROGRESS", "请求正在处理中，请稍后重试", java.util.List.of(), 1);
    }
    public ActionVO save(UUID actor, String operation, UUID key, String hash, UUID target, long version, int status) {
        mapper.insert(actor, operation, key, hash, target, version, status, clock.instant());
        return new ActionVO(target, version, false);
    }
}
