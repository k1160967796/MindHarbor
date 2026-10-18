package com.mindharbor.agent.dto;

import com.mindharbor.agent.domain.UserMemoryItem;
import com.mindharbor.agent.domain.UserMemoryType;
import java.time.Instant;

/**
 * 用户画像记忆响应。
 */
public record UserMemoryItemResponse(
        Long id,
        UserMemoryType type,
        String summary,
        String evidence,
        double confidence,
        Instant createdAt,
        Instant updatedAt,
        Instant lastSeenAt
) {
    public static UserMemoryItemResponse from(UserMemoryItem item) {
        return new UserMemoryItemResponse(
                item.getId(),
                item.getType(),
                item.getSummary(),
                item.getEvidence(),
                item.getConfidence(),
                item.getCreatedAt(),
                item.getUpdatedAt(),
                item.getLastSeenAt());
    }
}
