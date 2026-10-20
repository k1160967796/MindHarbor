package com.mindharbor.agent.service.knowledge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindharbor.agent.config.MindHarborProperties;
import com.mindharbor.agent.domain.KnowledgeChunk;
import com.mindharbor.agent.repository.KnowledgeChunkRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
/**
 * RAG 知识库核心服务。
 *
 * <p>负责知识入库、向量写入、检索排序和命中上下文扩展，是咨询/风险回答的知识来源。</p>
 */
public class KnowledgeService {

    private static final double VECTOR_WEIGHT = 0.65;
    private static final double BM25_WEIGHT = 0.35;

    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final MindHarborProperties properties;
    private final ChromaGateway chromaGateway;
    private final EmbeddingClient embeddingClient;
    private final KnowledgeReranker knowledgeReranker;
    private final ObjectMapper objectMapper;
    private final KnowledgeChunker chunker = new KnowledgeChunker();
    private final Bm25Scorer bm25Scorer = new Bm25Scorer();
    @org.springframework.beans.factory.annotation.Autowired
    private KnowledgeTagCache tagCache;

    public KnowledgeService(
            KnowledgeChunkRepository knowledgeChunkRepository,
            MindHarborProperties properties,
            ChromaGateway chromaGateway,
            EmbeddingClient embeddingClient,
            KnowledgeReranker knowledgeReranker,
            ObjectMapper objectMapper
    ) {
        this.knowledgeChunkRepository = knowledgeChunkRepository;
        this.properties = properties;
        this.chromaGateway = chromaGateway;
        this.embeddingClient = embeddingClient;
        this.knowledgeReranker = knowledgeReranker;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public int ingest(String source, String content) {
        return ingestBlocks(source, List.of(new KnowledgeBlock(null, "text", content, null, false)));
    }

    @Transactional
    public int ingestBlocks(String source, List<KnowledgeBlock> blocks) {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (KnowledgeBlock block : blocks) {
            if (block.text() == null || block.text().isBlank()) continue;
            // Preserve tables/formulas/diagrams as complete semantic units.
            List<String> parts = block.isStructured() ? List.of(block.text()) : chunker.chunk(
                    block.text(), properties.getKnowledge().getChunkSize(), properties.getKnowledge().getChunkOverlap());
            for (String part : parts) {
                KnowledgeChunk chunk = new KnowledgeChunk();
                chunk.setSource(source);
                chunk.setSourceIndex(chunks.size());
                chunk.setPageNumber(block.pageNumber());
                chunk.setBlockType(block.type());
                chunk.setImageRef(block.imageRef());
                chunk.setNeedsReview(block.needsReview());
                chunk.setContent(part);
                if (!block.needsReview()) chunk.setEmbeddingJson(serializeEmbedding(safeEmbedding(part)));
                chunks.add(chunk);
            }
        }
        if (chunks.isEmpty()) throw new IllegalArgumentException("没有从文件中解析出可用知识");
        knowledgeChunkRepository.deleteBySource(source);
        knowledgeChunkRepository.flush();
        chromaGateway.deleteSource(source);
        for (KnowledgeChunk chunk : chunks) {
            KnowledgeChunk saved = knowledgeChunkRepository.save(chunk);
            if (!saved.isNeedsReview()) chromaGateway.mirror(saved);
        }
        if (tagCache != null) tagCache.invalidateAfterCommit();
        return chunks.size();
    }

    @Transactional(readOnly = true)
    public List<KnowledgeChunk> pendingReview() {
        return knowledgeChunkRepository.findByNeedsReviewTrueOrderBySourceAscSourceIndexAsc();
    }

    @Transactional
    public void approve(Long id, String content) {
        KnowledgeChunk chunk = knowledgeChunkRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("知识片段不存在"));
        if (content == null || content.isBlank() || content.length() > 12000
                || content.contains("[尚未启用") || content.contains("[视觉识别")) {
            throw new IllegalArgumentException("请核对原图并填写完整内容后再发布（最多 12000 字符）");
        }
        chunk.setContent(content.trim());
        chunk.setNeedsReview(false);
        chunk.setEmbeddingJson(serializeEmbedding(safeEmbedding(chunk.getContent())));
        knowledgeChunkRepository.save(chunk);
        chromaGateway.mirror(chunk);
        if (tagCache != null) tagCache.invalidateAfterCommit();
    }

    @Transactional(readOnly = true)
    public List<SearchResult> retrieve(String query, int topK) {
        KnowledgeBudget budget = new KnowledgeBudget(properties.getKnowledge().getBudgetMs());
        KnowledgeCandidates candidates = recall(query, topK, budget);
        KnowledgeReview reviewed = review(query, query, "", candidates.results(), topK, budget);
        return materialize(reviewed.results(), topK);
    }

    /** 召回阶段不调用生成式模型；先保留 BM25，网络超时仍有可用候选。 */
    @Transactional(readOnly = true)
    public KnowledgeCandidates recall(String query, int topK, KnowledgeBudget budget) {
        if (topK <= 0 || query == null || query.isBlank()) return new KnowledgeCandidates(List.of(), List.of());
        List<String> reasons = new ArrayList<>();
        int limit = Math.max(topK, Math.max(1, properties.getKnowledge().getRecallCandidateLimit()));
        List<KnowledgeChunk> chunks = knowledgeChunkRepository.findAll().stream()
                .filter(chunk -> !chunk.isNeedsReview()).toList();
        List<SearchResult> bm25 = bm25Scorer.rank(query, chunks, limit);
        List<Double> vector = List.of();
        if (!chunks.isEmpty() && budget.remainingMs() > 0) {
            try {
                vector = embeddingClient.embed(query, budget.remaining(properties.getKnowledge().getEmbeddingTimeoutMs()));
                if (vector == null || vector.isEmpty()) { vector = List.of(); reasons.add("EMBEDDING_UNAVAILABLE"); }
            } catch (Exception error) { reasons.add(KnowledgeBudget.failureReason("EMBEDDING", error)); }
        }
        List<SearchResult> vectors = List.of();
        if (!vector.isEmpty()) {
            Map<Long, KnowledgeChunk> published = new LinkedHashMap<>();
            chunks.forEach(chunk -> published.put(chunk.getId(), chunk));
            if (properties.getKnowledge().isUseChroma() && budget.remainingMs() > 0) {
                try {
                    vectors = chromaGateway.queryVector(vector, limit, budget.remaining(properties.getKnowledge().getChromaTimeoutMs()))
                            .stream().filter(result -> result.chunkId() != null && published.containsKey(result.chunkId()))
                            .map(result -> new SearchResult(result.chunkId(), published.get(result.chunkId()).getSource(),
                                    published.get(result.chunkId()).getContent(), result.score())).toList();
                    if (vectors.isEmpty()) reasons.add("CHROMA_EMPTY");
                } catch (Exception error) { reasons.add(KnowledgeBudget.failureReason("CHROMA", error)); }
            }
            if (vectors.isEmpty() && budget.remainingMs() > 0) {
                vectors = retrieveByEmbedding(vector, limit, chunks);
                reasons.add("LOCAL_VECTOR_FALLBACK");
            }
        }
        if (budget.remainingMs() == 0) reasons.add("BUDGET_EXHAUSTED");
        return new KnowledgeCandidates(mergeHybridResults(vectors, bm25, limit), List.copyOf(reasons));
    }

    public KnowledgeReview review(String question, String query, String memory, List<SearchResult> candidates,
                                  int topK, KnowledgeBudget budget) {
        return knowledgeReranker.review(question, query, memory, candidates, topK, budget);
    }

    /** 评审结束后回读原文；再次检查发布状态，避免撤回/待审核片段被展示。 */
    @Transactional(readOnly = true)
    public List<SearchResult> materialize(List<SearchResult> selected, int topK) {
        if (topK <= 0) return List.of();
        List<SearchResult> located = new ArrayList<>();
        for (SearchResult result : selected.stream().limit(topK).toList()) {
            if (result.chunkId() == null) continue;
            knowledgeChunkRepository.findById(result.chunkId()).filter(chunk -> !chunk.isNeedsReview()).ifPresent(chunk ->
                    located.add(new SearchResult(chunk.getId(), chunk.getSource(), withSourceLocation(chunk), result.score())));
        }
        return expandBestContext(located, topK);
    }

    private List<SearchResult> retrieveByEmbedding(List<Double> vector, int topK, List<KnowledgeChunk> chunks) {
        return chunks.stream()
                .map(chunk -> new SearchResult(chunk.getId(), chunk.getSource(), chunk.getContent(),
                        cosine(vector, parseEmbedding(chunk.getEmbeddingJson()))))
                .filter(result -> result.score() > 0.0)
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed()).limit(topK).toList();
    }

    private List<SearchResult> mergeHybridResults(
            List<SearchResult> vectorResults,
            List<SearchResult> bm25Results,
            int topK
    ) {
        Map<String, HybridCandidate> candidates = new LinkedHashMap<>();
        double maxVectorScore = maxScore(vectorResults);
        double maxBm25Score = maxScore(bm25Results);
        mergeRoute(candidates, vectorResults, maxVectorScore, true);
        mergeRoute(candidates, bm25Results, maxBm25Score, false);
        return candidates.values().stream()
                .map(HybridCandidate::toSearchResult)
                .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                .limit(topK)
                .toList();
    }

    private void mergeRoute(
            Map<String, HybridCandidate> candidates,
            List<SearchResult> results,
            double maxScore,
            boolean vectorRoute
    ) {
        if (results.isEmpty() || maxScore <= 0.0) {
            return;
        }
        for (int rank = 0; rank < results.size(); rank++) {
            SearchResult result = results.get(rank);
            double normalizedScore = Math.max(0.0, result.score()) / maxScore;
            double rankBoost = 1.0 / (rank + 1.0);
            double routeScore = normalizedScore * 0.85 + rankBoost * 0.15;
            HybridCandidate candidate = candidates.computeIfAbsent(candidateKey(result), key -> new HybridCandidate(result));
            if (vectorRoute) {
                candidate.vectorScore = Math.max(candidate.vectorScore, routeScore);
            } else {
                candidate.bm25Score = Math.max(candidate.bm25Score, routeScore);
            }
        }
    }

    private double maxScore(List<SearchResult> results) {
        return results.stream()
                .mapToDouble(SearchResult::score)
                .filter(score -> score > 0.0)
                .max()
                .orElse(0.0);
    }

    private String candidateKey(SearchResult result) {
        if (result.chunkId() != null) {
            return "id:" + result.chunkId();
        }
        return "content:" + result.source() + ":" + result.content();
    }

    private List<SearchResult> expandBestContext(List<SearchResult> ranked, int topK) {
        if (ranked.isEmpty()) {
            return ranked;
        }
        // 命中片段前后各补一段，减少切块边界导致的上下文断裂。
        SearchResult best = ranked.get(0);
        SearchResult expanded = expand(best);
        List<SearchResult> results = new ArrayList<>();
        results.add(expanded);
        ranked.stream()
                .skip(1)
                .filter(result -> !sameChunk(result, expanded))
                .limit(Math.max(0, topK - 1))
                .forEach(results::add);
        return results;
    }

    private SearchResult expand(SearchResult result) {
        if (result.chunkId() == null) {
            return result;
        }
        return knowledgeChunkRepository.findById(result.chunkId())
                .map(chunk -> {
                    List<KnowledgeChunk> neighbors = knowledgeChunkRepository
                            .findBySourceAndSourceIndexBetweenOrderBySourceIndexAsc(
                                    chunk.getSource(),
                                    Math.max(0, chunk.getSourceIndex() - 1),
                                    chunk.getSourceIndex() + 1);
                    String expandedContent = String.join("\n\n", neighbors.stream()
                            .filter(neighbor -> !neighbor.isNeedsReview())
                            .map(this::withSourceLocation)
                            .toList());
                    return new SearchResult(chunk.getId(), chunk.getSource(), expandedContent, result.score());
                })
                .orElse(result);
    }

    private String withSourceLocation(KnowledgeChunk chunk) {
        if (chunk.getPageNumber() == null) return chunk.getContent();
        return "[" + chunk.getSource() + " 第" + chunk.getPageNumber() + "页 / "
                + chunk.getBlockType() + "]\n" + chunk.getContent();
    }

    private boolean sameChunk(SearchResult result, SearchResult expanded) {
        return result.chunkId() != null && result.chunkId().equals(expanded.chunkId());
    }

    private List<Double> safeEmbedding(String text) {
        try {
            return embeddingClient.embed(text);
        } catch (Exception ignored) {
            // embedding 失败不影响知识库可用性，后续会回退到本地检索。
            return List.of();
        }
    }

    private String serializeEmbedding(List<Double> embedding) {
        if (embedding == null || embedding.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(embedding);
        } catch (Exception ignored) {
            return null;
        }
    }

    private List<Double> parseEmbedding(String embeddingJson) {
        if (embeddingJson == null || embeddingJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(embeddingJson, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left.isEmpty() || right.isEmpty() || left.size() != right.size()) {
            return 0.0;
        }
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.size(); i++) {
            double a = left.get(i);
            double b = right.get(i);
            dot += a * b;
            leftNorm += a * a;
            rightNorm += b * b;
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private static class HybridCandidate {
        private final SearchResult result;
        private double vectorScore;
        private double bm25Score;

        private HybridCandidate(SearchResult result) {
            this.result = result;
        }

        private SearchResult toSearchResult() {
            double score = vectorScore * VECTOR_WEIGHT + bm25Score * BM25_WEIGHT;
            return new SearchResult(result.chunkId(), result.source(), result.content(), score);
        }
    }
}
