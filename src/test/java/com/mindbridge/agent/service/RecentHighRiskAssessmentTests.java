package com.mindbridge.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.domain.RiskLevel;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.ai.AiMessage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyList;

class RecentHighRiskAssessmentTests {
    private final AiClient ai = mock(AiClient.class);
    private final PsychologicalAssessmentService service = new PsychologicalAssessmentService(ai, new ObjectMapper());
    private final List<AiMessage> history = List.of(AiMessage.user("我想死"));

    @Test
    void failedReassessmentKeepsConservativeRisk() {
        when(ai.complete(anyList())).thenThrow(new IllegalStateException("model unavailable"));
        var result = service.assess("作业太多了写不完", history);
        assertThat(result.risk()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.summary()).contains("reassessment unavailable");
        assertThat(service.assess("作业太多了写不完", List.of()).risk()).isEqualTo(RiskLevel.LOW);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{}", "{\"emotion\":\"NORMAL\"}"})
    void invalidAssessmentCannotSilentlyDefaultToLow(String response) {
        when(ai.complete(anyList())).thenReturn(response);
        assertThat(service.assess("作业太多了写不完", history).risk()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void successfulReassessmentCanLowerRiskRatherThanPermanentlyLockingHigh() {
        when(ai.complete(anyList())).thenReturn("""
                {"emotion":"ANXIETY","emotionScore":2.0,"risk":"LOW","confidence":0.8,
                 "summary":"用户已明确安全且有人陪伴。","recentRiskResolved":true,"resolutionEvidence":"辅导员现在陪着我"}
                """);
        assertThat(service.assess("我已经安全了，辅导员现在陪着我", history).risk())
                .isEqualTo(RiskLevel.LOW);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"emotion\":\"NORMAL\",\"risk\":\"LOW\"}",
            "{\"emotion\":\"NORMAL\",\"risk\":\"LOW\",\"recentRiskResolved\":false,\"resolutionEvidence\":\"\"}",
            "{\"emotion\":\"NORMAL\",\"risk\":\"LOW\",\"recentRiskResolved\":true,\"resolutionEvidence\":\"辅导员现在陪着我\"}"
    })
    void lowRiskWithoutGroundedResolutionEvidenceIsNotAccepted(String response) {
        when(ai.complete(anyList())).thenReturn(response);
        assertThat(service.assess("作业太多了写不完", history).risk()).isEqualTo(RiskLevel.HIGH);
    }
}
