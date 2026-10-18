package com.mindharbor.agent.dto;

import com.mindharbor.agent.domain.ChatMessage;
import com.mindharbor.agent.domain.MessageRole;
import java.time.Instant;

/**
 * 管理员查看完整会话时的单条消息响应。
 */
public record ConversationMessageResponse(
        Long id,
        MessageRole role,
        String content,
        Instant createdAt,
        com.mindharbor.agent.domain.ReplyStatus replyStatus,
        String requestId, int attempts
) {
    public static ConversationMessageResponse from(ChatMessage message) {
        return new ConversationMessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                message.getCreatedAt(), message.getReplyStatus(), message.getRequestId(), message.getAttempts());
    }
}
