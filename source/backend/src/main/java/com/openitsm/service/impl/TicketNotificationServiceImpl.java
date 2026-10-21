package com.openitsm.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.openitsm.entity.TicketEntity;
import com.openitsm.entity.UserEntity;
import com.openitsm.mapper.NotificationMapper;
import com.openitsm.mapper.UserMapper;
import com.openitsm.service.TicketNotificationService;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TicketNotificationServiceImpl implements TicketNotificationService {
    private final NotificationMapper tasks;
    private final UserMapper users;
    private final Clock clock;

    public TicketNotificationServiceImpl(NotificationMapper tasks, UserMapper users, Clock clock) {
        this.tasks = tasks; this.users = users; this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(TicketEntity ticket, UUID actorId, String action) {
        if (!Set.of("assignments", "transfers", "replies", "resolutions", "close", "reopen").contains(action)) return;
        var recipients = new HashSet<UUID>();
        recipients.add(ticket.requesterId);
        if (ticket.assigneeId != null) recipients.add(ticket.assigneeId);
        recipients.remove(actorId);
        if (recipients.isEmpty()) return;
        UUID eventId = UUID.randomUUID();
        // Do not lock other members while holding the ticket row; delivery rechecks live permissions.
        var active = users.selectList(new QueryWrapper<UserEntity>().in("id", recipients).eq("status", "ACTIVE"));
        for (var recipient : active) {
            if ("EMPLOYEE".equals(recipient.role) && !recipient.id.equals(ticket.requesterId)) continue;
            tasks.insertTicketTask(UUID.randomUUID(), eventId, recipient.id, clock.instant(), ticket.id, action);
        }
    }
}
