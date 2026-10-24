package com.mindharbor.agent.service.ai;

import com.mindharbor.agent.domain.ModelCallRecord;
import com.mindharbor.agent.repository.ModelCallRecordRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelCallTelemetryTests {
    final List<ModelCallRecord> saved = new CopyOnWriteArrayList<>();
    final ModelCallRecordRepository repository = mock(ModelCallRecordRepository.class);
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final ChatModel sync = mock(ChatModel.class);
    final StreamingChatModel streaming = mock(StreamingChatModel.class);
    ModelCallTelemetry telemetry;
    SpringAiChatClient client;
    @BeforeEach void setup() {
        when(repository.save(any())).thenAnswer(c -> { saved.add(c.getArgument(0)); return c.getArgument(0); });
        telemetry = new ModelCallTelemetry(repository, meters);
        client = new SpringAiChatClient(sync, streaming, telemetry, "test", "fixture-model");
    }
    @AfterEach void close() { telemetry.close(); meters.close(); }
    static ChatResponse response(String text, int input, int output) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().model("fixture-model").usage(new DefaultUsage(input, output)).build());
    }
    static ModelCallContext context(String id) { return new ModelCallContext(id, "session", "FINAL_RESPONSE", 2, 3); }
    List<ModelCallRecord> finish() { telemetry.close(); return saved; }

    @Test void streamCountsCumulativeUsageOnceIncludingUsageOnlyTail() {
        when(streaming.stream(any(Prompt.class))).thenReturn(Flux.just(response("你",0,0),
                response("好",10,2), response("",10,2)));
        var text = client.stream(List.of(AiMessage.user("private-input")))
                .contextWrite(c -> c.put(ModelCallContext.KEY, context("request-1"))).collectList().block();
        assertThat(text).containsExactly("你", "好");
        var rows = finish(); assertThat(rows).hasSize(1);
        var r = rows.get(0);
        assertThat(r.requestId).isEqualTo("request-1"); assertThat(r.attempt).isEqualTo(3);
        assertThat(r.inputTokens).isEqualTo(10); assertThat(r.outputTokens).isEqualTo(2);
        assertThat(r.totalTokens).isEqualTo(12); assertThat(r.firstTokenMs).isNotNull();
        assertThat(r.usageSource).isEqualTo("PROVIDER"); assertThat(r.outcome).isEqualTo("SUCCESS");
        assertThat(meters.get("mindharbor.model.tokens.total").counter().count()).isEqualTo(12);
        assertThat(meters.getMeters()).allSatisfy(m -> assertThat(m.getId().getTags().toString()).doesNotContain("request-1", "private-input"));
    }
    @Test void missingUsageIsNullNotZeroAndSyncHasNoFirstToken() {
        when(sync.call(any(Prompt.class))).thenReturn(response("reply",0,0));
        try(var scope=ModelCallContext.use(context("request-2").phase("MEMORY_AGENT"))) {
            assertThat(client.complete(List.of())).isEqualTo("reply");
        }
        var r=finish().get(0);
        assertThat(r.inputTokens).isNull(); assertThat(r.totalTokens).isNull();
        assertThat(r.firstTokenMs).isNull(); assertThat(r.phase).isEqualTo("MEMORY_AGENT");
        assertThat(ModelCallContext.current().requestId()).isNull();
    }
    @Test void errorDoesNotStoreSensitiveExceptionMessage() throws Exception {
        when(sync.call(any(Prompt.class))).thenThrow(new IllegalStateException("secret student text api-key"));
        assertThatThrownBy(() -> client.complete(List.of())).isInstanceOf(IllegalStateException.class);
        var r=finish().get(0); assertThat(r.outcome).isEqualTo("ERROR");
        assertThat(r.errorType).isEqualTo("IllegalStateException");
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(r))
                .doesNotContain("secret student", "api-key");
    }
    @Test void retryCreatesSeparateCallsAndErrorsRemainCounted() {
        when(streaming.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("first")),
                Flux.just(response("second",3,2)));
        assertThat(client.stream(List.of()).retry(1).collectList().block()).containsExactly("second");
        assertThat(finish()).extracting(r -> r.outcome).containsExactly("ERROR", "SUCCESS");
        assertThat(saved).extracting(r -> r.id).doesNotHaveDuplicates();
    }
    @Test void cancellationRecordsPartialCallOnceWithoutInventingUsage() {
        when(streaming.stream(any(Prompt.class))).thenReturn(Flux.concat(Flux.just(response("partial",0,0)), Flux.never()));
        StepVerifier.create(client.stream(List.of())).expectNext("partial").thenCancel().verify();
        var rows=finish(); assertThat(rows).hasSize(1);
        assertThat(rows.get(0).outcome).isEqualTo("CANCELLED"); assertThat(rows.get(0).totalTokens).isNull();
    }
    @Test void internalDeadlineRecordsTimeoutRatherThanCancellation() {
        when(streaming.stream(any(Prompt.class))).thenReturn(Flux.never());
        assertThatThrownBy(() -> client.completeWithin(List.of(), Duration.ofMillis(40), Map.of(), 10))
                .isInstanceOf(IllegalStateException.class);
        assertThat(finish()).extracting(r -> r.outcome).containsExactly("TIMEOUT");
    }
    @Test void concurrentStreamsKeepTheirRequestIdentity() {
        when(streaming.stream(any(Prompt.class))).thenReturn(Flux.just(response("ok",1,1)).delayElements(Duration.ofMillis(10)));
        var a=client.stream(List.of()).contextWrite(c -> c.put(ModelCallContext.KEY, context("A")));
        var b=client.stream(List.of()).contextWrite(c -> c.put(ModelCallContext.KEY, context("B")));
        Flux.merge(a,b).blockLast();
        assertThat(finish()).extracting(r -> r.requestId).containsExactlyInAnyOrder("A", "B");
        assertThat(ModelCallContext.current().requestId()).isNull();
    }
    @Test void metadataBeforeTextIsNotFirstTokenAndEmptyIsNotSuccess() {
        when(streaming.stream(any(Prompt.class))).thenReturn(Flux.just(response("",2,0)));
        client.stream(List.of()).blockLast();
        var r=finish().get(0); assertThat(r.firstTokenMs).isNull(); assertThat(r.outcome).isEqualTo("EMPTY");
    }
    @Test void brokenObservationStorageDoesNotBreakChat() {
        when(repository.save(any())).thenThrow(new IllegalStateException("database offline"));
        when(sync.call(any(Prompt.class))).thenReturn(response("answer",2,2));
        assertThat(client.complete(List.of())).isEqualTo("answer"); finish();
        assertThat(meters.get("mindharbor.model.persistence.dropped").tag("reason","database").counter().count()).isEqualTo(1);
    }
}
