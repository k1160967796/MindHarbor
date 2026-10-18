package com.mindharbor.agent.service.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.mindharbor.agent.repository.KnowledgeChunkRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.core.io.ByteArrayResource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:knowledge-media;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "mindharbor.knowledge.use-chroma=false", "mindharbor.memory.use-chroma=false",
    "mindharbor.knowledge.reranker-enabled=false", "spring.ai.mcp.server.enabled=false",
    "spring.ai.mcp.client.enabled=false"
})
@AutoConfigureWebTestClient
class KnowledgeMediaIntegrationTests {
    @Autowired KnowledgeService knowledge;
    @Autowired KnowledgeChunkRepository repository;
    @Autowired WebTestClient web;
    @MockBean ChromaGateway chroma;
    @MockBean EmbeddingClient embeddings;

    @Test void reviewGateAppliesToBm25ChromaAndNeighborExpansion() {
        knowledge.ingestBlocks("review.pdf", List.of(
                new KnowledgeBlock(1, "table", "unique-review-token ".repeat(100), "a".repeat(64) + ".png", true),
                new KnowledgeBlock(2, "formula", "unpublished-secret", null, true)));
        var chunks = repository.findTop20BySourceOrderByCreatedAtDesc("review.pdf");
        assertThat(chunks).hasSize(2); // structured block isn't split at 512 characters
        var first = chunks.stream().filter(chunk -> chunk.getPageNumber() == 1).findFirst().orElseThrow();
        when(chroma.query(anyString(), anyInt())).thenReturn(List.of(
                new SearchResult(first.getId(), "review.pdf", "STALE-UNREVIEWED", 1)));
        assertThat(knowledge.retrieve("unique-review-token", 4)).isEmpty();
        knowledge.approve(first.getId(), "unique-review-token corrected table");
        var results = knowledge.retrieve("unique-review-token", 4);
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).content()).contains("第1页", "corrected table")
                .doesNotContain("unpublished-secret", "STALE-UNREVIEWED");
        assertThat(repository.findById(first.getId()).orElseThrow().isNeedsReview()).isFalse();
    }

    @Test void oldPublishedTextRemainsRetrievableAndBlankReplacementIsRejected() {
        knowledge.ingest("legacy.md", "distinct-legacy-content");
        assertThatThrownBy(() -> knowledge.ingest("legacy.md", "  ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(knowledge.retrieve("distinct-legacy-content", 4)).isNotEmpty();
    }

    @Test void reviewAndOriginalImagesRequireAdminAndAdminCanPublish() {
        knowledge.ingestBlocks("permission.png", List.of(new KnowledgeBlock(1, "image", "initial", null, true)));
        Long id = repository.findTop20BySourceOrderByCreatedAtDesc("permission.png").get(0).getId();
        web.get().uri("/api/admin/knowledge/review").headers(h -> h.setBasicAuth("student", "student123"))
                .exchange().expectStatus().isForbidden();
        web.get().uri("/api/admin/knowledge/images/" + "a".repeat(64) + ".png")
                .headers(h -> h.setBasicAuth("student", "student123"))
                .exchange().expectStatus().isForbidden();
        web.put().uri("/api/admin/knowledge/review/" + id).headers(h -> h.setBasicAuth("admin", "admin123"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("content", "人工确认的流程"))
                .exchange().expectStatus().isNoContent();
        assertThat(repository.findById(id).orElseThrow().getContent()).isEqualTo("人工确认的流程");
    }

    @Test void multipartTextUploadRunsOutsideEventLoop() {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("file", new ByteArrayResource("text-upload-check".getBytes())).filename("http-upload.txt");
        web.post().uri("/api/admin/knowledge/file").headers(h -> h.setBasicAuth("admin", "admin123"))
                .contentType(MediaType.MULTIPART_FORM_DATA).bodyValue(body.build())
                .exchange().expectStatus().isOk().expectBody().jsonPath("chunks").isEqualTo(1);
    }
}
