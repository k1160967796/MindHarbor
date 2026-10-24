package com.mindharbor.agent.service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.mindharbor.agent.config.MindHarborProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * OpenAI 兼容 embedding 客户端。
 *
 * <p>用于把上传知识和查询文本转换成向量；未配置 API Key 时保持无副作用返回空结果。</p>
 */
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private final MindHarborProperties properties;
    private final WebClient webClient;
    private final com.mindharbor.agent.service.ai.ModelCallTelemetry telemetry;
    private final java.util.concurrent.ExecutorService warmupWorker=java.util.concurrent.Executors.newSingleThreadExecutor(r->{
        Thread thread=new Thread(r,"local-embedding-warmup");thread.setDaemon(true);return thread;
    });
    private final java.util.concurrent.atomic.AtomicBoolean warming=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicLong lastWarmup=new java.util.concurrent.atomic.AtomicLong();
    @org.springframework.beans.factory.annotation.Value("${mindharbor.embedding.local-warmup-enabled:true}")
    private boolean warmupEnabled=true;

    @jakarta.annotation.PostConstruct
    public void warmLocalModel() {
        // A cancelled 1.2s foreground call cannot finish a several-second cold model load.
        // Warm independently, once at startup or after a failure, without extending the RAG budget.
        if(!warmupEnabled || !"ollama".equalsIgnoreCase(properties.getAi().getProvider())
                || properties.getEmbedding().getApiKey().isBlank()
                || !sameOrigin(properties.getEmbedding().getBaseUrl(),properties.getAi().getOllama().getBaseUrl()))return;
        long now=System.currentTimeMillis();
        if(now-lastWarmup.get()<60000 || !warming.compareAndSet(false,true))return;
        lastWarmup.set(now);
        try {warmupWorker.execute(()->{
            try(var scope=com.mindharbor.agent.service.ai.ModelCallContext.use(new com.mindharbor.agent.service.ai.ModelCallContext(
                    null,null,"EMBEDDING_WARMUP",0,0))) {
                embed("模型预热",java.time.Duration.ofSeconds(30));
            } catch(RuntimeException ignored) {
                // Failure remains observable; foreground BM25 fallback stays available.
            } finally {warming.set(false);}
        });}catch(java.util.concurrent.RejectedExecutionException stopped){warming.set(false);}
    }
    private static boolean sameOrigin(String a,String b) {
        try {var x=java.net.URI.create(a);var y=java.net.URI.create(b);
            return java.util.Objects.equals(x.getScheme(),y.getScheme())
                    && java.util.Objects.equals(x.getHost(),y.getHost()) && x.getPort()==y.getPort();
        }catch(IllegalArgumentException invalid){return false;}
    }
    @jakarta.annotation.PreDestroy public void close(){warmupWorker.shutdownNow();}

    public OpenAiEmbeddingClient(MindHarborProperties properties, WebClient.Builder webClientBuilder) {
        this(properties, webClientBuilder, null);
    }

    public OpenAiEmbeddingClient(MindHarborProperties properties, WebClient.Builder webClientBuilder,
                                 com.mindharbor.agent.service.ai.ModelCallTelemetry telemetry) {
        this.properties = properties;
        this.telemetry = telemetry;
        WebClient.Builder builder = webClientBuilder.baseUrl(properties.getEmbedding().getBaseUrl());
        if (!properties.getEmbedding().getApiKey().isBlank()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getEmbedding().getApiKey());
        }
        this.webClient = builder.build();
    }

    @Override
    public List<Double> embed(String text) {
        return embed(text, java.time.Duration.ofSeconds(30));
    }

    @Override
    public List<Double> embed(String text, java.time.Duration timeout) {
        // API Key 为空时直接返回空向量，让 RAG 使用本地检索兜底。
        if (properties.getEmbedding().getApiKey().isBlank() || text == null || text.isBlank()) {
            return List.of();
        }
        var call = telemetry == null ? null : telemetry.start(
                com.mindharbor.agent.service.ai.ModelCallContext.current(), "openai-compatible",
                properties.getEmbedding().getModel(), "EMBEDDING");
        try {
        Map<String, Object> body = Map.of(
                "model", properties.getEmbedding().getModel(),
                "input", text,
                "encoding_format", "float"
        );
        JsonNode response = webClient.post()
                .uri("/v1/embeddings")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(timeout);
        if (call != null && response != null) {
            JsonNode usage = response.path("usage");
            if (usage.path("prompt_tokens").isIntegralNumber() && usage.path("total_tokens").isIntegralNumber()
                    && usage.path("prompt_tokens").asLong() >= 0 && usage.path("total_tokens").asLong() > 0)
                call.usage(usage.path("prompt_tokens").asLong(), 0L, usage.path("total_tokens").asLong());
        }
        JsonNode embedding = response == null
                ? null
                : response.path("data").path(0).path("embedding");
        // 服务端响应异常时不抛给业务层，返回空结果交给 KnowledgeService 回退。
        if (embedding == null || !embedding.isArray()) {
            if (call != null) call.finish("EMPTY", null);
            return List.of();
        }
        List<Double> values = new ArrayList<>(embedding.size());
        embedding.forEach(value -> values.add(value.asDouble()));
        if (call != null) call.finish(values.isEmpty() ? "EMPTY" : "SUCCESS", null);
        return values;
        } catch (RuntimeException ex) { if (call != null) call.error(ex); warmLocalModel(); throw ex; }
    }

    @Override
    public String modelName() {
        return properties.getEmbedding().getModel();
    }
}
