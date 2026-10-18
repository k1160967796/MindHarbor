package com.mindharbor.agent.service.agent;

import com.mindharbor.agent.domain.*;
import com.mindharbor.agent.service.ai.*;
import com.mindharbor.agent.service.knowledge.SearchResult;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CounselorCompactPlanTests {
    private final AiClient ai = mock(AiClient.class);
    private final CounselorAgent agent = new CounselorAgent(ai);

    @ParameterizedTest
    @EnumSource(value = RiskLevel.class, names = {"LOW", "HIGH"})
    void validPlanIsCompactAndBackendKeepsSafetyConstraints(RiskLevel risk) {
        when(ai.completeWithin(anyList(), any(), anyMap(), anyInt())).thenReturn(
                "{\"focus\":\"回应作业带来的压力\",\"action\":\"把任务分为一个可执行的小步骤\"}");
        var context = context(risk);
        var decision = agent.act(context);
        assertThat(context.responsePlan().length()).isLessThanOrEqualTo(160);
        assertThat(context.responsePlan()).contains("风险要求", "要点", "行动", "禁止", "诊断开药", "危险操作细节");
        if (risk == RiskLevel.HIGH) {
            assertThat(context.responsePlan()).contains("确认当下安全", "紧急帮助");
            assertThat(context.responseMessages().get(0).content()).contains("高风险处理规则");
        }
        assertThat(decision.observation()).contains("planSource=MODEL", "planChars=", "elapsedMs=");
        verify(ai).completeWithin(anyList(), eq(Duration.ofSeconds(2)), anyMap(), eq(128));
        verifyNoMoreInteractions(ai);
    }

    @ParameterizedTest
    @ValueSource(strings = {"这是给学生的完整回答", "{}", "{\"focus\":\"\",\"action\":\"行动\"}",
            "{\"focus\":\"有效要点\",\"action\":\"这个行动方向过长所以不能被当作合格的简短提纲接受而应该被拒绝并回退到后端生成的安全提纲\"}"})
    void malformedOrLongOutputUsesShortSafeFallback(String response) {
        when(ai.completeWithin(anyList(), any(), anyMap(), anyInt())).thenReturn(response);
        var context = context(RiskLevel.HIGH);
        assertThat(agent.act(context).observation()).contains("planSource=FALLBACK_FAILED");
        assertThat(context.responsePlan()).contains("确认当下安全", "禁止");
        assertThat(context.responsePlan().length()).isLessThanOrEqualTo(160);
    }

    @Test
    void timeoutFallsBackWithoutRetryingOrBlockingTheAnswerPath() {
        when(ai.completeWithin(anyList(), any(), anyMap(), anyInt()))
                .thenThrow(new IllegalStateException(new java.util.concurrent.TimeoutException()));
        var context = context(RiskLevel.HIGH);
        assertThat(agent.act(context).observation()).contains("planSource=FALLBACK_TIMEOUT");
        assertThat(context.responsePlanned()).isTrue();
        assertThat(context.responseMessages()).isNotEmpty();
        verify(ai).completeWithin(anyList(), any(), anyMap(), anyInt());
        verifyNoMoreInteractions(ai);
    }

    @Test
    void planningExcerptsDoNotReplaceFullEvidenceOrHistoryInFinalAnswer() {
        when(ai.completeWithin(anyList(), any(), anyMap(), anyInt())).thenReturn(
                "{\"focus\":\"回应当前困扰\",\"action\":\"给出支持步骤\"}");
        var context = context(RiskLevel.HIGH);
        String full = "知识".repeat(500) + "完整原文末尾标记";
        context.setRetrievedKnowledge(List.of(new SearchResult(1L, "source", full, .9)));
        context.setMemoryBrief("记忆".repeat(500));
        context.setModelHistory(List.of(AiMessage.user("历史风险原文"), AiMessage.user("作业太多了写不完")));
        agent.act(context);
        verify(ai).completeWithin(argThat(messages -> messages.stream().noneMatch(m -> m.content().contains("完整原文末尾标记"))),
                any(), anyMap(), anyInt());
        assertThat(context.responseMessages().get(0).content()).contains(full);
        assertThat(context.responseMessages()).containsAll(context.modelHistory());
    }

    private AgentContext context(RiskLevel risk) {
        var context = new AgentContext(new UserAccount(), new ChatSession(), "作业太多了写不完", "作业太多了写不完");
        context.setIntent(IntentType.CONSULT);
        context.setRiskLevel(risk);
        context.markRiskAssessed();
        return context;
    }
}
