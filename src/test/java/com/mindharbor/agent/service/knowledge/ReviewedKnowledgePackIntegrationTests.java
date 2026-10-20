package com.mindharbor.agent.service.knowledge;

import com.mindharbor.agent.repository.*;
import com.mindharbor.agent.domain.*;
import com.mindharbor.agent.service.agent.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.data.redis.core.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.http.MediaType;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.url=jdbc:h2:mem:reviewed-packs;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "mindharbor.knowledge.use-chroma=false","mindharbor.memory.use-chroma=false",
        "spring.ai.mcp.server.enabled=false","spring.ai.mcp.client.enabled=false"})
@AutoConfigureWebTestClient
class ReviewedKnowledgePackIntegrationTests {
    @Autowired WebTestClient web;
    @Autowired ReviewedKnowledgePackService packs;
    @Autowired ReviewedKnowledgePackRepository repository;
    @Autowired KnowledgeChunkRepository chunks;
    @Autowired KnowledgeAgent agent;
    @SpyBean KnowledgeService knowledge;
    @MockBean EmbeddingClient embeddings;
    @MockBean ChromaGateway chroma;
    @MockBean StringRedisTemplate redis;

    @Test void adminApprovalIsDurableAndStudentCannotActivateOrDisablePacks() {
        @SuppressWarnings("unchecked") ValueOperations<String,String> values=mock(ValueOperations.class);
        Map<String,String> cache=new HashMap<>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(c->cache.get(c.getArgument(0)));
        doAnswer(c->{cache.put(c.getArgument(0),c.getArgument(1));return null;})
                .when(values).set(anyString(),anyString(),any(Duration.class));
        web.get().uri("/api/admin/knowledge/packs").exchange().expectStatus().isUnauthorized();
        web.get().uri("/api/admin/knowledge/packs").headers(h->h.setBasicAuth("student","student123"))
                .exchange().expectStatus().isForbidden();
        web.get().uri("/api/admin/knowledge/packs").headers(h->h.setBasicAuth("admin","admin123"))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(ReviewedKnowledgePackService.DEFINITIONS.size());
        var drafts=packs.previews();
        assertThat(drafts).allSatisfy(d->assertThat(d.sources()).isNotEmpty());
        for(var draft:drafts) {
            String url="/api/admin/knowledge/packs/"+draft.id();
            web.put().uri(url+"/approve").headers(h->h.setBasicAuth("student","student123"))
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("digest",draft.digest()))
                    .exchange().expectStatus().isForbidden();
            web.put().uri(url+"/approve").headers(h->h.setBasicAuth("admin","admin123"))
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("digest","stale-preview"))
                    .exchange().expectStatus().isEqualTo(409);
            web.put().uri(url+"/approve").headers(h->h.setBasicAuth("admin","admin123"))
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("digest",draft.digest()))
                    .exchange().expectStatus().isOk();
            assertThat(repository.findById(draft.id()).orElseThrow().getReviewedBy()).isEqualTo("admin");
            assertThat(packs.lookup(draft.tag()).hit()).isTrue();
        }
        clearInvocations(knowledge,embeddings,chroma);
        for(String text:List.of("我明天考试，太焦虑了睡不着怎么办","快期末了很紧张，书看不进去怎么办","作业快截止了，越着急越不敢开始",
                "期末让我很紧张，睡得不安稳怎么办","最近学习压力很大，睡前想东想西怎么办",
                "我课堂汇报时害怕讲不好，身体紧绷怎么办","和同学交流我担心说错话，整个人很紧绷怎么办",
                "我担心找不到实习，越想越焦虑怎么办")) {
            var c=new AgentContext(new UserAccount(),new ChatSession(),text,text);
            c.setIntent(IntentType.CONSULT);c.markIntentRouted();
            assertThat(agent.act(c).observation()).contains("SKIPPED_APPROVED_PACK");
            assertThat(c.retrievedKnowledge()).isNotEmpty();
        }
        verify(knowledge,never()).recall(anyString(),anyInt(),any());
        verify(knowledge,never()).review(anyString(),anyString(),anyString(),anyList(),anyInt(),any());
        verifyNoInteractions(embeddings,chroma);

        // Committed source edits invalidate even an already-warmed Redis package.
        Long id=drafts.get(0).sources().get(0).id();
        var source=chunks.findById(id).orElseThrow();source.setContent(source.getContent()+" updated");
        chunks.saveAndFlush(source);
        assertThat(packs.lookup(drafts.get(0).tag()).state()).isEqualTo("SOURCE_CHANGED");
        assertThat(packs.previews().get(0).status()).isEqualTo("STALE");
        web.put().uri("/api/admin/knowledge/packs/exam-focus/disable").headers(h->h.setBasicAuth("student","student123"))
                .exchange().expectStatus().isForbidden();
        web.put().uri("/api/admin/knowledge/packs/exam-focus/disable").headers(h->h.setBasicAuth("admin","admin123"))
                .exchange().expectStatus().isOk();
        assertThat(repository.findById("exam-focus").orElseThrow().isApproved()).isFalse();
    }
}
