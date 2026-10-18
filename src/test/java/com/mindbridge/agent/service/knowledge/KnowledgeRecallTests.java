package com.mindbridge.agent.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.KnowledgeChunk;
import com.mindbridge.agent.repository.KnowledgeChunkRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeRecallTests {
    MindBridgeProperties properties = new MindBridgeProperties();
    KnowledgeChunkRepository repository = mock(KnowledgeChunkRepository.class);
    ChromaGateway chroma = mock(ChromaGateway.class);
    EmbeddingClient embeddings = mock(EmbeddingClient.class);
    KnowledgeService service = new KnowledgeService(repository, properties, chroma, embeddings,
            mock(KnowledgeReranker.class), new ObjectMapper());

    @Test void chromaFailureReusesVectorAndNeverEmbedsAgain() {
        properties.getKnowledge().setUseChroma(true);
        when(repository.findAll()).thenReturn(List.of(chunk(1L, "睡眠", false)));
        when(embeddings.embed(anyString(), any(Duration.class))).thenReturn(List.of(1.0, 0.0));
        when(chroma.queryVector(anyList(), anyInt(), any())).thenThrow(new IllegalStateException("offline"));
        var result = service.recall("睡眠", 4, new KnowledgeBudget(4000));
        assertThat(result.results()).extracting(SearchResult::chunkId).containsExactly(1L);
        assertThat(result.degradationReasons()).contains("CHROMA_FAILED", "LOCAL_VECTOR_FALLBACK");
        verify(embeddings, times(1)).embed(eq("睡眠"), any(Duration.class));
        verify(embeddings, never()).embed(anyString());
    }

    @Test void failedEmbeddingStillReturnsBm25() {
        when(repository.findAll()).thenReturn(List.of(chunk(1L, "睡眠", false)));
        when(embeddings.embed(anyString(), any(Duration.class))).thenThrow(new IllegalStateException("timeout"));
        var result = service.recall("睡眠", 4, new KnowledgeBudget(4000));
        assertThat(result.results()).extracting(SearchResult::chunkId).containsExactly(1L);
        assertThat(result.degradationReasons()).contains("EMBEDDING_FAILED");
        verifyNoInteractions(chroma);
    }

    @Test void unreviewedAndStaleChromaContentCannotEnterResults() {
        properties.getKnowledge().setUseChroma(true);
        when(repository.findAll()).thenReturn(List.of(chunk(1L, "睡眠 approved", false), chunk(2L, "睡眠 secret", true)));
        when(embeddings.embed(anyString(), any(Duration.class))).thenReturn(List.of(1.0, 0.0));
        when(chroma.queryVector(anyList(), anyInt(), any())).thenReturn(List.of(
                new SearchResult(2L, "a", "secret", 1), new SearchResult(1L, "a", "STALE", .9)));
        var result = service.recall("睡眠", 4, new KnowledgeBudget(4000));
        assertThat(result.results()).extracting(SearchResult::chunkId).containsExactly(1L);
        assertThat(result.results().get(0).content()).isEqualTo("睡眠 approved");
    }

    @Test void exhaustedBudgetSkipsNetworkAndPreservesBm25() throws Exception {
        when(repository.findAll()).thenReturn(List.of(chunk(1L, "睡眠", false)));
        KnowledgeBudget budget = new KnowledgeBudget(1);
        Thread.sleep(10);
        var result = service.recall("睡眠", 4, budget);
        assertThat(result.results()).hasSize(1);
        assertThat(result.degradationReasons()).contains("BUDGET_EXHAUSTED");
        verifyNoInteractions(embeddings, chroma);
    }

    private KnowledgeChunk chunk(Long id, String content, boolean review) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        ReflectionTestUtils.setField(chunk, "id", id);
        chunk.setSource("a.md"); chunk.setContent(content); chunk.setNeedsReview(review); chunk.setEmbeddingJson("[1.0,0.0]");
        return chunk;
    }
}
