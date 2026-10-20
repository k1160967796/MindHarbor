package com.mindharbor.agent.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindharbor.agent.domain.*;
import com.mindharbor.agent.repository.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Curated packages from the public catalog. No automatic approval from an LLM or a cache hit. */
@Service
public class ReviewedKnowledgePackService {
    public record Definition(String id, String tag, String scope, List<String> sources, List<String> hints) {}
    public static final List<Definition> DEFINITIONS = KnowledgeTagCatalog.ENTRIES.stream()
            .filter(KnowledgeTagCatalog.Entry::direct)
            .map(e -> new Definition(e.id(), e.tag(), e.scope(), e.sources(), e.hints())).toList();
    public record Source(Long id, String source, String content, String fingerprint) {}
    public record Preview(String id, String tag, String scope, String status, String reviewedBy,
                          Instant expiresAt, List<Source> sources, String digest) {}
    public record Hit(String id, String revision, String state, List<SearchResult> results) {
        public boolean hit() { return "APPROVED_PACK".equals(state); }
        static Hit miss(String state) { return new Hit("", "", state, List.of()); }
    }
    private final ReviewedKnowledgePackRepository packs;
    private final KnowledgeChunkRepository chunks;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final boolean enabled;

    public ReviewedKnowledgePackService(ReviewedKnowledgePackRepository packs, KnowledgeChunkRepository chunks,
            StringRedisTemplate redis, ObjectMapper json,
            @Value("${mindharbor.knowledge.reviewed-packs.enabled:true}") boolean enabled) {
        this.packs = packs; this.chunks = chunks; this.redis = redis; this.json = json; this.enabled = enabled;
    }

    /** Admin sees the exact immutable text to approve, never just a list of labels. */
    @Transactional(readOnly = true)
    public List<Preview> previews() {
        return DEFINITIONS.stream().map(def -> preview(def, packs.findById(def.id()).orElse(null))).toList();
    }

    private Preview preview(Definition def, ReviewedKnowledgePack saved) {
        List<Source> sources = suggestions(def);
        String status = "DRAFT";
        if (saved != null) {
            status = !saved.isApproved() ? "DISABLED" : !saved.getExpiresAt().isAfter(Instant.now()) ? "EXPIRED" : "STALE";
            List<Source> approved = read(saved.getSnapshotJson());
            if (saved.isApproved() && saved.getExpiresAt().isAfter(Instant.now()) && valid(approved)) {
                sources = approved;
                status = "APPROVED";
            }
        }
        return new Preview(def.id(), def.tag(), def.scope(), status, saved == null ? null : saved.getReviewedBy(),
                saved == null ? null : saved.getExpiresAt(), sources, digest(sources));
    }

    private List<Source> suggestions(Definition def) {
        // Only known public teaching documents; uploaded student material cannot
        // silently enter a cross-student package through semantic retrieval.
        return def.sources().stream()
                .flatMap(source -> chunks.findTop20BySourceOrderByCreatedAtDesc(source).stream()
                        .filter(c -> !c.isNeedsReview() && c.getContent() != null)
                        .filter(c -> def.hints().stream().anyMatch(c.getContent()::contains))
                        .sorted(Comparator.comparingInt(KnowledgeChunk::getSourceIndex)).limit(2))
                .limit(4).map(this::source).toList();
    }

    @Transactional
    public void approve(String id, String expectedDigest, String reviewer) {
        Definition def = definition(id);
        ReviewedKnowledgePack saved = packs.findById(id).orElse(null);
        Preview view = preview(def, saved);
        if (reviewer == null || reviewer.isBlank() || view.sources().isEmpty()
                || view.sources().stream().mapToInt(s -> s.content().length()).sum() > 6000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "知识包为空或过长，请先维护来源内容");
        if (!view.digest().equals(expectedDigest) || !valid(view.sources()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "来源已变化，请刷新并重新审核");
        if (saved == null) { saved = new ReviewedKnowledgePack(); saved.setId(id); }
        saved.setApproved(true); saved.setRevision(UUID.randomUUID().toString());
        saved.setSnapshotJson(write(view.sources())); saved.setReviewedBy(reviewer);
        saved.setReviewedAt(Instant.now()); saved.setExpiresAt(Instant.now().plus(Duration.ofDays(30)));
        packs.save(saved);
    }

    @Transactional
    public void disable(String id) {
        definition(id);
        packs.findById(id).ifPresent(pack -> { pack.setApproved(false); packs.save(pack); });
    }

    public Hit lookup(String tag) {
        if (!enabled) return Hit.miss("DISABLED");
        try {
            Definition def = DEFINITIONS.stream().filter(d -> d.tag().equals(tag)).findFirst().orElse(null);
            if (def == null) return Hit.miss("UNSUPPORTED");
            ReviewedKnowledgePack pack = packs.findById(def.id()).orElse(null);
            if (pack == null || !pack.isApproved()) return Hit.miss("NOT_APPROVED");
            if (!pack.getExpiresAt().isAfter(Instant.now())) return Hit.miss("EXPIRED");
            String key = "mindharbor:knowledge:approved-pack:v1:" + pack.getId() + ":" + pack.getRevision();
            String snapshot = redis.opsForValue().get(key);
            // DB is authoritative for revocation and approved content. Redis entries
            // must match the approved snapshot, not merely carry the same pack ID.
            if (!pack.getSnapshotJson().equals(snapshot)) {
                snapshot = pack.getSnapshotJson();
                redis.opsForValue().set(key, snapshot, Duration.ofSeconds(Math.max(1,
                        Math.min(1800, Duration.between(Instant.now(), pack.getExpiresAt()).toSeconds()))));
            }
            List<Source> sources = read(snapshot);
            if (!valid(sources)) return Hit.miss("SOURCE_CHANGED");
            return new Hit(pack.getId(), pack.getRevision(), "APPROVED_PACK", sources.stream()
                    .map(s -> new SearchResult(s.id(), s.source(), s.content(), 1.0)).toList());
        } catch (Exception error) {
            return Hit.miss("UNAVAILABLE"); // Redis/DB/invalid snapshots fall back to normal RAG.
        }
    }

    private boolean valid(List<Source> sources) {
        if (sources.isEmpty() || sources.size() > 4 || sources.stream().mapToInt(s -> s.content().length()).sum() > 6000) return false;
        Map<Long, KnowledgeChunk> current = new HashMap<>();
        chunks.findAllById(sources.stream().map(Source::id).toList()).forEach(c -> current.put(c.getId(), c));
        return sources.stream().allMatch(s -> {
            KnowledgeChunk c = current.get(s.id());
            return c != null && !c.isNeedsReview() && source(c).equals(s);
        });
    }
    private Source source(KnowledgeChunk c) {
        return new Source(c.getId(), c.getSource(), c.getContent(), hash(c.getSource() + "\0" + c.getContent()));
    }
    private Definition definition(String id) {
        return DEFINITIONS.stream().filter(d -> d.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    private List<Source> read(String value) {
        try { return List.of(json.readValue(value, Source[].class)); }
        catch (Exception error) { return List.of(); }
    }
    private String write(List<Source> sources) {
        try { return json.writeValueAsString(sources); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private String digest(List<Source> sources) { return hash(write(sources)); }
    private String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
