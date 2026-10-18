package com.mindbridge.agent.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.MindBridgeProperties;
import com.mindbridge.agent.domain.*;
import com.mindbridge.agent.service.ai.*;
import com.mindbridge.agent.service.knowledge.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.mindbridge.agent.service.knowledge.KnowledgeReview.Coverage;

class KnowledgeAgentOptimizationTests {
    MindBridgeProperties properties = new MindBridgeProperties();
    KnowledgeService service = mock(KnowledgeService.class);
    AiClient ai = mock(AiClient.class);
    List<SearchResult> first = List.of(new SearchResult(1L, "sleep.md", "考试睡眠支持", .9));
    KnowledgeAgent agent = new KnowledgeAgent(service, properties, ai);

    @BeforeEach void setup() {
        when(service.recall(anyString(), anyInt(), any())).thenReturn(new KnowledgeCandidates(first, List.of()));
        KnowledgeReranker reviewer = new KnowledgeReranker(properties, ai, new ObjectMapper());
        when(service.review(anyString(), anyString(), anyString(), anyList(), anyInt(), any()))
                .thenAnswer(call -> reviewer.review(call.getArgument(0), call.getArgument(1), call.getArgument(2),
                        call.getArgument(3), call.getArgument(4), call.getArgument(5)));
        when(service.materialize(anyList(), anyInt())).thenAnswer(call -> call.getArgument(0));
    }

    @Test void completeQuestionUsesExactlyOneModelCallAndPropagatesCoverage() {
        when(ai.completeWithin(anyList(), any(), anyMap(), anyInt())).thenReturn(review("SUFFICIENT", "null"));
        AgentContext context = context("最近考试压力很大，晚上睡不着，怎么缓解？", List.of());
        var decision = agent.act(context);
        verify(ai, times(1)).completeWithin(anyList(), any(), anyMap(), anyInt());
        verify(ai, never()).complete(anyList());
        assertThat(context.knowledgeStatus()).contains("SUFFICIENT");
        assertThat(context.retrievedKnowledge()).hasSize(1);
        assertThat(decision.observation()).contains("rewritten=false", "retry=false", "elapsedMs=");
    }

    @Test void contextualFollowUpRewritesThenReviews() {
        when(ai.completeWithin(anyList(), any(), anyMap(), anyInt()))
                .thenReturn("考试压力 睡眠支持", review("SUFFICIENT", "null"));
        AgentContext context = context("那我该怎么办？", List.of(AiMessage.user("最近考试压力很大")));
        agent.act(context);
        verify(ai, times(2)).completeWithin(anyList(), any(), anyMap(), anyInt());
        verify(service).recall(eq("考试压力 睡眠支持"), anyInt(), any());
        assertThat(agent.needsRewrite("考试焦虑", context.previousHistory())).isFalse();
    }

    @Test void secondRecallMergesFirstEvidenceAndNeverLoopsAgain() {
        when(service.recall(anyString(), anyInt(), any())).thenReturn(new KnowledgeCandidates(first, List.of()),
                new KnowledgeCandidates(List.of(first.get(0), new SearchResult(2L, "help.md", "预约支持", .8)), List.of()));
        when(ai.completeWithin(anyList(), any(), anyMap(), anyInt())).thenReturn(
                review("PARTIAL", "\"心理咨询预约\""),
                "{\"ranked\":[{\"chunkId\":2,\"score\":0.9},{\"chunkId\":1,\"score\":0.8}],\"coverage\":\"PARTIAL\",\"retryQuery\":\"再查一次\"}");
        AgentContext context = context("我最近考试压力很大", List.of());
        agent.act(context);
        assertThat(context.retrievedKnowledge()).extracting(SearchResult::chunkId).containsExactly(2L, 1L);
        verify(service, times(2)).recall(anyString(), anyInt(), any());
        verify(ai, times(2)).completeWithin(anyList(), any(), anyMap(), anyInt());
    }

    @Test void failedSecondReviewKeepsFirstEvidence() {
        when(service.recall(anyString(), anyInt(), any())).thenReturn(new KnowledgeCandidates(first, List.of()),
                new KnowledgeCandidates(List.of(new SearchResult(2L, "help.md", "预约支持", 1)), List.of()));
        when(ai.completeWithin(anyList(), any(), anyMap(), anyInt())).thenReturn(review("PARTIAL", "\"心理咨询预约\""), "bad json");
        AgentContext context = context("我最近考试压力很大", List.of());
        agent.act(context);
        assertThat(context.retrievedKnowledge()).extracting(SearchResult::chunkId).containsExactly(1L, 2L);
        assertThat(context.knowledgeStatus()).contains("UNKNOWN", "REVIEW_INVALID_JSON");
    }

    @Test void failedReviewDoesNotTriggerRetryOrPretendSufficient() {
        when(ai.completeWithin(anyList(), any(), anyMap(), anyInt())).thenThrow(new IllegalStateException("timeout"));
        AgentContext context = context("我最近考试压力很大", List.of());
        agent.act(context);
        assertThat(context.knowledgeStatus()).contains("UNKNOWN", "REVIEW_FAILED");
        verify(service, times(1)).recall(anyString(), anyInt(), any());
    }

    @Test void insufficientBudgetPreventsSecondRecall() {
        properties.getKnowledge().setRetryMinRemainingMs(5000);
        when(ai.completeWithin(anyList(), any(), anyMap(), anyInt())).thenReturn(review("PARTIAL", "\"心理咨询预约\""));
        AgentContext context = context("我最近考试压力很大", List.of());
        agent.act(context);
        assertThat(context.knowledgeStatus()).contains("PARTIAL", "RETRY_BUDGET_INSUFFICIENT");
        verify(service, times(1)).recall(anyString(), anyInt(), any());
    }

    @Test void emptyRecallCanRewriteOnceAndRecover() {
        when(service.recall(anyString(), anyInt(), any())).thenReturn(new KnowledgeCandidates(List.of(), List.of()),
                new KnowledgeCandidates(first, List.of()));
        when(ai.completeWithin(anyList(), any(), anyMap(), anyInt())).thenReturn("考试压力 睡眠支持", review("SUFFICIENT", "null"));
        AgentContext context = context("我最近状态不好", List.of());
        agent.act(context);
        assertThat(context.knowledgeStatus()).contains("SUFFICIENT");
        verify(service, times(2)).recall(anyString(), anyInt(), any());
    }

    private String review(String coverage, String retry) {
        return "{\"ranked\":[{\"chunkId\":1,\"score\":0.9}],\"coverage\":\"" + coverage + "\",\"retryQuery\":" + retry + "}";
    }

    private AgentContext context(String input, List<AiMessage> history) {
        AgentContext context = new AgentContext(new UserAccount(), new ChatSession(), input, input);
        context.setPreviousHistory(history);
        context.setIntent(IntentType.CONSULT);
        context.markIntentRouted();
        return context;
    }
}
