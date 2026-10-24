package com.mindharbor.agent.service.ai;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 基于 Spring AI 的聊天模型适配器。
 *
 * <p>项目内部继续使用简洁的 {@link AiClient} 接口；真正的大模型调用由
 * Spring AI 的 ChatModel / StreamingChatModel 完成。</p>
 */
public class SpringAiChatClient implements AiClient {

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final ModelCallTelemetry telemetry;
    private final String provider;
    private final String model;

    public SpringAiChatClient(ChatModel chatModel, StreamingChatModel streamingChatModel) {
        this(chatModel, streamingChatModel, null, "unknown", "unknown");
    }

    public SpringAiChatClient(ChatModel chatModel, StreamingChatModel streamingChatModel,
                              ModelCallTelemetry telemetry, String provider, String model) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.telemetry = telemetry; this.provider = provider; this.model = model;
    }

    @Override
    public String complete(List<AiMessage> messages) {
        var call = start(ModelCallContext.current(), "CHAT_SYNC");
        try {
            ChatResponse response = chatModel.call(new Prompt(toSpringMessages(messages)));
            if (call != null) { call.response(response, false); call.success(); }
            return extractText(response);
        } catch (RuntimeException ex) { if (call != null) call.error(ex); throw ex; }
    }

    @Override
    public Flux<String> stream(List<AiMessage> messages) {
        return observedStream(new Prompt(toSpringMessages(messages)), "CHAT_STREAM");
    }

    @Override
    public Flux<String> streamAnswer(List<AiMessage> messages, int maxTokens) {
        org.springframework.ai.chat.prompt.ChatOptions options = chatModel instanceof org.springframework.ai.ollama.OllamaChatModel
                ? org.springframework.ai.ollama.api.OllamaOptions.builder().numPredict(maxTokens).build()
                : org.springframework.ai.chat.prompt.ChatOptions.builder().maxTokens(maxTokens).build();
        return observedStream(new Prompt(toSpringMessages(messages), options), "CHAT_STREAM", true);
    }

    @Override
    public String completeWithin(List<AiMessage> messages, java.time.Duration timeout,
                                 java.util.Map<String, Object> schema, int maxTokens) {
        org.springframework.ai.chat.prompt.ChatOptions options;
        if (chatModel instanceof org.springframework.ai.ollama.OllamaChatModel) {
            var builder = org.springframework.ai.ollama.api.OllamaOptions.builder()
                    .temperature(0.0).numPredict(maxTokens);
            if (schema != null && !schema.isEmpty()) builder.format(schema);
            options = builder.build();
        } else {
            options = org.springframework.ai.chat.prompt.ChatOptions.builder()
                    .temperature(0.0).maxTokens(maxTokens).build();
        }
        var call = start(ModelCallContext.current(), "CHAT_INTERNAL");
        var finish = new java.util.concurrent.atomic.AtomicReference<String>();
        try {
            String answer = Flux.defer(() -> streamingChatModel.stream(new Prompt(toSpringMessages(messages), options)))
                    .doOnNext(response -> {
                        String reason=ModelFinishReason.from(response);
                        if(reason!=null)finish.set(reason);
                        if (call != null) call.response(response, true);
                    })
                    .map(this::extractText).reduce(new StringBuilder(), StringBuilder::append)
                    .map(StringBuilder::toString).block(timeout);
            if (call != null) call.success();
            if(ModelFinishReason.truncated(finish.get()))throw new OutputTruncatedException();
            return answer;
        } catch (RuntimeException ex) {
            if (call != null) call.error(ex);
            throw ex;
        }
    }

    private ModelCallTelemetry.Call start(ModelCallContext context, String operation) {
        return telemetry == null ? null : telemetry.start(context, provider, model, operation);
    }

    private Flux<String> observedStream(Prompt prompt, String operation) {
        return observedStream(prompt, operation, false);
    }

    private Flux<String> observedStream(Prompt prompt, String operation, boolean checkFinish) {
        // Capture sync Agent scope at assembly; Reactor Context takes precedence for final/retried replies.
        ModelCallContext captured = ModelCallContext.current();
        return Flux.deferContextual(context -> {
            var call = start(context.getOrDefault(ModelCallContext.KEY, captured), operation);
            var finish = new java.util.concurrent.atomic.AtomicReference<String>();
            Flux<ChatResponse> responses = Flux.defer(() -> streamingChatModel.stream(prompt));
            return responses.doOnNext(response -> {
                        String reason = ModelFinishReason.from(response);
                        if (reason != null) finish.set(reason);
                        if (call != null) call.response(response, true);
                    })
                    .doOnComplete(() -> { if (call != null) call.success(); })
                    .doOnError(error -> { if (call != null) call.error(error); })
                    .doFinally(signal -> {
                        if (call != null && signal == reactor.core.publisher.SignalType.CANCEL) call.finish("CANCELLED", null);
                    })
                    .map(this::extractText).filter(token -> !token.isEmpty())
                    .concatWith(Flux.defer(() -> checkFinish && ModelFinishReason.truncated(finish.get())
                            ? Flux.error(new OutputTruncatedException()) : Flux.empty()));
        });
    }

    private List<Message> toSpringMessages(List<AiMessage> messages) {
        return messages.stream()
                .map(this::toSpringMessage)
                .toList();
    }

    private Message toSpringMessage(AiMessage message) {
        return switch (message.role()) {
            case "system" -> new SystemMessage(message.content());
            case "assistant" -> new AssistantMessage(message.content());
            default -> new UserMessage(message.content());
        };
    }

    private String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }
}
