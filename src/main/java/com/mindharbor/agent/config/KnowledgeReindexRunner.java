package com.mindharbor.agent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindharbor.agent.repository.KnowledgeChunkRepository;
import com.mindharbor.agent.repository.UserMemoryItemRepository;
import com.mindharbor.agent.service.knowledge.ChromaGateway;
import com.mindharbor.agent.service.knowledge.EmbeddingClient;
import com.mindharbor.agent.service.memory.UserMemoryChromaGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Explicit startup maintenance for an embedding-model/index migration; preserves source records. */
@Component
@Order(1)
@ConditionalOnProperty(name = "mindharbor.knowledge.reindex-on-start", havingValue = "true")
public class KnowledgeReindexRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeReindexRunner.class);
    private final KnowledgeChunkRepository chunks;
    private final UserMemoryItemRepository memories;
    private final EmbeddingClient embeddings;
    private final ChromaGateway knowledge;
    private final UserMemoryChromaGateway memory;
    private final ObjectMapper mapper;

    public KnowledgeReindexRunner(KnowledgeChunkRepository chunks, UserMemoryItemRepository memories,
                                  EmbeddingClient embeddings, ChromaGateway knowledge,
                                  UserMemoryChromaGateway memory, ObjectMapper mapper) {
        this.chunks = chunks; this.memories = memories; this.embeddings = embeddings;
        this.knowledge = knowledge; this.memory = memory; this.mapper = mapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        int count = 0;
        for (var chunk : chunks.findAll()) {
            if (chunk.isNeedsReview()) continue;
            var vector = embeddings.embed(chunk.getContent());
            if (vector.isEmpty()) throw new IllegalStateException("Reindex requires a working embedding service");
            chunk.setEmbeddingJson(mapper.writeValueAsString(vector));
            chunks.save(chunk);
            knowledge.mirror(chunk);
            count++;
        }
        for (var item : memories.findAll()) memory.mirror(item);
        log.info("Reindexed {} published knowledge chunks with {}", count, embeddings.modelName());
    }
}
