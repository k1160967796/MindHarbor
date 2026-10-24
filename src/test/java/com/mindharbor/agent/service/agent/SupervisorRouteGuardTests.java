package com.mindharbor.agent.service.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.mindharbor.agent.domain.ChatSession;
import com.mindharbor.agent.domain.IntentType;
import com.mindharbor.agent.domain.UserAccount;
import com.mindharbor.agent.service.IntentClassifier;
import com.mindharbor.agent.service.PsychologicalAssessmentService;
import com.mindharbor.agent.service.RouteGuard;
import com.mindharbor.agent.service.ai.AiClient;
import com.mindharbor.agent.service.ai.AiMessage;
import com.mindharbor.agent.service.knowledge.KnowledgeService;
import com.mindharbor.agent.config.MindHarborProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupervisorRouteGuardTests {
    @Test
    void homeworkAfterHighRiskReachesActualRiskAssessmentWithOriginalHistory() {
        AiClient ai = mock(AiClient.class);
        when(ai.complete(any())).thenReturn("{\"emotion\":\"HIGH_RISK\",\"risk\":\"HIGH\",\"summary\":\"近期风险未解除\"}");
        var history = List.of(AiMessage.user("我想死"), AiMessage.assistant("先关注安全。"));
        // modelHistory deliberately contains only the current turn: risk review must use original history.
        AgentContext context = context("作业太多了写不完", history);
        var decision = new SupervisorAgent(new IntentClassifier(ai), new RouteGuard()).act(context);
        assertThat(context.intent()).isEqualTo(IntentType.CONSULT);
        assertThat(context.knowledgeHandled()).isFalse();
        assertThat(context.riskAssessed()).isFalse();
        assertThat(decision.observation()).contains("candidate=CHAT", "RECENT_HIGH_RISK_REVIEW");
        verifyNoInteractions(ai);

        context.markKnowledgeHandled();
        var risk = new RiskGuardianAgent(new PsychologicalAssessmentService(ai, new com.fasterxml.jackson.databind.ObjectMapper()));
        assertThat(risk.supports(context)).isTrue();
        assertThat(risk.act(context).observation()).contains("recentHighRisk=true");
        assertThat(context.riskLevel()).isEqualTo(com.mindharbor.agent.domain.RiskLevel.HIGH);
        assertThat(context.riskAssessed()).isTrue();
        verify(ai).complete(argThat(messages -> messages.stream().anyMatch(message ->
                message.content().contains("我想死") && message.content().contains("作业太多了写不完"))));
    }

    @Test
    void modelChatMisclassificationStillReachesKnowledgeAndRiskWithoutExtraModelCall() {
        AiClient ai = mock(AiClient.class);
        when(ai.complete(any())).thenReturn("CHAT");
        SupervisorAgent supervisor = new SupervisorAgent(new IntentClassifier(ai), new RouteGuard());
        AgentContext context = context("我最近很焦虑，晚上睡不着", List.of());

        AgentDecision decision = supervisor.act(context);

        assertThat(context.intent()).isEqualTo(IntentType.CONSULT);
        assertThat(context.intentRouted()).isTrue();
        assertThat(context.knowledgeHandled()).isFalse();
        assertThat(context.riskAssessed()).isFalse();
        KnowledgeAgent knowledge = new KnowledgeAgent(mock(KnowledgeService.class), new MindHarborProperties(), ai);
        assertThat(knowledge.supports(context)).isTrue();
        context.markKnowledgeHandled();
        assertThat(new RiskGuardianAgent(mock(PsychologicalAssessmentService.class)).supports(context)).isTrue();
        assertThat(decision.observation()).contains("candidate=CHAT", "intent=CONSULT", "CURRENT_CONSULT_SIGNAL");
        verify(ai, times(1)).complete(any());
        verifyNoMoreInteractions(ai);
    }

    @Test
    void protectsFollowUpWhenModelMisclassifiesGenericQuestion() {
        AiClient ai = mock(AiClient.class);
        when(ai.complete(any())).thenReturn("CHAT");
        AgentContext context = context("如何解决这个问题", List.of(AiMessage.user("我考试压力很大")));
        new SupervisorAgent(new IntentClassifier(ai), new RouteGuard()).act(context);
        assertThat(context.intent()).isEqualTo(IntentType.CONSULT);
        assertThat(context.riskAssessed()).isFalse();
        verify(ai).complete(any());
    }

    @Test
    void normalChatStillSkipsKnowledgeAndRisk() {
        AiClient ai = mock(AiClient.class);
        AgentContext context = context("帮我解释 Java 接口", List.of());
        new SupervisorAgent(new IntentClassifier(ai), new RouteGuard()).act(context);
        assertThat(context.intent()).isEqualTo(IntentType.CHAT);
        assertThat(context.knowledgeHandled()).isTrue();
        assertThat(context.riskAssessed()).isTrue();
        verifyNoInteractions(ai);
    }

    private AgentContext context(String input, List<AiMessage> history) {
        AgentContext context = new AgentContext(new UserAccount(), new ChatSession(), input, input);
        context.setPreviousHistory(history);
        context.setModelHistory(List.of(AiMessage.user(input)));
        context.markMemoryLoaded();
        return context;
    }
}
