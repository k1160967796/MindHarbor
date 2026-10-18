package com.mindharbor.agent.dto;

import com.mindharbor.agent.domain.ChatMessage;
import com.mindharbor.agent.domain.ChatSession;
import java.time.Instant;
import java.util.List;

/**
 * 管理员查看完整会话的响应体。
 */
public record ConversationResponse(
        String sessionId,
        String title,
        Long userId,
        String username,
        String displayName,
        Instant createdAt,
        Instant updatedAt,
        List<ConversationMessageResponse> messages,
        List<AgentRunTraceResponse> runTraces
) {
    public static ConversationResponse from(
            ChatSession session,
            List<ChatMessage> messages,
            List<AgentRunTraceResponse> runTraces
    ) {
        return new ConversationResponse(
                session.getPublicId(),
                session.getTitle(),
                session.getUser().getId(),
                session.getUser().getUsername(),
                session.getUser().getDisplayName(),
                session.getCreatedAt(),
                session.getUpdatedAt(),
                messages.stream()
                        .map(ConversationMessageResponse::from)
                        .toList(),
                runTraces);
    }
}
