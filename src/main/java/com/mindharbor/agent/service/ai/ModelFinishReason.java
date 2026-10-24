package com.mindharbor.agent.service.ai;

import org.springframework.ai.chat.model.ChatResponse;

public final class ModelFinishReason {
    private ModelFinishReason() {}
    public static String from(ChatResponse response) {
        if (response == null || response.getResult() == null) return null;
        String reason = response.getResult().getMetadata().getFinishReason();
        return reason == null || reason.isBlank() ? null : reason.toLowerCase(java.util.Locale.ROOT);
    }
    public static boolean truncated(String reason) { return "length".equals(reason) || "max_tokens".equals(reason); }
}
