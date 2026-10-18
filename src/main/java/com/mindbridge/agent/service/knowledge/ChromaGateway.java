package com.mindbridge.agent.service.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.KnowledgeChunk;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
/**
 * Chroma 向量库网关。
 *
 * <p>当 use-chroma=true 时，把知识库切块镜像到外部向量库，并优先从 Chroma 检索。</p>
 */
public class ChromaGateway {

    private final MindBridgeProperties properties;
    private final ChromaRestStore store;

    public ChromaGateway(MindBridgeProperties properties, WebClient.Builder webClientBuilder, EmbeddingClient embeddings) {
        this.properties = properties;
        this.store = new ChromaRestStore(webClientBuilder, properties.getKnowledge().getChromaBaseUrl(),
                properties.getKnowledge().getChromaCollection(), embeddings);
    }

    public void mirror(KnowledgeChunk chunk) {
        if (!properties.getKnowledge().isUseChroma() || chunk.isNeedsReview()) {
            return;
        }
        // 本地数据库仍是主存储；Chroma 只是可选检索加速层。
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", chunk.getSource());
        metadata.put("sourceIndex", chunk.getSourceIndex());
        if (chunk.getPageNumber() != null) metadata.put("pageNumber", chunk.getPageNumber());
        if (chunk.getBlockType() != null) metadata.put("blockType", chunk.getBlockType());
        if (chunk.getImageRef() != null) metadata.put("imageRef", chunk.getImageRef());
        store.upsert(String.valueOf(chunk.getId()), chunk.getContent(), metadata);
    }

    public List<SearchResult> query(String text, int topK) {
        if (!properties.getKnowledge().isUseChroma()) {
            return List.of();
        }
        return parseResults(store.query(text, topK, Map.of()));
    }

    public void deleteSource(String source) {
        if (!properties.getKnowledge().isUseChroma()) return;
        store.delete(Map.of("where", Map.of("source", source)));
    }

    public List<SearchResult> queryVector(List<Double> vector, int topK, java.time.Duration timeout) {
        if (!properties.getKnowledge().isUseChroma()) return List.of();
        return parseResults(store.queryVector(vector, topK, Map.of(), timeout));
    }

    private List<SearchResult> parseResults(JsonNode response) {
        if (response == null) {
            return List.of();
        }
        List<SearchResult> results = new ArrayList<>();
        JsonNode docs = response.path("documents").path(0);
        JsonNode ids = response.path("ids").path(0);
        JsonNode metadatas = response.path("metadatas").path(0);
        JsonNode distances = response.path("distances").path(0);
        for (int i = 0; i < docs.size(); i++) {
            Long id = parseId(ids.path(i).asText());
            double score = 1.0 - distances.path(i).asDouble(1.0);
            String source = metadatas.path(i).path("source").asText("chroma");
            results.add(new SearchResult(id, source, docs.path(i).asText(), score));
        }
        return results;
    }

    private Long parseId(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

}
