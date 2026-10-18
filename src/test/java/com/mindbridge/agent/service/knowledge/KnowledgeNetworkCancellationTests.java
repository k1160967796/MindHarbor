package com.mindbridge.agent.service.knowledge;

import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.service.ai.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class KnowledgeNetworkCancellationTests {
    @Test void timedModelCallCancelsUnderlyingStream() {
        AtomicBoolean cancelled = new AtomicBoolean();
        StreamingChatModel streaming = mock(StreamingChatModel.class);
        when(streaming.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenReturn(Flux.<ChatResponse>never().doOnCancel(() -> cancelled.set(true)));
        AiClient client = new SpringAiChatClient(mock(ChatModel.class), streaming);
        assertThatThrownBy(() -> client.completeWithin(List.of(AiMessage.user("test")), Duration.ofMillis(30), Map.of(), 32))
                .isInstanceOf(IllegalStateException.class);
        assertThat(cancelled).isTrue();
    }

    @Test void timedEmbeddingCancelsUnderlyingHttpPublisher() {
        AtomicBoolean cancelled = new AtomicBoolean();
        MindBridgeProperties properties = new MindBridgeProperties();
        properties.getEmbedding().setApiKey("test-only");
        var client = new OpenAiEmbeddingClient(properties, WebClient.builder().exchangeFunction(request ->
                Mono.<org.springframework.web.reactive.function.client.ClientResponse>never()
                        .doOnCancel(() -> cancelled.set(true))));
        assertThatThrownBy(() -> client.embed("test", Duration.ofMillis(30))).isInstanceOf(IllegalStateException.class);
        assertThat(cancelled).isTrue();
    }

    @Test void chromaCollectionSetupAndQueryUseSameDeadlineAndCancel() {
        AtomicBoolean cancelled = new AtomicBoolean();
        var store = new ChromaRestStore(WebClient.builder().exchangeFunction(request ->
                Mono.<org.springframework.web.reactive.function.client.ClientResponse>never()
                        .doOnCancel(() -> cancelled.set(true))), "http://localhost:8000", "test", mock(EmbeddingClient.class));
        assertThatThrownBy(() -> store.queryVector(List.of(1.0), 4, Map.of(), Duration.ofMillis(30)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(cancelled).isTrue();
    }
}
