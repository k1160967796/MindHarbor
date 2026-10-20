package com.mindharbor.agent.service.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindharbor.agent.config.MindHarborProperties;
import com.mindharbor.agent.domain.*;
import com.mindharbor.agent.repository.KnowledgeChunkRepository;
import com.mindharbor.agent.service.agent.*;
import com.mindharbor.agent.service.ai.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.core.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.mindharbor.agent.service.knowledge.KnowledgeReview.Coverage;

class KnowledgeTagCacheTests {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String,String> values = mock(ValueOperations.class);
    KnowledgeChunkRepository repository = mock(KnowledgeChunkRepository.class);
    Map<String,String> storage = new HashMap<>();
    KnowledgeTagCache cache = new KnowledgeTagCache(redis, repository, new ObjectMapper(), true, 1800);
    KnowledgeChunk chunk = new KnowledgeChunk();
    List<SearchResult> candidates = List.of(new SearchResult(1L, "public.md", "公共睡眠与考试应对知识", .9));
    KnowledgeReview sufficient = new KnowledgeReview(candidates, Coverage.SUFFICIENT, null, "REVIEWED");
    KnowledgeService knowledge = mock(KnowledgeService.class);
    AiClient ai = mock(AiClient.class);
    MindHarborProperties properties = new MindHarborProperties();
    KnowledgeAgent agent = new KnowledgeAgent(knowledge, properties, ai, cache);

    @BeforeEach void setup() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(c -> storage.get(c.getArgument(0)));
        doAnswer(c -> {storage.put(c.getArgument(0), c.getArgument(1));return null;})
                .when(values).set(anyString(), anyString(), any(Duration.class));
        when(redis.delete(anyString())).thenAnswer(c -> storage.remove(c.getArgument(0)) != null);
        ReflectionTestUtils.setField(chunk, "id", 1L);
        chunk.setSource("public.md"); chunk.setContent(candidates.get(0).content());
        when(repository.findAllById(any())).thenReturn(List.of(chunk));
        when(knowledge.recall(anyString(), anyInt(), any())).thenReturn(new KnowledgeCandidates(candidates, List.of()));
        when(knowledge.review(anyString(), anyString(), anyString(), anyList(), anyInt(), any())).thenReturn(sufficient);
        when(knowledge.materialize(anyList(), anyInt())).thenAnswer(c -> c.getArgument(0));
    }

    private AgentContext context(String text) {
        AgentContext context = new AgentContext(new UserAccount(), new ChatSession(), text, text);
        context.setIntent(IntentType.CONSULT);context.markIntentRouted();
        return context;
    }

    @Test void threeSupportedLabelsUseDeterministicPublicQueries() {
        assertThat(cache.plan(context("我明天考试，太焦虑了睡不着怎么办")).tag()).isEqualTo("考试|焦虑|入睡困难");
        assertThat(cache.plan(context("快期末了很紧张，书看不进去怎么办")).tag()).isEqualTo("考试|焦虑|注意力难集中");
        assertThat(cache.plan(context("作业快截止了，越着急越不敢开始")).tag()).isEqualTo("学业任务|焦虑|拖延启动");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "明天考试睡不着", "考试不焦虑但睡不着", "考试没有焦虑只是失眠",
            "我考试并不紧张但睡不着", "考试没那么焦虑，睡不着", "考试焦虑但能睡着",
            "考试焦虑失眠，你教的方法没用", "考试焦虑失眠，已经几个月了",
            "考试焦虑失眠越来越严重", "考试焦虑睡不着，同时和室友发生矛盾",
            "考试焦虑睡不着，作业也拖延", "考试焦虑睡不着也看不进去",
            "考试焦虑失眠，不想活了", "那我该怎么办", "如果考试焦虑失眠怎么办",
            "我朋友考试焦虑失眠"})
    void uncertainComplexNegatedAndRiskQuestionsBypass(String text) {
        assertThat(cache.plan(context(text)).eligible()).isFalse();
    }

    @Test void recentRiskAndRiskRouteAlwaysBypass() {
        var c = context("考试焦虑睡不着");
        c.setPreviousHistory(List.of(AiMessage.user("我不想活了")));
        assertThat(cache.plan(c).eligible()).isFalse();
        c.setPreviousHistory(List.of()); c.setIntent(IntentType.RISK);
        assertThat(cache.plan(c).eligible()).isFalse();
    }

    @Test void twoStudentsReuseCandidatesButBothAreReviewedAndNoStudentTextIsStored() {
        var first = context("我明天考试，太焦虑了睡不着怎么办");
        first.setMemoryBrief("学生甲的私密画像");
        var second = context("临近考试很紧张，入睡困难怎么办");
        second.setMemoryBrief("学生乙的私密画像");
        var firstDecision = agent.act(first);
        var secondDecision = agent.act(second);
        assertThat(firstDecision.observation()).contains("MISS_STORED");
        assertThat(secondDecision.observation()).contains("cache=HIT");
        assertThat(second.knowledgeStatus()).doesNotContain("cache=");
        assertThat(second.riskAssessed()).isFalse(); // Cache never completes/skips risk assessment.
        verify(knowledge, times(1)).recall(eq("快考试了很焦虑，晚上睡不着怎么办"), anyInt(), any());
        verify(knowledge, times(2)).review(anyString(), anyString(), anyString(), anyList(), anyInt(), any());
        verifyNoInteractions(ai); // No additional LLM call for label extraction.
        assertThat(storage.values()).allSatisfy(value ->
                assertThat(value).doesNotContain("私密", "明天", "临近", "公共睡眠"));
        verify(values).set(anyString(), anyString(), eq(Duration.ofSeconds(1800)));
    }

    @Test void insufficientHitFallsBackEvenWithoutRetryQuery() {
        agent.act(context("考试焦虑睡不着"));
        clearInvocations(knowledge);
        when(knowledge.review(anyString(), anyString(), anyString(), anyList(), anyInt(), any()))
                .thenReturn(new KnowledgeReview(candidates, Coverage.PARTIAL, null, "REVIEWED"), sufficient);
        var next = context("明天考试焦虑失眠怎么办");
        var decision = agent.act(next);
        verify(knowledge).recall(eq(next.modelInput()), anyInt(), any());
        verify(knowledge, times(2)).review(anyString(), anyString(), anyString(), anyList(), anyInt(), any());
        assertThat(decision.observation()).contains("HIT_REJECTED", "SUFFICIENT");
        assertThat(storage).isEmpty();
    }

    @Test void failedReviewFallsBackAndNeverPoisonsCache() {
        when(knowledge.review(anyString(), anyString(), anyString(), anyList(), anyInt(), any()))
                .thenReturn(new KnowledgeReview(candidates, Coverage.UNKNOWN, null, "REVIEW_TIMEOUT"));
        agent.act(context("考试焦虑睡不着"));
        assertThat(storage).isEmpty();
        verify(knowledge, times(2)).recall(anyString(), anyInt(), any());
    }

    @Test void unavailableRedisFallsBackToOriginalQuery() {
        when(values.get(anyString())).thenThrow(new IllegalStateException("offline"));
        var c = context("考试焦虑睡不着");
        var decision = agent.act(c);
        assertThat(decision.observation()).contains("cache=ERROR");
        verify(knowledge).recall(eq(c.modelInput()), anyInt(), any());
    }

    @Test void contentChangesWithdrawalAndDeletedChunksInvalidateEntry() {
        var plan = cache.plan(context("考试焦虑睡不着"));
        var pool = new KnowledgeCandidates(candidates, List.of());
        assertThat(cache.store(plan, pool, sufficient)).isTrue();
        chunk.setContent("修改后的知识");
        assertThat(cache.lookup(plan).state()).isEqualTo("STALE");
        chunk.setContent(candidates.get(0).content());
        cache.store(plan, pool, sufficient);
        chunk.setNeedsReview(true);
        assertThat(cache.lookup(plan).state()).isEqualTo("STALE");
        chunk.setNeedsReview(false);
        cache.store(plan, pool, sufficient);
        when(repository.findAllById(any())).thenReturn(List.of());
        assertThat(cache.lookup(plan).state()).isEqualTo("STALE");
    }

    @Test void committedKnowledgeWriteRotatesGenerationAndRejectsInflightOldWrites() {
        var c = context("考试焦虑睡不着");
        var old = cache.plan(c);
        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.invalidateAfterCommit();
            assertThat(cache.plan(c).key()).isEqualTo(old.key());
            TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
            assertThat(cache.plan(c).key()).isNotEqualTo(old.key());
            assertThat(cache.store(old, new KnowledgeCandidates(candidates, List.of()), sufficient)).isFalse();
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }

    @Test void expiredCacheAndRestartAreColdAndDegradedPoolsAreNotStored() {
        var c = context("考试焦虑睡不着"); var plan = cache.plan(c);
        cache.store(plan, new KnowledgeCandidates(candidates, List.of()), sufficient);
        storage.clear(); // Simulates Redis TTL expiration.
        assertThat(cache.lookup(plan).state()).isEqualTo("MISS");
        assertThat(cache.store(plan, new KnowledgeCandidates(candidates, List.of("EMBEDDING_TIMEOUT")), sufficient)).isFalse();
        var restarted = new KnowledgeTagCache(redis, repository, new ObjectMapper(), true, 1800);
        assertThat(restarted.plan(c).key()).isNotEqualTo(plan.key());
    }

    @Test void rollbackDoesNotInvalidateAndDisabledCacheDoesNotReadRedis() {
        var c = context("考试焦虑睡不着");var before = cache.plan(c);
        TransactionSynchronizationManager.initSynchronization();
        try {
            cache.invalidateAfterCommit();
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
        assertThat(cache.plan(c).key()).isEqualTo(before.key());
        var disabled = new KnowledgeTagCache(redis, repository, new ObjectMapper(), false, 1800);
        assertThat(disabled.lookup(disabled.plan(c)).state()).isEqualTo("BYPASS_DISABLED");
        verifyNoInteractions(values);
    }

    @Test void malformedRedisAndWriteFailureDoNotBreakConsultation() {
        var plan = cache.plan(context("考试焦虑睡不着"));
        storage.put(plan.key(), "invalid json");
        assertThat(cache.lookup(plan).state()).isEqualTo("ERROR");
        doThrow(new IllegalStateException("offline")).when(values).set(anyString(), anyString(), any(Duration.class));
        assertThat(cache.store(plan, new KnowledgeCandidates(candidates, List.of()), sufficient)).isFalse();
    }
}
