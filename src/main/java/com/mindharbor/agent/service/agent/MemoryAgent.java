package com.mindharbor.agent.service.agent;

import com.mindharbor.agent.config.MindHarborProperties;
import com.mindharbor.agent.domain.ChatMessage;
import com.mindharbor.agent.domain.ChatSession;
import com.mindharbor.agent.repository.ChatMessageRepository;
import com.mindharbor.agent.service.PrivacySanitizer;
import com.mindharbor.agent.service.ai.AiClient;
import com.mindharbor.agent.service.ai.AiMessage;
import com.mindharbor.agent.service.memory.ShortTermMemoryService;
import com.mindharbor.agent.service.memory.ShortTermMemoryService.MemoryMessage;
import com.mindharbor.agent.service.memory.UserProfileMemoryService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 记忆 Agent。
 *
 * <p>优先读取 Redis 短期记忆和用户画像；短期记忆过期时，从 MySQL 长期聊天记录恢复最近上下文。</p>
 */
@Component
public class MemoryAgent implements MindHarborAgent {

    private final ChatMessageRepository chatMessageRepository;
    private final ShortTermMemoryService shortTermMemoryService;
    private final MindHarborProperties properties;
    private final PrivacySanitizer privacySanitizer;
    private final UserProfileMemoryService userProfileMemoryService;
    private com.mindharbor.agent.service.memory.ConversationSummaryService summaries;

    @org.springframework.beans.factory.annotation.Autowired
    public void setSummaries(com.mindharbor.agent.service.memory.ConversationSummaryService summaries) {this.summaries=summaries;}

    public MemoryAgent(
            ChatMessageRepository chatMessageRepository,
            ShortTermMemoryService shortTermMemoryService,
            MindHarborProperties properties,
            PrivacySanitizer privacySanitizer,
            AiClient aiClient,
            UserProfileMemoryService userProfileMemoryService
    ) {
        this.chatMessageRepository = chatMessageRepository;
        this.shortTermMemoryService = shortTermMemoryService;
        this.properties = properties;
        this.privacySanitizer = privacySanitizer;
        this.userProfileMemoryService = userProfileMemoryService;
    }

    @Override
    public AgentName name() {
        return AgentName.MEMORY_AGENT;
    }

    @Override
    public boolean supports(AgentContext context) {
        return !context.memoryLoaded();
    }

    @Override
    public AgentDecision act(AgentContext context) {
        List<MemoryMessage> redisHistory = shortTermMemoryService.recent(context.session().getPublicId());
        List<AiMessage> previousHistory;
        String source;
        if (!redisHistory.isEmpty()) {
            previousHistory = redisHistory.stream()
                    .map(this::toAiMessage)
                    .toList();
            source = "Redis";
        } else {
            List<ChatMessage> databaseHistory = recentHistory(context.session());
            shortTermMemoryService.refresh(context.session().getPublicId(), databaseHistory.stream()
                    .map(message -> new MemoryMessage(message.getRole(), message.getContent()))
                    .toList());
            previousHistory = databaseHistory.stream()
                    .map(this::toAiMessage)
                    .toList();
            source = "MySQL";
        }

        String profileBrief = userProfileMemoryService.profileBrief(context.user(), context.modelInput());
        var recall=summaries==null ? com.mindharbor.agent.service.memory.ConversationSummaryService.fallback(previousHistory)
                : summaries.read(context.session().getId(),context.modelInput(),previousHistory);
        String historyBrief = recall.brief();
        context.setPreviousHistory(previousHistory);
        context.setModelHistory(withCurrentUser(recall.history(), context.modelInput()));
        context.setMemoryBrief(combineMemoryBrief(profileBrief, historyBrief));
        context.markMemoryLoaded();
        return AgentDecision.continueWith(
                AgentAction.READ_MEMORY,
                "%s loaded %d messages; memory=%s; summaryModelCalls=0".formatted(source, previousHistory.size(),recall.source()));
    }

    private List<ChatMessage> recentHistory(ChatSession session) {
        if (session.getId() == null) {
            return List.of();
        }
        List<ChatMessage> history = new ArrayList<>(chatMessageRepository.findMemoryHistory(
                session.getId(), org.springframework.data.domain.PageRequest.of(0, 20)));
        Collections.reverse(history);
        return history;
    }

    private List<AiMessage> withCurrentUser(List<AiMessage> previousHistory, String currentInput) {
        List<AiMessage> history = new ArrayList<>(previousHistory);
        history.add(AiMessage.user(currentInput));
        int limit = Math.max(2, properties.getChat().getHistoryLimit() * 2);
        return history.stream()
                .skip(Math.max(0, history.size() - limit))
                .toList();
    }

    private String combineMemoryBrief(String profileBrief, String historyBrief) {
        boolean hasProfile = profileBrief != null && !profileBrief.equals("无已保存用户画像。");
        boolean hasHistory = historyBrief != null && !historyBrief.equals("无相关历史记忆。");
        if (!hasProfile && !hasHistory) {
            return "无相关历史记忆。";
        }
        if (hasProfile && hasHistory) {
            return """
                    用户画像：
                    %s

                    最近对话记忆：
                    %s
                    """.formatted(profileBrief, historyBrief).trim();
        }
        if (hasProfile) {
            return "用户画像：\n" + profileBrief;
        }
        return "最近对话记忆：\n" + historyBrief;
    }

    private String formatHistory(List<AiMessage> history) {
        return String.join("\n", history.stream()
                .skip(Math.max(0, history.size() - 12))
                .map(message -> message.role() + ": " + message.content())
                .toList());
    }

    private String shorten(String value, int maxLength) {
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    private AiMessage toAiMessage(ChatMessage chatMessage) {
        String content = privacySanitizer.sanitize(chatMessage.getContent());
        return switch (chatMessage.getRole()) {
            case ASSISTANT -> AiMessage.assistant(content);
            case SYSTEM -> AiMessage.system(content);
            case USER -> AiMessage.user(content);
        };
    }

    private AiMessage toAiMessage(MemoryMessage memoryMessage) {
        String content = privacySanitizer.sanitize(memoryMessage.content());
        return switch (memoryMessage.role()) {
            case ASSISTANT -> AiMessage.assistant(content);
            case SYSTEM -> AiMessage.system(content);
            case USER -> AiMessage.user(content);
        };
    }
}
