package com.mindharbor.agent.service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

/** Chroma v2 REST requires collection UUIDs and client-generated embeddings. */
public final class ChromaRestStore {
    private static final Logger log = LoggerFactory.getLogger(ChromaRestStore.class);
    private static final String ROOT = "/api/v2/tenants/default_tenant/databases/default_database/collections";
    private final WebClient client;
    private final EmbeddingClient embeddings;
    private final String collectionName;
    private volatile String collectionId;

    public ChromaRestStore(WebClient.Builder builder, String baseUrl, String collectionName,
                           EmbeddingClient embeddings) {
        this.client = builder.clone().baseUrl(baseUrl).build();
        this.collectionName = collectionName;
        this.embeddings = embeddings;
    }

    public void upsert(String id, String document, Map<String, Object> metadata) {
        try {
            List<Double> vector = embeddings.embed(document);
            if (vector.isEmpty()) return;
            post("/upsert", Map.of("ids", List.of(id), "documents", List.of(document),
                    "metadatas", List.of(metadata), "embeddings", List.of(vector)));
        } catch (Exception error) {
            log.warn("Chroma upsert failed for collection {}; DB content retained ({})",
                    collectionName, error.getClass().getSimpleName());
        }
    }

    public JsonNode query(String text, int topK, Map<String, Object> where) {
        try {
            List<Double> vector = embeddings.embed(text);
            if (vector.isEmpty()) return null;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query_embeddings", List.of(vector));
            body.put("n_results", Math.max(1, topK));
            body.put("include", List.of("documents", "metadatas", "distances"));
            if (where != null && !where.isEmpty()) body.put("where", where);
            return post("/query", body);
        } catch (Exception error) {
            log.warn("Chroma query failed for collection {}; using local retrieval ({})",
                    collectionName, error.getClass().getSimpleName());
            return null;
        }
    }

    public void delete(Map<String, Object> filter) {
        try {
            post("/delete", filter);
        } catch (Exception error) {
            log.warn("Chroma delete failed for collection {} ({})",
                    collectionName, error.getClass().getSimpleName());
        }
    }

    /** 查询向量由调用方提供；集合初始化和查询共享一次总网络超时。 */
    public JsonNode queryVector(List<Double> vector, int topK, Map<String, Object> where, Duration timeout) {
        if (vector == null || vector.isEmpty()) return null;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query_embeddings", List.of(vector));
        body.put("n_results", Math.max(1, topK));
        body.put("include", List.of("documents", "metadatas", "distances"));
        if (where != null && !where.isEmpty()) body.put("where", where);
        reactor.core.publisher.Mono<String> collection = collectionId != null
                ? reactor.core.publisher.Mono.just(collectionId)
                : client.post().uri(ROOT).bodyValue(Map.of("name", collectionName, "get_or_create", true,
                        "configuration", Map.of("hnsw", Map.of("space", "cosine"))))
                    .retrieve().bodyToMono(JsonNode.class).map(node -> {
                        String id = node.path("id").asText();
                        if (id.isBlank()) throw new IllegalStateException("Chroma collection ID missing");
                        collectionId = id;
                        return id;
                    });
        return collection.flatMap(id -> client.post().uri(ROOT + "/" + id + "/query")
                .bodyValue(body).retrieve().bodyToMono(JsonNode.class)).block(timeout);
    }

    private JsonNode post(String operation, Object body) {
        return client.post().uri(ROOT + "/" + collectionId() + operation).bodyValue(body)
                .retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(30));
    }

    private synchronized String collectionId() {
        if (collectionId != null) return collectionId;
        JsonNode collection = client.post().uri(ROOT)
                .bodyValue(Map.of("name", collectionName, "get_or_create", true,
                        "configuration", Map.of("hnsw", Map.of("space", "cosine"))))
                .retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(30));
        String id = collection == null ? "" : collection.path("id").asText();
        if (id.isBlank()) throw new IllegalStateException("Chroma did not return a collection ID");
        collectionId = id;
        return id;
    }
}
