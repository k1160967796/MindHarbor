package com.mindharbor.agent.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindharbor.agent.domain.IntentType;
import com.mindharbor.agent.domain.KnowledgeChunk;
import com.mindharbor.agent.repository.KnowledgeChunkRepository;
import com.mindharbor.agent.service.agent.AgentContext;
import com.mindharbor.agent.service.ai.RiskLexicon;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Single-application-instance, cross-student public candidate cache.
 * No student input, memory, assessment or personalized review is serialized.
 * A boot namespace prevents reuse after restart/model/config changes; committed
 * knowledge writes rotate the generation. Old entries expire naturally.
 */
@Service
public class KnowledgeTagCache {
    private final StringRedisTemplate redis;
    private final KnowledgeChunkRepository chunks;
    private final ObjectMapper json;
    private final boolean enabled;
    private final Duration ttl;
    private final String namespace = "mindharbor:knowledge:tags:v1:" + UUID.randomUUID() + ":";
    private final AtomicLong generation = new AtomicLong();

    public KnowledgeTagCache(StringRedisTemplate redis, KnowledgeChunkRepository chunks, ObjectMapper json,
            @Value("${mindharbor.knowledge.tag-cache.enabled:true}") boolean enabled,
            @Value("${mindharbor.knowledge.tag-cache.ttl-seconds:1800}") long ttlSeconds) {
        this.redis = redis; this.chunks = chunks; this.json = json; this.enabled = enabled;
        this.ttl = Duration.ofSeconds(Math.max(1, ttlSeconds));
    }

    public record Plan(String tag, String query, String key, long generation, String reason) {
        public boolean eligible() { return key != null; }
    }
    public record Lookup(List<SearchResult> results, String state) {
        public boolean hit() { return "HIT".equals(state); }
    }
    public record CachedChunk(Long id, double score, String fingerprint) {}

    public Plan plan(AgentContext context) {
        String text = context.modelInput().replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (!enabled) return bypass("DISABLED");
        if (context.intent() != IntentType.CONSULT || RiskLexicon.hasHighRiskSignal(text)
                || RiskLexicon.hasRecentHighRiskUserSignal(context.previousHistory())) return bypass("RISK_OR_ROUTE");
        var expanded = KnowledgeTagCatalog.expandedMatch(text);
        if (expanded != null) {
            long version = generation.get();
            return new Plan(expanded.tag(), expanded.query(), namespace + version + ":" + expanded.tag(), version, "ELIGIBLE");
        }
        // Conservative v1: complete explicit questions only; do not guess missing emotion
        // or import personal context into a globally shared lookup.
        if (text.length() > 180 || contains(text, "不焦虑", "不紧张", "不担心", "不失眠", "没失眠",
                "没有焦虑", "没有失眠", "不再", "并不", "并非", "不是", "没用", "无效", "试过",
                "没有用", "不管用", "不太", "不怎么", "没那么", "不觉得", "不感到", "不算", "毫不",
                "并没有", "还是", "之前", "上次", "刚才", "这种情况", "这个问题",
                "几个月", "几周", "长期", "一直", "每天", "反复", "越来越", "加重",
                "自残", "药", "心悸", "呼吸困难", "室友", "父母", "家庭", "分手", "恋爱",
                "导师", "工作", "面试", "经济", "生病", "抑郁", "绝望", "恐慌", "崩溃",
                "难过", "孤独", "愤怒", "别人", "朋友", "同学说", "他", "她", "假如", "假设", "如果",
                "早醒", "噩梦")) return bypass("COMPLEX_OR_NEGATED");
        if (!contains(text, "焦虑", "紧张", "担心", "着急")) return bypass("MISSING_EMOTION");
        // A negated symptom must not accidentally become a positive label.
        if (contains(text, "没有睡不着", "不再睡不着", "没有注意力", "不拖延", "没有拖延",
                "能睡着", "睡得好", "没有紧张", "没有担心", "没有着急")) return bypass("NEGATED");
        boolean exam = contains(text, "考试", "考前", "期末", "期中", "考研");
        boolean task = contains(text, "作业", "论文", "任务", "截止", "ddl");
        boolean sleep = contains(text, "睡不着", "失眠", "入睡困难", "难以入睡");
        boolean focus = contains(text, "注意力不集中", "注意力难集中", "无法集中", "难以集中", "看不进去", "学不进去");
        boolean delay = contains(text, "拖延", "不敢开始", "迟迟不开始", "迟迟没有开始", "迟迟没开始");
        if ((exam && task) || (sleep ? 1 : 0) + (focus ? 1 : 0) + (delay ? 1 : 0) != 1)
            return bypass("AMBIGUOUS_LABELS");
        String tag, query;
        if (exam && sleep) {
            tag = "考试|焦虑|入睡困难"; query = "快考试了很焦虑，晚上睡不着怎么办";
        } else if (exam && focus) {
            tag = "考试|焦虑|注意力难集中"; query = "快考试了很焦虑，书看不进去、注意力难集中怎么办";
        } else if (task && delay) {
            tag = "学业任务|焦虑|拖延启动"; query = "作业快截止了很焦虑，拖延不敢开始怎么办";
        } else return bypass("UNSUPPORTED_LABELS");
        long version = generation.get();
        return new Plan(tag, query, namespace + version + ":" + tag, version, "ELIGIBLE");
    }

    private Plan bypass(String reason) { return new Plan("", "", null, generation.get(), reason); }
    private static boolean contains(String text, String... words) {
        return Arrays.stream(words).anyMatch(text::contains);
    }

    public Lookup lookup(Plan plan) {
        if (!plan.eligible()) return new Lookup(List.of(), "BYPASS_" + plan.reason());
        try {
            String value = redis.opsForValue().get(plan.key());
            if (value == null) return new Lookup(List.of(), "MISS");
            List<CachedChunk> saved = Arrays.asList(json.readValue(value, CachedChunk[].class));
            if (saved.isEmpty() || saved.size() > 100) return stale(plan);
            Map<Long, KnowledgeChunk> byId = new HashMap<>();
            chunks.findAllById(saved.stream().map(CachedChunk::id).toList()).forEach(c -> byId.put(c.getId(), c));
            List<SearchResult> results = new ArrayList<>();
            for (CachedChunk item : saved) {
                KnowledgeChunk chunk = byId.get(item.id());
                if (chunk == null || chunk.isNeedsReview() || !Double.isFinite(item.score())
                        || !fingerprint(chunk.getSource(), chunk.getContent()).equals(item.fingerprint())) return stale(plan);
                results.add(new SearchResult(chunk.getId(), chunk.getSource(), chunk.getContent(), item.score()));
            }
            if (generation.get() != plan.generation()) return stale(plan);
            return new Lookup(List.copyOf(results), "HIT");
        } catch (Exception error) {
            return new Lookup(List.of(), "ERROR");
        }
    }

    private Lookup stale(Plan plan) { evict(plan); return new Lookup(List.of(), "STALE"); }
    public void evict(Plan plan) {
        if (plan.eligible()) try { redis.delete(plan.key()); } catch (Exception ignored) { }
    }

    public boolean store(Plan plan, KnowledgeCandidates candidates, KnowledgeReview review) {
        if (!plan.eligible() || generation.get() != plan.generation() || candidates.results().isEmpty()
                || candidates.results().size() > 100 || !candidates.degradationReasons().isEmpty()
                || review.coverage() != KnowledgeReview.Coverage.SUFFICIENT
                || !"REVIEWED".equals(review.reason())) return false;
        try {
            List<CachedChunk> saved = new ArrayList<>();
            for (SearchResult result : candidates.results()) {
                if (result.chunkId() == null || !Double.isFinite(result.score())) return false;
                saved.add(new CachedChunk(result.chunkId(), result.score(), fingerprint(result.source(), result.content())));
            }
            redis.opsForValue().set(plan.key(), json.writeValueAsString(saved), ttl);
            return true;
        } catch (Exception error) { return false; }
    }

    /** Called inside the same transaction as every supported knowledge mutation. */
    public void invalidateAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { generation.incrementAndGet(); }
            });
        } else generation.incrementAndGet();
    }

    private String fingerprint(String source, String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((source + "\0" + content).getBytes(StandardCharsets.UTF_8)));
    }
}
