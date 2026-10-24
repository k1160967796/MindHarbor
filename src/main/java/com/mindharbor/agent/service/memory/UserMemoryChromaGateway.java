package com.mindharbor.agent.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.mindharbor.agent.service.knowledge.ChromaRestStore;
import com.mindharbor.agent.service.knowledge.EmbeddingClient;
import com.mindharbor.agent.config.MindHarborProperties;
import com.mindharbor.agent.domain.ChatSession;
import com.mindharbor.agent.domain.UserMemoryItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
/**
 * 用户画像记忆 Chroma 网关。
 *
 * <p>MySQL/H2 保存可审计的画像条目；Chroma 只保存语义检索索引，
 * 用于按当前输入召回相关长期记忆。</p>
 */
public class UserMemoryChromaGateway {

    private final MindHarborProperties properties;
    private final ChromaRestStore store;

    public UserMemoryChromaGateway(MindHarborProperties properties, WebClient.Builder webClientBuilder, EmbeddingClient embeddings) {
        this.properties = properties;
        this.store = new ChromaRestStore(webClientBuilder, properties.getMemory().getChromaBaseUrl(),
                properties.getMemory().getChromaCollection(), embeddings);
    }

    public void mirror(UserMemoryItem item) {
        if (!properties.getMemory().isUseChroma() || item.getId() == null) {
            return;
        }
        if(!item.currentAt(java.time.Instant.now())) {
            store.deleteStrict(Map.of("where",Map.of("$and",List.of(Map.of("memoryId",String.valueOf(item.getId())),
                    Map.of("sourceOrder",Map.of("$lte",item.getSourceOrder()))))));
            return;
        }
        store.upsertStrict(chromaId(item.getId())+"-r"+item.getSourceOrder(), document(item), metadata(item));
        // Never delete a newer index if this worker returns late after losing its lease.
        store.deleteStrict(Map.of("where",Map.of("$and",List.of(Map.of("memoryId",String.valueOf(item.getId())),
                Map.of("sourceOrder",Map.of("$lt",item.getSourceOrder()))))));
    }

    public List<UserMemoryMatch> query(Long userId, String text, int topK) {
        if (!properties.getMemory().isUseChroma() || userId == null || text == null || text.isBlank()) {
            return List.of();
        }
        return parseMatches(store.query(text, topK, Map.of("userId", String.valueOf(userId))));
    }

    public void delete(Long memoryId) {
        if (!properties.getMemory().isUseChroma() || memoryId == null) return;
        store.deleteStrict(Map.of("where", Map.of("memoryId",String.valueOf(memoryId))));
    }

    private List<UserMemoryMatch> parseMatches(JsonNode response) {
        if (response == null) {
            return List.of();
        }
        List<UserMemoryMatch> matches = new ArrayList<>();
        JsonNode metadatas = response.path("metadatas").path(0);
        JsonNode distances = response.path("distances").path(0);
        for (int i = 0; i < metadatas.size(); i++) {
            Long memoryId = parseLong(metadatas.path(i).path("memoryId").asText());
            if (memoryId != null) {
                double score = 1.0 - distances.path(i).asDouble(1.0);
                matches.add(new UserMemoryMatch(memoryId, score,metadatas.path(i).path("sourceOrder").asLong(0)));
            }
        }
        return matches;
    }

    private Map<String, Object> metadata(UserMemoryItem item) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("memoryId", String.valueOf(item.getId()));
        metadata.put("sourceOrder",item.getSourceOrder());
        metadata.put("userId", String.valueOf(item.getUser().getId()));
        metadata.put("type", item.getType().name());
        metadata.put("scope",item.getFactScope());
        metadata.put("factKey",item.getFactKey()==null?"legacy":item.getFactKey());
        ChatSession session = item.getSourceSession();
        if (session != null && session.getPublicId() != null) {
            metadata.put("sourceSessionId", session.getPublicId());
        }
        return metadata;
    }

    private String document(UserMemoryItem item) {
        String evidence = item.getEvidence() == null || item.getEvidence().isBlank()
                ? ""
                : "\n证据：" + item.getEvidence();
        return "%s/%s：%s%s".formatted(item.getType().name(),item.getFactScope(),item.getSummary(), evidence);
    }

    private String chromaId(Long memoryId) {
        return "memory:" + memoryId;
    }

    private Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    public record UserMemoryMatch(Long memoryId, double score,long sourceOrder) {
    }
}
