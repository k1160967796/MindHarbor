package com.mindharbor.agent.service;

import com.mindharbor.agent.config.MindHarborProperties;
import com.mindharbor.agent.domain.ChatMessage;
import com.mindharbor.agent.domain.ChatSession;
import com.mindharbor.agent.domain.IntentType;
import com.mindharbor.agent.domain.MessageRole;
import com.mindharbor.agent.domain.PsychologicalReport;
import com.mindharbor.agent.domain.RiskLevel;
import com.mindharbor.agent.domain.UserAccount;
import com.mindharbor.agent.dto.ChatRequest;
import com.mindharbor.agent.dto.ChatStreamEvent;
import com.mindharbor.agent.repository.ChatMessageRepository;
import com.mindharbor.agent.repository.PsychologicalReportRepository;
import com.mindharbor.agent.service.ai.AiClient;
import com.mindharbor.agent.service.ai.AiMessage;
import com.mindharbor.agent.service.ai.PromptTemplates;
import com.mindharbor.agent.service.knowledge.SearchResult;
import com.mindharbor.agent.service.agent.AgentRunResult;
import com.mindharbor.agent.service.agent.AgentRuntimeService;
import com.mindharbor.agent.service.memory.ShortTermMemoryService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
/**
 * 学生聊天主流程服务。
 *
 * <p>负责会话落库、模型流式调用和后台报告触发；意图路由、记忆读取、RAG 与风险评估
 * 由 AgentRuntimeService 中的多 Agent loop 完成。</p>
 */
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatMessageRepository chatMessageRepository;
    private final PsychologicalReportRepository reportRepository;
    private final MindHarborProperties properties;
    private final ToolOrchestrationService toolOrchestrationService;
    private final PrivacySanitizer privacySanitizer;
    private final ShortTermMemoryService shortTermMemoryService;
    private final com.mindharbor.agent.service.memory.MemoryMaintenanceService memoryTasks;
    private final AgentRuntimeService agentRuntimeService;
    private final AgentRunTraceService agentRunTraceService;
    private final AiClient aiClient;
    private final ReplyStore replyStore;

    public ChatService(
            ChatMessageRepository chatMessageRepository,
            PsychologicalReportRepository reportRepository,
            MindHarborProperties properties,
            ToolOrchestrationService toolOrchestrationService,
            PrivacySanitizer privacySanitizer,
            ShortTermMemoryService shortTermMemoryService,
            com.mindharbor.agent.service.memory.MemoryMaintenanceService memoryTasks,
            AgentRuntimeService agentRuntimeService,
            AgentRunTraceService agentRunTraceService,
            AiClient aiClient, ReplyStore replyStore
    ) {
        this.chatMessageRepository = chatMessageRepository;
        this.reportRepository = reportRepository;
        this.properties = properties;
        this.toolOrchestrationService = toolOrchestrationService;
        this.privacySanitizer = privacySanitizer;
        this.shortTermMemoryService = shortTermMemoryService;
        this.memoryTasks = memoryTasks;
        this.agentRuntimeService = agentRuntimeService;
        this.agentRunTraceService = agentRunTraceService;
        this.aiClient = aiClient;
        this.replyStore = replyStore;
    }

    public Flux<ServerSentEvent<ChatStreamEvent>> streamChat(Long userId, ChatRequest request) {
        return Mono.fromCallable(() -> replyStore.open(userId, request))
                .subscribeOn(Schedulers.boundedElastic()).flatMapMany(this::execute);
    }

    public Flux<ServerSentEvent<ChatStreamEvent>> resume(Long userId, String requestId, Integer generation) {
        return Mono.fromCallable(() -> replyStore.resume(userId, requestId, generation))
                .subscribeOn(Schedulers.boundedElastic()).flatMapMany(this::execute);
    }

    public Mono<ReplyStore.View> status(Long userId, String requestId) {
        return Mono.fromCallable(() -> replyStore.status(userId, requestId)).subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<ServerSentEvent<ChatStreamEvent>> execute(ReplyStore.Ticket ticket) {
        if (ticket.owner() == null) {
            var v = ticket.view();
            String type = v.status() == com.mindharbor.agent.domain.ReplyStatus.SUCCESS ? "done"
                    : v.status() == com.mindharbor.agent.domain.ReplyStatus.FAILED ? "error" : "busy";
            return Flux.just(replyEvent(type, v, "error".equals(type) ? v.error() : v.content()));
        }
        // cache keeps preparation alive if the browser disconnects, allowing report/task registration.
        Mono<ReplyStore.Ticket> prepared = ticket.prepare()
                ? Mono.fromCallable(() -> prepare(ticket)).doOnError(ex -> replyStore.preparationFailure(ticket))
                        .subscribeOn(Schedulers.boundedElastic()).cache()
                : Mono.just(ticket);
        if (ticket.prepare()) prepared.subscribe(ignored -> {},
                ex -> log.warn("Reply preparation failed for {}", ticket.view().requestId()));
        Flux<ServerSentEvent<ChatStreamEvent>> main = Flux.just(replyEvent("meta", ticket.view(), ""))
                .concatWith(prepared.flatMapMany(this::generate))
                .onErrorResume(ex -> Mono.fromCallable(() -> {
                    if(ticket.prepare() && !replyStore.status(ticket.user().getId(),ticket.view().requestId()).prepared())replyStore.preparationFailure(ticket);
                    else replyStore.failure(ticket, "回复生成失败，请稍后重新生成。");
                    log.warn("Reply {} failed: {}", ticket.view().requestId(), ex.toString());
                    var state=replyStore.status(ticket.user().getId(),ticket.view().requestId());
                    return replyEvent(state.status()==com.mindharbor.agent.domain.ReplyStatus.INTERRUPTED?"busy":"error",state,
                            state.error()==null?"准备阶段等待恢复":state.error());
                }).subscribeOn(Schedulers.boundedElastic()));
        // Heartbeats cause transport failures to surface even while preparation/model work is quiet.
        return Flux.merge(main, Flux.interval(Duration.ofSeconds(5))
                        .map(n -> event("heartbeat", ChatStreamEvent.meta(ticket.view().sessionId()))))
                .takeUntil(e -> java.util.Set.of("done", "error", "busy").contains(e.event()))
                .doFinally(signal -> {
                    if (signal == reactor.core.publisher.SignalType.CANCEL)
                        Mono.fromRunnable(() -> replyStore.interrupt(ticket)).subscribeOn(Schedulers.boundedElastic())
                                .subscribe(null, ex -> log.error("Failed to persist disconnect; lease recovery will retry", ex));
                });
    }

    private ReplyStore.Ticket prepare(ReplyStore.Ticket ticket) {
        try (var scope = com.mindharbor.agent.service.ai.ModelCallContext.use(
                new com.mindharbor.agent.service.ai.ModelCallContext(ticket.view().requestId(),
                        ticket.view().sessionId(), "PREPARATION", ticket.view().generation(), 0))) {
            return prepareObserved(ticket);
        }
    }

    private ReplyStore.Ticket prepareObserved(ReplyStore.Ticket ticket) {
        UserAccount user = ticket.user(); ChatSession session = ticket.session(); String input = ticket.input();
        Instant startedAt = Instant.now();
        AgentRunResult run = agentRuntimeService.runRecoverable(ticket, privacySanitizer.sanitize(input));
        Instant completedAt = Instant.now();
        RiskLevel risk = run.riskLevel() == null ? RiskLevel.LOW : run.riskLevel();
        List<AiMessage> prompt = run.responseMessages().isEmpty()
                ? buildMessages(user, run.intent(), risk, run.retrievedKnowledge(), run.modelHistory()) : run.responseMessages();
        ReplyStore.Ticket ready = replyStore.prepared(ticket, prompt, reply -> {
            ChatMessage message = new ChatMessage(); message.setUser(user); message.setSession(session);
            message.setRole(MessageRole.USER); message.setContent(input); chatMessageRepository.save(message);
            memoryTasks.enqueue(user.getId(),session.getId(),message.getId(),reply.getId(),ticket.view().requestId(),input);
            agentRunTraceService.saveRun(user, session, message, input, run, startedAt, completedAt);
            if (run.requiresReport()) {
                var report = saveReport(user, session, input, run.intent(), run.assessment());
                toolOrchestrationService.enqueue(report.getId());
            }
        });
        shortTermMemoryService.append(session.getPublicId(), MessageRole.USER, input);
        return ready;
    }

    /** Recover preparation without generating an unseen answer; the browser resumes SSE later. */
    public void recoverPreparation(String requestId) {
        ReplyStore.Ticket ticket=replyStore.resumePreparation(requestId);
        if(ticket==null || ticket.owner()==null || !ticket.prepare())return;
        replyStore.interrupt(ticket);
        try {prepare(ticket);} catch(Exception error) {
            replyStore.preparationFailure(ticket);
            log.warn("Agent preparation recovery deferred for {}: {}",requestId,error.getClass().getSimpleName());
        }
    }

    private Flux<ServerSentEvent<ChatStreamEvent>> generate(ReplyStore.Ticket ticket) {
        if (ticket.owner() == null) return Flux.just(replyEvent("busy", ticket.view(), "等待恢复"));
        return Mono.fromCallable(() -> replyStore.attempt(ticket)).subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(attempt -> {
                    StringBuilder text = new StringBuilder();
                    Duration remaining = Duration.between(Instant.now(), attempt.deadline());
                    if (remaining.isNegative() || remaining.isZero()) return Flux.error(new IllegalStateException("Generation deadline exceeded"));
                    Flux<ServerSentEvent<ChatStreamEvent>> body = answerPart(attempt, attempt.prompt(), text, false)
                            .takeUntilOther(Mono.delay(remaining).flatMap(n -> Mono.error(new java.util.concurrent.TimeoutException("Generation deadline exceeded"))));
                    return Flux.just(replyEvent(attempt.view().attempts() == 1 ? "start" : "retry", attempt.view(),
                                    "模型响应超时或失败，正在重试"))
                            .concatWith(body)
                            .concatWith(Mono.fromCallable(() -> {
                                if (text.toString().isBlank()) throw new IllegalStateException("Empty model response");
                                if (!replyStore.success(attempt, text.toString())) throw new IllegalStateException("Execution lease lost");
                                shortTermMemoryService.append(attempt.view().sessionId(), MessageRole.ASSISTANT, text.toString());
                                return replyEvent("done", replyStore.status(attempt.user().getId(), attempt.view().requestId()), text.toString());
                            }).subscribeOn(Schedulers.boundedElastic()))
                            .onErrorResume(ex -> {
                                if (attempt.view().attempts() < ReplyStore.MAX_ATTEMPTS && retryable(ex)
                                        && Instant.now().plusSeconds(2).isBefore(attempt.deadline())) {
                                    return Flux.just(replyEvent("retry", attempt.view(), "模型响应超时或失败，正在重试"))
                                            .concatWith(Mono.delay(Duration.ofSeconds(attempt.view().attempts())).thenMany(generate(attempt)));
                                }
                                return Mono.fromCallable(() -> {
                                    String reason = ex instanceof IncompleteReplyException
                                            ? "回答未能完整收尾，请点击重新生成。"
                                            : "回复失败，请检查模型服务后点击重新生成。";
                                    replyStore.failure(attempt, reason);
                                    return replyEvent("error", replyStore.status(attempt.user().getId(), attempt.view().requestId()),
                                            reason);
                                }).subscribeOn(Schedulers.boundedElastic());
                            });
                });
    }

    private Flux<ServerSentEvent<ChatStreamEvent>> answerPart(ReplyStore.Ticket attempt,
            List<AiMessage> prompt, StringBuilder text, boolean continuation) {
        int maxTokens = continuation ? properties.getChat().getContinuationMaxTokens() : properties.getChat().getAnswerMaxTokens();
        Flux<ServerSentEvent<ChatStreamEvent>> part = Flux.defer(() -> aiClient.streamAnswer(prompt, maxTokens))
                .contextWrite(ctx -> ctx.put(com.mindharbor.agent.service.ai.ModelCallContext.KEY,
                        new com.mindharbor.agent.service.ai.ModelCallContext(attempt.view().requestId(),
                                attempt.view().sessionId(), continuation ? "FINAL_CONTINUATION" : "FINAL_RESPONSE",
                                attempt.view().generation(), attempt.view().attempts())))
                .timeout(Duration.ofSeconds(45))
                .switchIfEmpty(Flux.error(new IllegalStateException("Empty model response")))
                .doOnNext(text::append)
                .map(token -> replyEvent("token", attempt.view(), token));
        if (continuation) return part.onErrorMap(ex -> new IncompleteReplyException(ex));
        return part.onErrorResume(com.mindharbor.agent.service.ai.OutputTruncatedException.class, ex ->
                Mono.fromCallable(() -> replyStore.reserveContinuation(attempt)).subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(allowed -> {
                            if (!allowed) return Flux.error(new IncompleteReplyException(ex));
                            var next = new ArrayList<>(prompt);
                            next.add(AiMessage.assistant(text.toString()));
                            next.add(AiMessage.system("保留原有安全规则。上一条助手回答因长度限制中断。只接着补完未完成的句子或建议，并用一两句简短收尾。不要重复已有文字，不要新增建议、话题或标题，不要重新开始回答。"));
                            return Flux.just(replyEvent("continuation", attempt.view(), "回答尚未完成，正在补完并收尾…"))
                                    .concatWith(answerPart(attempt, next, text, true));
                        }));
    }

    private static final class IncompleteReplyException extends RuntimeException {
        private IncompleteReplyException(Throwable cause) { super("Incomplete answer after bounded continuation", cause); }
    }

    static boolean retryable(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause()) {
            if (e instanceof IncompleteReplyException) return false;
            if (e instanceof org.springframework.web.reactive.function.client.WebClientResponseException web) {
                int code = web.getStatusCode().value();
                return code == 408 || code == 429 || code >= 500;
            }
            String message = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (e instanceof IllegalArgumentException || message.matches("(?s).*(401|403|404|400|422|unauthorized|invalid api|not found|lease lost|deadline exceeded).*")) return false;
        }
        return true;
    }

    private ServerSentEvent<ChatStreamEvent> replyEvent(String type, ReplyStore.View view, String content) {
        return event(type, new ChatStreamEvent(type, view.sessionId(), content, null, null,
                view.requestId(), view.replyId(), view.status().name(), view.attempts(), view.generation(), view.prepared()));
    }

    private PsychologicalReport saveReport(
            UserAccount user,
            ChatSession session,
            String content,
            IntentType intent,
            PsychologyAssessment assessment
    ) {
        PsychologicalReport report = new PsychologicalReport();
        report.setUser(user);
        report.setSession(session);
        report.setContent(content);
        report.setIntent(intent);
        report.setEmotion(assessment.emotion());
        report.setEmotionScore(assessment.emotionScore());
        report.setRiskLevel(assessment.risk());
        report.setConfidence(assessment.confidence());
        report.setSummary(assessment.summary());
        return reportRepository.save(report);
    }

    private List<AiMessage> buildMessages(
            UserAccount user,
            IntentType intent,
            RiskLevel riskLevel,
            List<SearchResult> retrieved,
            List<AiMessage> history
    ) {
        // 检索片段只作为系统上下文给模型使用，不直接展示后台评估信息给学生。
        String context = String.join("\n\n", retrieved.stream()
                .map(result -> "- [" + result.source() + "] " + result.content())
                .toList());
        List<AiMessage> messages = new ArrayList<>();
        messages.add(PromptTemplates.answerSystemPrompt(intent, riskLevel, context, user.getDisplayName()));

        int limit = messageWindowLimit();
        history.stream()
                .skip(Math.max(0, history.size() - limit))
                .forEach(messages::add);
        return messages;
    }

    private int messageWindowLimit() {
        // history-limit 以轮次理解，这里乘 2 保留用户和助手两侧消息。
        return Math.max(2, properties.getChat().getHistoryLimit() * 2);
    }

    private ServerSentEvent<ChatStreamEvent> event(String name, ChatStreamEvent data) {
        return ServerSentEvent.builder(data).event(name).build();
    }

}
