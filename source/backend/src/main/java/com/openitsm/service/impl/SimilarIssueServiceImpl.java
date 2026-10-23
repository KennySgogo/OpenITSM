package com.openitsm.service.impl;

import com.openitsm.ai.client.PythonAiClient;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.mapper.*;
import com.openitsm.service.*;
import com.openitsm.vo.SimilarIssueSourceVO;
import com.openitsm.vo.SimilarIssuesVO;
import java.time.Clock;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class SimilarIssueServiceImpl implements SimilarIssueService {
    private final SimilarIssueMapper sources;
    private final TicketMapper tickets;
    private final KnowledgeArticleMapper articles;
    private final SessionService sessions;
    private final RuntimeSettingsService runtime;
    private final PythonAiClient python;
    private final AiConnectionStatusMapper statuses;
    private final Clock clock;

    public SimilarIssueServiceImpl(SimilarIssueMapper sources, TicketMapper tickets, KnowledgeArticleMapper articles,
        SessionService sessions, RuntimeSettingsService runtime, PythonAiClient python, AiConnectionStatusMapper statuses, Clock clock) {
        this.sources = sources; this.tickets = tickets; this.articles = articles; this.sessions = sessions;
        this.runtime = runtime; this.python = python; this.statuses = statuses; this.clock = clock;
    }

    private void authorize(AuthContext context) {
        var resolved = sessions.resolve(context.rawToken());
        if (resolved.expired() || resolved.context().user() == null)
            throw new BusinessException(401, "SESSION_EXPIRED", "会话已失效，请重新登录");
        if (!Set.of("ENGINEER", "ADMIN").contains(resolved.context().user().role))
            throw new BusinessException(403, "FORBIDDEN", "仅工程师和管理员可检索相似来源");
    }

    @Override
    public SimilarIssuesVO search(AuthContext context, UUID ticketId) {
        authorize(context);
        var ticket = tickets.selectById(ticketId);
        if (ticket == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "工单不存在");
        var settings = runtime.requireAiConnection();
        var terms = terms(ticket.title + " " + ticket.description);
        var candidates = terms.isEmpty() ? List.<SimilarIssueSourceVO>of() : sources.candidates(ticketId, terms);
        if (candidates.isEmpty()) {
            runtime.recheckAiConnection(settings.version()); authorize(context);
            return new SimilarIssuesVO(List.of());
        }
        var allowed = new LinkedHashMap<String, SimilarIssueSourceVO>();
        candidates.forEach(source -> allowed.put(source.sourceType() + ":" + source.sourceId(), source));
        var materials = allowed.entrySet().stream().map(entry -> new PythonAiClient.SimilarSource(entry.getKey(),
            clip(entry.getValue().title(), 1000), sourceText(entry.getValue(), terms))).toList();
        UUID attempt = UUID.randomUUID();
        statuses.begin("CALL", settings.version(), clock.instant(), attempt);
        try {
            var result = python.similarIssues(settings.model(), clip(ticket.title, 1000), clip(ticket.description, 4000), ticket.businessType, materials);
            runtime.recheckAiConnection(settings.version()); authorize(context);
            var currentTicket = tickets.selectById(ticketId);
            if (currentTicket == null) throw new BusinessException(404, "RESOURCE_NOT_FOUND", "工单不存在");
            if (!Objects.equals(ticket.title, currentTicket.title) || !Objects.equals(ticket.description, currentTicket.description)
                || !Objects.equals(ticket.businessTypeId, currentTicket.businessTypeId))
                throw new BusinessException(409, "INVALID_STATE", "工单问题已变化，请重新检索");
            if (result.items() == null || result.items().size() > 5) throw invalid();
            var items = new ArrayList<SimilarIssuesVO.Item>();
            for (var match : result.items()) {
                var source = match == null ? null : allowed.remove(match.sourceKey());
                if (source == null) throw invalid();
                var current = current(source);
                if (current != null && Objects.equals(source.title(), current.title()) && Objects.equals(source.content(), current.content())
                    && Objects.equals(source.resolutionSummary(), current.resolutionSummary()))
                    items.add(new SimilarIssuesVO.Item(current.sourceType(), current.sourceId(), current.number(), current.title(),
                        passage(current.resolutionSummary() != null && !current.resolutionSummary().isBlank()
                            ? current.resolutionSummary() : current.content(), terms, 240), match.reason()));
            }
            statuses.finish("CALL", attempt, "SUCCESS", clock.instant());
            return new SimilarIssuesVO(items);
        } catch (BusinessException ex) {
            statuses.finish("CALL", attempt, ex.code, clock.instant()); throw ex;
        }
    }

    private SimilarIssueSourceVO current(SimilarIssueSourceVO source) {
        if ("TICKET".equals(source.sourceType())) {
            var ticket = tickets.selectById(source.sourceId());
            if (ticket == null || !Set.of("RESOLVED", "CLOSED").contains(ticket.status)) return null;
            return new SimilarIssueSourceVO("TICKET", ticket.id, ticket.number, ticket.title,
                ticket.description, ticket.resolutionSummary, 0);
        }
        var article = articles.selectById(source.sourceId());
        return article == null || !"PUBLISHED".equals(article.status) ? null
            : new SimilarIssueSourceVO("KNOWLEDGE", article.id, null, article.title, article.content, null, 0);
    }

    private String sourceText(SimilarIssueSourceVO source, List<String> terms) {
        if ("TICKET".equals(source.sourceType())) {
            return passage(source.content(), terms, 1200) + "\n公开解决摘要："
                + clip(source.resolutionSummary() == null ? "" : source.resolutionSummary(), 800);
        }
        return passage(source.content(), terms, 2000);
    }

    private static List<String> terms(String text) {
        var result = new LinkedHashSet<String>();
        var matcher = Pattern.compile("[A-Za-z0-9_]{2,}|[\\p{IsHan}]+").matcher(clip(text, 6000).toLowerCase(Locale.ROOT));
        while (matcher.find() && result.size() < 24) {
            String word = matcher.group();
            if (Character.UnicodeScript.of(word.codePointAt(0)) == Character.UnicodeScript.HAN) {
                for (int i = 0; i + 1 < word.length() && result.size() < 24; i++) result.add(word.substring(i, i + 2));
            } else result.add(word);
        }
        return new ArrayList<>(result);
    }

    private static String clip(String text, int length) { return text.substring(0, Math.min(text.length(), length)); }
    private static String passage(String text, List<String> terms, int length) {
        String lower = text.toLowerCase(Locale.ROOT);
        int position = terms.stream().mapToInt(lower::indexOf).filter(index -> index >= 0).min().orElse(0);
        int start = Math.max(0, position - 80);
        return text.substring(start, Math.min(text.length(), start + length));
    }
    private static BusinessException invalid() { return new BusinessException(503, "AI_INVALID_RESPONSE", "模型返回来源无效"); }
}
