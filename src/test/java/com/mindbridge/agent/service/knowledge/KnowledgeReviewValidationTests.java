package com.mindbridge.agent.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.service.ai.AiClient;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class KnowledgeReviewValidationTests {
    @org.junit.jupiter.api.Test
    void compactScoresAreValidatedAndSorted() {
        AiClient ai = mock(AiClient.class);
        when(ai.completeWithin(anyList(), any(), anyMap(), anyInt())).thenReturn(
                "{\"scores\":{\"1\":0.2,\"2\":0.9},\"coverage\":\"SUFFICIENT\",\"retryQuery\":null}");
        var reviewer = new KnowledgeReranker(new MindBridgeProperties(), ai, new ObjectMapper());
        var result = reviewer.review("考试焦虑", "考试焦虑", "",
                List.of(new SearchResult(1L, "a", "a", .9), new SearchResult(2L, "b", "b", .2)), 1,
                new KnowledgeBudget(4000));
        assertThat(result.results()).extracting(SearchResult::chunkId).containsExactly(2L);
        assertThat(result.coverage()).isEqualTo(KnowledgeReview.Coverage.SUFFICIENT);
        assertThat(result.reason()).isEqualTo("REVIEWED");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not json",
        "{\"scores\":{\"1\":0.9,\"1\":0.2},\"coverage\":\"SUFFICIENT\",\"retryQuery\":null}",
        "{\"scores\":{\"99\":0.9},\"coverage\":\"SUFFICIENT\",\"retryQuery\":null}",
        "{\"scores\":{},\"coverage\":\"SUFFICIENT\",\"retryQuery\":null}",
        "{\"ranked\":[{\"chunkId\":99,\"score\":0.9}],\"coverage\":\"SUFFICIENT\",\"retryQuery\":null}",
        "{\"ranked\":[{\"chunkId\":1,\"score\":0.9},{\"chunkId\":1,\"score\":0.7}],\"coverage\":\"SUFFICIENT\",\"retryQuery\":null}",
        "{\"ranked\":[{\"chunkId\":1,\"score\":1.5}],\"coverage\":\"SUFFICIENT\",\"retryQuery\":null}",
        "{\"ranked\":[],\"coverage\":\"SUFFICIENT\",\"retryQuery\":null}",
        "{\"ranked\":[{\"chunkId\":1,\"score\":0.9}],\"coverage\":\"MAYBE\",\"retryQuery\":null}"
    })
    void invalidReviewFallsBackWithUnknownCoverage(String response) {
        AiClient ai = mock(AiClient.class);
        when(ai.completeWithin(anyList(), any(), anyMap(), anyInt())).thenReturn(response);
        KnowledgeReranker reviewer = new KnowledgeReranker(new MindBridgeProperties(), ai, new ObjectMapper());
        var candidates = List.of(new SearchResult(1L, "a", "原始资料", .5));
        var result = reviewer.review("考试焦虑", "考试焦虑", "", candidates, 4, new KnowledgeBudget(4000));
        assertThat(result.results()).isEqualTo(candidates);
        assertThat(result.coverage()).isEqualTo(KnowledgeReview.Coverage.UNKNOWN);
        assertThat(result.retryQuery()).isNull();
        assertThat(result.reason()).startsWith("REVIEW_INVALID_");
    }
}
