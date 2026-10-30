package com.openitsm.service.impl;

import com.openitsm.ai.client.PythonAiClient;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.entity.TicketEntity;
import com.openitsm.mapper.*;
import com.openitsm.service.*;
import com.openitsm.vo.ReplyDraftVO;
import com.openitsm.vo.SimilarIssuesVO;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ReplyDraftServiceImpl implements ReplyDraftService {
    private final SimilarIssueService similar;
    private final TicketMapper tickets;
    private final KnowledgeArticleMapper articles;
    private final SessionService sessions;
    private final RuntimeSettingsService runtime;
    private final PythonAiClient python;
    private final AiConnectionStatusMapper statuses;
    private final Clock clock;

    public ReplyDraftServiceImpl(SimilarIssueService similar, TicketMapper tickets, KnowledgeArticleMapper articles,
        SessionService sessions, RuntimeSettingsService runtime, PythonAiClient python, AiConnectionStatusMapper statuses, Clock clock) {
        this.similar = similar; this.tickets = tickets; this.articles = articles; this.sessions = sessions;
        this.runtime = runtime; this.python = python; this.statuses = statuses; this.clock = clock;
    }

    private TicketEntity current(AuthContext context, UUID id) {
        var session = sessions.resolve(context.rawToken());
        if (session.expired() || session.context().user() == null)
            throw new BusinessException(401, "SESSION_EXPIRED", "会话已失效，请重新登录");
        if (!Set.of("ENGINEER", "ADMIN").contains(session.context().user().role))
            throw new BusinessException(403, "FORBIDDEN", "仅工程师和管理员可生成回复草稿");
        var ticket = tickets.selectById(id);
        if (ticket == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "工单不存在");
        if (!Set.of("NEW", "IN_PROGRESS", "ON_HOLD", "RESOLVED").contains(ticket.status))
            throw new BusinessException(409, "INVALID_STATE", "当前工单不能生成回复草稿");
        return ticket;
    }

    private record Source(ReplyDraftVO.Source view, String content, String summary) {}
    private Source source(SimilarIssuesVO.Item item) {
        if ("TICKET".equals(item.sourceType())) {
            var ticket = tickets.selectById(item.sourceId());
            if (ticket == null || !Set.of("RESOLVED", "CLOSED").contains(ticket.status)) throw changed();
            return new Source(new ReplyDraftVO.Source("TICKET", ticket.id, ticket.number, ticket.title), ticket.description, ticket.resolutionSummary);
        }
        var article = articles.selectById(item.sourceId());
        if (article == null || !"PUBLISHED".equals(article.status)) throw changed();
        return new Source(new ReplyDraftVO.Source("KNOWLEDGE", article.id, null, article.title), article.content, null);
    }

    @Override
    public ReplyDraftVO generate(AuthContext context, UUID id) {
        var ticket = current(context, id);
        var settings = runtime.requireAiConnection();
        var matches = similar.search(context, id).items();
        var sources = matches.stream().map(this::source).toList();
        runtime.recheckAiConnection(settings.version());
        if (current(context, id).version != ticket.version) throw changed();
        var materials = sources.stream().map(source -> new PythonAiClient.SimilarSource(
            source.view().sourceType() + ":" + source.view().sourceId(), clip(source.view().title(), 1000),
            clip(source.content(), 1400) + (source.summary() == null ? "" : "\n公开解决摘要：" + clip(source.summary(), 700)))).toList();
        UUID attempt = UUID.randomUUID();
        statuses.begin("CALL", settings.version(), clock.instant(), attempt);
        try {
            var result = python.replyDraft(
                    settings.model(),
                    clip(ticket.title, 1000),
                    clip(ticket.description, 4000),
                    ticket.businessType, materials);

            if (result.content() == null || result.content().isBlank() || result.content().length() > 2000)
                throw new BusinessException(503, "AI_INVALID_RESPONSE", "模型返回的草稿无效");
            runtime.recheckAiConnection(settings.version());
            if (current(context, id).version != ticket.version) throw changed();
            for (int i = 0; i < matches.size(); i++) if (!sources.get(i).equals(source(matches.get(i)))) throw changed();
            statuses.finish("CALL", attempt, "SUCCESS", clock.instant());
            return new ReplyDraftVO(result.content(), sources.stream().map(Source::view).toList(), ticket.version, !sources.isEmpty());
        } catch (BusinessException ex) {
            statuses.finish("CALL", attempt, ex.code, clock.instant()); throw ex;
        }
    }

    private static String clip(String text, int limit) { return text.substring(0, Math.min(text.length(), limit)); }
    private static BusinessException changed() { return new BusinessException(409, "INVALID_STATE", "工单或参考来源已变化，请重新核对并生成草稿"); }
}
