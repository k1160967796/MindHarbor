package com.mindharbor.agent.service.ai;

import com.mindharbor.agent.repository.ModelCallRecordRepository;
import com.mindharbor.agent.service.knowledge.EmbeddingClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.url=jdbc:h2:mem:model-observation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "mindharbor.knowledge.use-chroma=false", "mindharbor.memory.use-chroma=false",
        "spring.ai.mcp.server.enabled=false", "spring.ai.mcp.client.enabled=false"})
@AutoConfigureWebTestClient
class ModelObservationIntegrationTests {
    @Autowired WebTestClient web;
    @Autowired ModelCallTelemetry telemetry;
    @Autowired ModelCallRecordRepository repository;
    @MockBean EmbeddingClient embeddings;
    @MockBean StringRedisTemplate redis;

    @Test void adminSeesPersistedUsageAndUnknownCountsWhileStudentCannotReadMetrics() throws Exception {
        var a=telemetry.start(new ModelCallContext("integration-request", "session", "FINAL_RESPONSE", 0, 1), "test", "fixture", "CHAT_STREAM");
        a.usage(10L,5L,15L); a.finish("SUCCESS", null);
        var b=telemetry.start(new ModelCallContext("integration-request", "session", "FINAL_RESPONSE", 0, 2), "test", "fixture", "CHAT_STREAM");
        b.finish("CANCELLED", null);
        for(int i=0;i<100 && repository.findByRequestIdOrderByStartedAtAsc("integration-request").size()<2;i++) Thread.sleep(20);
        assertThat(repository.findByRequestIdOrderByStartedAtAsc("integration-request")).hasSize(2);
        web.get().uri("/api/admin/model-calls").exchange().expectStatus().isUnauthorized();
        web.get().uri("/api/admin/model-calls").headers(h->h.setBasicAuth("student","student123")).exchange().expectStatus().isForbidden();
        web.get().uri("/actuator/metrics").headers(h->h.setBasicAuth("student","student123")).exchange().expectStatus().isForbidden();
        web.get().uri("/api/admin/model-calls?requestId=integration-request").headers(h->h.setBasicAuth("admin","admin123"))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.calls").isEqualTo(2)
                .jsonPath("$.knownTotalTokens").isEqualTo(15).jsonPath("$.usageMissingCalls").isEqualTo(1)
                .jsonPath("$.unsuccessfulCalls").isEqualTo(1);
        web.get().uri("/actuator/metrics/mindharbor.model.call").headers(h->h.setBasicAuth("admin","admin123"))
                .exchange().expectStatus().isOk();
    }
}
