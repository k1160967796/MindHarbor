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
import com.mindharbor.agent.service.memory.UserProfileMemoryService;
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
    private final UserProfileMemoryService userProfileMemoryService;
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
            UserProfileMemoryService userProfileMemoryService,
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
        this.userProfileMemoryService = userProfileMemoryService;
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
                ? Mono.fromCallable(() -> prepare(ticket)).doOnError(ex -> replyStore.failure(ticket, "准备回复失败，请重新提交消息。"))
                        .subscribeOn(Schedulers.boundedElastic()).cache()
                : Mono.just(ticket);
        if (ticket.prepare()) prepared.subscribe(ignored -> {},
                ex -> log.warn("Reply preparation failed for {}", ticket.view().requestId()));
        Flux<ServerSentEvent<ChatStreamEvent>> main = Flux.just(replyEvent("meta", ticket.view(), ""))
                .concatWith(prepared.flatMapMany(this::generate))
                .onErrorResume(ex -> Mono.fromCallable(() -> {
                    replyStore.failure(ticket, "回复生成失败，请稍后重新生成。");
                    log.warn("Reply {} failed: {}", ticket.view().requestId(), ex.toString());
                    return replyEvent("error", replyStore.status(ticket.user().getId(), ticket.view().requestId()), "回复生成失败，请稍后重新生成。");
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
        UserAccount user = ticket.user(); ChatSession session = ticket.session(); String input = ticket.input();
        Instant startedAt = Instant.now();
        AgentRunResult run = agentRuntimeService.run(user, session, input, privacySanitizer.sanitize(input));
        Instant completedAt = Instant.now();
        RiskLevel risk = run.riskLevel() == null ? RiskLevel.LOW : run.riskLevel();
        List<AiMessage> prompt = run.responseMessages().isEmpty()
                ? buildMessages(user, run.intent(), risk, run.retrievedKnowledge(), run.modelHistory()) : run.responseMessages();
        ReplyStore.Ticket ready = replyStore.prepared(ticket, prompt, reply -> {
            ChatMessage message = new ChatMessage(); message.setUser(user); message.setSession(session);
            message.setRole(MessageRole.USER); message.setContent(input); chatMessageRepository.save(message);
            agentRunTraceService.saveRun(user, session, message, input, run, startedAt, completedAt);
            if (run.requiresReport()) {
                var report = saveReport(user, session, input, run.intent(), run.assessment());
                toolOrchestrationService.enqueue(report.getId());
            }
        });
        shortTermMemoryService.append(session.getPublicId(), MessageRole.USER, input);
        rememberUserProfile(user, session, input, run.memoryBrief());
        return ready;
    }

    private Flux<ServerSentEvent<ChatStreamEvent>> generate(ReplyStore.Ticket ticket) {
        if (ticket.owner() == null) return Flux.just(replyEvent("busy", ticket.view(), "等待恢复"));
        return Mono.fromCallable(() -> replyStore.attempt(ticket)).subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(attempt -> {
                    StringBuilder text = new StringBuilder();
                    Duration remaining = Duration.between(Instant.now(), attempt.deadline());
                    if (remaining.isNegative() || remaining.isZero()) return Flux.error(new IllegalStateException("Generation deadline exceeded"));
                    Flux<String> model = Flux.defer(() -> aiClient.stream(attempt.prompt()))
                            .timeout(Duration.ofSeconds(45))
                            .takeUntilOther(Mono.delay(remaining).flatMap(n -> Mono.error(new java.util.concurrent.TimeoutException("Generation deadline exceeded"))))
                            .switchIfEmpty(Flux.error(new IllegalStateException("Empty model response")));
                    Flux<ServerSentEvent<ChatStreamEvent>> body = model.doOnNext(text::append)
                            .map(token -> replyEvent("token", attempt.view(), token));
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
                                    replyStore.failure(attempt, "回复失败，请检查模型服务后点击重新生成。");
                                    return replyEvent("error", replyStore.status(attempt.user().getId(), attempt.view().requestId()),
                                            "回复失败，请检查模型服务后点击重新生成。");
                                }).subscribeOn(Schedulers.boundedElastic());
                            });
                });
    }

    static boolean retryable(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause()) {
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

    private void rememberUserProfile(UserAccount user, ChatSession session, String input, String memoryBrief) {
        try {
            userProfileMemoryService.rememberUserInput(user, session, input, memoryBrief);
        } catch (Exception exception) {
            log.debug("User profile memory update skipped: {}", exception.getMessage());
        }
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
