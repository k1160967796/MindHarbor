package com.mindharbor.agent.service.ai;

import com.mindharbor.agent.domain.ModelCallRecord;
import com.mindharbor.agent.repository.ModelCallRecordRepository;
import com.mindharbor.agent.config.MindHarborProperties;
import com.mindharbor.agent.service.knowledge.OpenAiEmbeddingClient;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.ollama.*;
import org.springframework.ai.ollama.api.*;
import org.springframework.web.reactive.function.client.WebClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Spring AI 1.0.0 adapters against local protocol fixtures; no external model or mail. */
class SpringAiProviderUsageTests {
    final List<ModelCallRecord> rows = new CopyOnWriteArrayList<>();
    final AtomicReference<String> request = new AtomicReference<>();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    HttpServer server;
    ModelCallTelemetry telemetry;
    @BeforeEach void setup() throws Exception {
        var repository=mock(ModelCallRecordRepository.class);
        when(repository.save(any())).thenAnswer(c->{rows.add(c.getArgument(0));return c.getArgument(0);});
        telemetry=new ModelCallTelemetry(repository, meters);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.start();
    }
    @AfterEach void stop() {server.stop(0);telemetry.close();meters.close();}
    String url() {return "http://127.0.0.1:"+server.getAddress().getPort();}
    void serve(String path, String contentType, String response) {
        server.createContext(path, exchange->{
            request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type",contentType);
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
    }
    @Test void openAiUsageOnlyTailSurvivesRealAdapter() {
        serve("/v1/chat/completions","text/event-stream", """
                data: {"id":"fixture","object":"chat.completion.chunk","created":1,"model":"fixture","choices":[{"index":0,"delta":{"role":"assistant","content":"hello"},"finish_reason":null}]}

                data: {"id":"fixture","object":"chat.completion.chunk","created":1,"model":"fixture","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: {"id":"fixture","object":"chat.completion.chunk","created":1,"model":"fixture","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}

                data: [DONE]

                """);
        var model=OpenAiChatModel.builder().openAiApi(OpenAiApi.builder().baseUrl(url()).apiKey("fixture-not-secret").build())
                .defaultOptions(OpenAiChatOptions.builder().model("fixture").streamUsage(true).build()).build();
        var client=new SpringAiChatClient(model,model,telemetry,"openai","fixture");
        assertThat(client.stream(List.of(AiMessage.user("test"))).collectList().block(Duration.ofSeconds(10))).contains("hello");
        telemetry.close(); assertThat(rows).hasSize(1);
        assertThat(rows.get(0).totalTokens).isEqualTo(12);
        assertThat(rows.get(0).inputTokens).isEqualTo(10);
        assertThat(rows.get(0).finishReason).isEqualTo("stop");
        assertThat(request.get()).contains("include_usage");
    }
    @Test void ollamaFinalEvaluationCountsAreRecorded() {
        serve("/api/chat","application/x-ndjson", """
                {"model":"fixture","created_at":"2026-10-22T00:00:00Z","message":{"role":"assistant","content":"hello"},"done":false}
                {"model":"fixture","created_at":"2026-10-22T00:00:00Z","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","prompt_eval_count":9,"eval_count":2,"total_duration":1000000,"load_duration":1,"prompt_eval_duration":2,"eval_duration":3}
                """);
        var model=OllamaChatModel.builder().ollamaApi(OllamaApi.builder().baseUrl(url()).build())
                .defaultOptions(OllamaOptions.builder().model("fixture").build()).build();
        var client=new SpringAiChatClient(model,model,telemetry,"ollama","fixture");
        assertThat(client.stream(List.of(AiMessage.user("test"))).collectList().block(Duration.ofSeconds(10))).contains("hello");
        telemetry.close(); assertThat(rows).hasSize(1); assertThat(rows.get(0).totalTokens).isEqualTo(11);
        assertThat(rows.get(0).inputTokens).isEqualTo(9);assertThat(rows.get(0).outputTokens).isEqualTo(2);
        assertThat(rows.get(0).finishReason).isEqualTo("stop");
    }
    @Test void ollamaLengthFinishSignalsTruncationAndUsesAnswerBudget() {
        serve("/api/chat","application/x-ndjson", """
                {"model":"fixture","created_at":"2026-10-22T00:00:00Z","message":{"role":"assistant","content":"half"},"done":false}
                {"model":"fixture","created_at":"2026-10-22T00:00:00Z","message":{"role":"assistant","content":""},"done":true,"done_reason":"length","prompt_eval_count":9,"eval_count":1024}
                """);
        var model=OllamaChatModel.builder().ollamaApi(OllamaApi.builder().baseUrl(url()).build())
                .defaultOptions(OllamaOptions.builder().model("fixture").numPredict(512).build()).build();
        var client=new SpringAiChatClient(model,model,telemetry,"ollama","fixture");
        reactor.test.StepVerifier.create(client.streamAnswer(List.of(AiMessage.user("test")),1024))
                .expectNext("half").expectError(OutputTruncatedException.class).verify(Duration.ofSeconds(10));
        telemetry.close(); assertThat(rows).hasSize(1);
        assertThat(rows.get(0).finishReason).isEqualTo("length");
        assertThat(rows.get(0).truncated).isTrue(); assertThat(rows.get(0).outcome).isEqualTo("TRUNCATED");
        assertThat(request.get()).contains("\"num_predict\":1024");
    }

    @Test void openAiLengthFinishSurvivesUsageOnlyTail() {
        serve("/v1/chat/completions","text/event-stream", """
                data: {"id":"fixture","object":"chat.completion.chunk","created":1,"model":"fixture","choices":[{"index":0,"delta":{"role":"assistant","content":"half"},"finish_reason":null}]}

                data: {"id":"fixture","object":"chat.completion.chunk","created":1,"model":"fixture","choices":[{"index":0,"delta":{},"finish_reason":"length"}]}

                data: {"id":"fixture","object":"chat.completion.chunk","created":1,"model":"fixture","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":1024,"total_tokens":1034}}

                data: [DONE]

                """);
        var model=OpenAiChatModel.builder().openAiApi(OpenAiApi.builder().baseUrl(url()).apiKey("fixture").build())
                .defaultOptions(OpenAiChatOptions.builder().model("fixture").streamUsage(true).build()).build();
        var client=new SpringAiChatClient(model,model,telemetry,"openai","fixture");
        reactor.test.StepVerifier.create(client.streamAnswer(List.of(AiMessage.user("test")),1024))
                .expectNext("half").expectError(OutputTruncatedException.class).verify(Duration.ofSeconds(10));
        telemetry.close(); assertThat(rows.get(0).totalTokens).isEqualTo(1034);
        assertThat(rows.get(0).finishReason).isEqualTo("length");
        assertThat(request.get()).contains("1024");
    }
    @Test void internalStructuredCallRejectsLengthFinishEvenWhenPartialTextLooksLikeJson() {
        serve("/api/chat","application/x-ndjson", """
                {"model":"fixture","message":{"role":"assistant","content":"[]"},"done":false}
                {"model":"fixture","message":{"role":"assistant","content":""},"done":true,"done_reason":"length","prompt_eval_count":9,"eval_count":1000}
                """);
        var model=OllamaChatModel.builder().ollamaApi(OllamaApi.builder().baseUrl(url()).build())
                .defaultOptions(OllamaOptions.builder().model("fixture").build()).build();
        var client=new SpringAiChatClient(model,model,telemetry,"ollama","fixture");
        assertThatThrownBy(()->client.completeWithin(List.of(AiMessage.user("test")),Duration.ofSeconds(10),
                Map.of("type","array"),1000)).isInstanceOf(OutputTruncatedException.class);
        telemetry.close();assertThat(rows).hasSize(1);
        assertThat(rows.get(0).outcome).isEqualTo("TRUNCATED");
        assertThat(rows.get(0).totalTokens).isEqualTo(1009);
    }
    @Test void embeddingUsesUsageAndMissingMetadataRemainsUnknown() {
        serve("/v1/embeddings","application/json", """
                {"data":[{"embedding":[0.1,0.2],"index":0}],"usage":{"prompt_tokens":7,"total_tokens":7}}
                """);
        var properties=new MindHarborProperties();properties.getEmbedding().setApiKey("fixture");
        properties.getEmbedding().setBaseUrl(url());properties.getEmbedding().setModel("fixture");
        var client=new OpenAiEmbeddingClient(properties,WebClient.builder(),telemetry);
        try(var scope=ModelCallContext.use(new ModelCallContext("embedding-request","session","KNOWLEDGE_AGENT",0,0))) {
            assertThat(client.embed("test")).containsExactly(0.1,0.2);
        }
        server.removeContext("/v1/embeddings");
        serve("/v1/embeddings","application/json","{\"data\":[{\"embedding\":[0.2]}]}");
        client.embed("test");telemetry.close();assertThat(rows).hasSize(2);
        assertThat(rows.get(0).requestId).isEqualTo("embedding-request");assertThat(rows.get(0).totalTokens).isEqualTo(7);
        assertThat(rows.get(1).requestId).isNull(); assertThat(rows.get(1).totalTokens).isNull();
    }
}
