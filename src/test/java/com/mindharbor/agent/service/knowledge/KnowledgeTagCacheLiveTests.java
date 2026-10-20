package com.mindharbor.agent.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindharbor.agent.config.*;
import com.mindharbor.agent.domain.*;
import com.mindharbor.agent.repository.KnowledgeChunkRepository;
import com.mindharbor.agent.service.agent.*;
import com.mindharbor.agent.service.ai.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * Opt-in local probe: actual Ollama/Chroma/Redis, read-only MySQL snapshot.
 * Does not start the application, create student messages, or execute mail tasks.
 * Repository calls use the snapshot, so timings exclude normal JDBC query costs.
 */
@EnabledIfEnvironmentVariable(named="KNOWLEDGE_CACHE_LIVE", matches="true")
class KnowledgeTagCacheLiveTests {
    @Test void measureLocalKnowledgePath() throws Exception {
        String url = System.getenv().getOrDefault("CACHE_PROBE_DB_URL",
                "jdbc:mysql://localhost:3307/mindbridge?useSSL=false&allowPublicKeyRetrieval=true");
        List<KnowledgeChunk> snapshot = new ArrayList<>();
        try (Connection db = DriverManager.getConnection(url,
                System.getenv().getOrDefault("CACHE_PROBE_DB_USER", "mindbridge"),
                System.getenv().getOrDefault("CACHE_PROBE_DB_PASSWORD", "mindbridge"))) {
            db.setReadOnly(true);
            try (var statement = db.createStatement(); var rows = statement.executeQuery(
                    "select id,source,source_index,content,embedding_json,needs_review from knowledge_chunks")) {
                while (rows.next()) {
                    var chunk = new KnowledgeChunk();
                    ReflectionTestUtils.setField(chunk,"id",rows.getLong("id"));
                    chunk.setSource(rows.getString("source")); chunk.setSourceIndex(rows.getInt("source_index"));
                    chunk.setContent(rows.getString("content"));chunk.setEmbeddingJson(rows.getString("embedding_json"));
                    chunk.setNeedsReview(rows.getBoolean("needs_review"));snapshot.add(chunk);
                }
            }
        }
        assertThat(snapshot).isNotEmpty();
        KnowledgeChunkRepository repo = mock(KnowledgeChunkRepository.class);
        when(repo.findAll()).thenReturn(snapshot);
        when(repo.findById(anyLong())).thenAnswer(c -> snapshot.stream().filter(x -> x.getId().equals(c.getArgument(0))).findFirst());
        when(repo.findTop20BySourceOrderByCreatedAtDesc(anyString())).thenAnswer(c ->
                snapshot.stream().filter(x -> x.getSource().equals(c.getArgument(0))).limit(20).toList());
        when(repo.findAllById(any())).thenAnswer(c -> {
            Set<Long> ids = new HashSet<>(); ((Iterable<Long>)c.getArgument(0)).forEach(ids::add);
            return snapshot.stream().filter(x -> ids.contains(x.getId())).toList();
        });
        when(repo.findBySourceAndSourceIndexBetweenOrderBySourceIndexAsc(anyString(),anyInt(),anyInt()))
                .thenAnswer(c -> snapshot.stream().filter(x -> x.getSource().equals(c.getArgument(0))
                        && x.getSourceIndex() >= (int)c.getArgument(1) && x.getSourceIndex() <= (int)c.getArgument(2))
                        .sorted(Comparator.comparingInt(KnowledgeChunk::getSourceIndex)).toList());
        var p = new MindHarborProperties();
        p.getAi().getOllama().setModel("qwen2.5:1.5b");
        p.getEmbedding().setBaseUrl("http://localhost:11434");
        p.getEmbedding().setApiKey("ollama-local");p.getEmbedding().setModel("bge-m3");
        p.getKnowledge().setUseChroma(true);
        p.getKnowledge().setChromaCollection(System.getenv().getOrDefault("CACHE_PROBE_COLLECTION","mindbridge_knowledge_bge_m3"));
        p.getKnowledge().setRerankerMaxContentChars(200);
        var json = new ObjectMapper();
        var embeddings = spy(new OpenAiEmbeddingClient(p,WebClient.builder()));
        var chroma = spy(new ChromaGateway(p,WebClient.builder(),embeddings));
        var ai = spy(new AiClientConfig().aiClient(p));
        var service = spy(new KnowledgeService(repo,p,chroma,embeddings,new KnowledgeReranker(p,ai,json),json));
        var factory = new LettuceConnectionFactory("localhost",6379);
        factory.afterPropertiesSet();factory.start();
        var redis = new StringRedisTemplate(factory);
        var cache = new KnowledgeTagCache(redis,repo,json,true,1800);
        var agent = new KnowledgeAgent(service,p,ai,cache);
        var plain = new KnowledgeAgent(service,p,ai);
        List<Map<String,Object>> measurements = new ArrayList<>();
        List<KnowledgeTagCache.Plan> used = new ArrayList<>();
        var packRepo = mock(com.mindharbor.agent.repository.ReviewedKnowledgePackRepository.class);
        Map<String,ReviewedKnowledgePack> probePacks = new HashMap<>();
        when(packRepo.findById(anyString())).thenAnswer(c -> Optional.ofNullable(probePacks.get(c.getArgument(0))));
        when(packRepo.save(any())).thenAnswer(c -> {
            ReviewedKnowledgePack pack=c.getArgument(0);probePacks.put(pack.getId(),pack);return pack;
        });
        var packs = new ReviewedKnowledgePackService(packRepo,repo,redis,json,true);
        var direct = new KnowledgeAgent(service,p,ai,cache,packs);
        try {
            // Warm local models outside measurements to avoid confusing model loading with cache savings.
            embeddings.embed("考试焦虑睡眠");ai.complete(List.of(AiMessage.user("只回复好")));
            String[] prompts = {"我明天考试，太焦虑了睡不着怎么办",
                    "临近考试很紧张，入睡困难怎么办",
                    "快考试了很焦虑，晚上睡不着怎么办"};
            for (int round=0;round<6;round++) {
                String text=prompts[Math.min(round,2)];
                var context = new AgentContext(new UserAccount(),new ChatSession(),text,text);
                context.setIntent(IntentType.CONSULT);context.markIntentRouted();
                used.add(cache.plan(context));
                clearInvocations(service,embeddings,chroma,ai);
                var result=(round==0 || round==5 ? plain : agent).act(context);
                long recalls=mockingDetails(service).getInvocations().stream().filter(i->i.getMethod().getName().equals("recall")).count();
                long embeddingCalls=mockingDetails(embeddings).getInvocations().stream().filter(i->i.getMethod().getName().equals("embed")).count();
                measurements.add(Map.of("round",round,"mode",round==0||round==5?"uncached":"tag-cache",
                        "recallCalls",recalls,"queryEmbeddingCalls",embeddingCalls,"trace",result.observation()));
            }
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target/knowledge-tag-cache-live.json"),
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("knowledgeChunks",snapshot.size(),
                            "scope","Real local Ollama, Chroma, Redis; read-only DB snapshot; KnowledgeAgent only, not end-to-end",
                            "measurements",measurements)));
            // Test-only approvals in a mock repository, never the user's MySQL.
            // They establish mechanics/performance, not clinical content approval.
            for(var draft:packs.previews()) packs.approve(draft.id(),draft.digest(),"isolated-probe");
            List<Map<String,Object>> packMeasurements = new ArrayList<>();
            for(int round=0;round<6;round++) {
                String text="我明天考试，太焦虑了睡不着怎么办";
                var context = new AgentContext(new UserAccount(),new ChatSession(),text,text);
                context.setIntent(IntentType.CONSULT);context.markIntentRouted();
                clearInvocations(service,embeddings,chroma,ai);
                var result=(round%2==0 ? plain : direct).act(context);
                long recalls=mockingDetails(service).getInvocations().stream().filter(i->i.getMethod().getName().equals("recall")).count();
                long reviews=mockingDetails(service).getInvocations().stream().filter(i->i.getMethod().getName().equals("review")).count();
                long embeddingCalls=mockingDetails(embeddings).getInvocations().stream().filter(i->i.getMethod().getName().equals("embed")).count();
                if(round%2==1) {
                    assertThat(result.observation()).contains("SKIPPED_APPROVED_PACK");
                    assertThat(recalls).isZero();assertThat(reviews).isZero();assertThat(embeddingCalls).isZero();
                    verifyNoInteractions(ai);
                }
                packMeasurements.add(Map.of("round",round,"mode",round%2==0?"normal-rag":"approved-pack",
                        "recallCalls",recalls,"reviewCalls",reviews,"queryEmbeddingCalls",embeddingCalls,"trace",result.observation()));
            }
            Files.writeString(Path.of("target/reviewed-knowledge-pack-live.json"),
                    json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "scope","Real Ollama/Chroma/Redis; read-only source snapshot; test-only approvals; no full chat or DB latency",
                            "measurements",packMeasurements)));
        } finally {
            used.forEach(cache::evict);
            probePacks.values().forEach(pack -> redis.delete(
                    "mindharbor:knowledge:approved-pack:v1:" + pack.getId() + ":" + pack.getRevision()));
            factory.destroy();
        }
    }
}
