package com.mindharbor.agent.service.agent;

import com.mindharbor.agent.domain.*;
import com.mindharbor.agent.service.PsychologyAssessment;
import com.mindharbor.agent.service.ai.AiMessage;
import com.mindharbor.agent.service.knowledge.SearchResult;
import java.util.List;

/** Explicit persistence format: data only, no Hibernate entities or service objects. */
public record AgentContextSnapshot(
        List<AiMessage> previousHistory,List<AiMessage> modelHistory,List<AiMessage> responseMessages,
        List<SearchResult> retrievedKnowledge,String memoryBrief,String knowledgeQuery,String knowledgeStatus,
        String knowledgePackId,String knowledgePackRevision,boolean forceNormalKnowledge,
        String responsePlan,IntentType intent,PsychologyAssessment assessment,RiskLevel riskLevel,
        AgentName responseAgent,boolean memoryLoaded,boolean intentRouted,boolean knowledgeHandled,
        boolean riskAssessed,boolean responsePlanned,boolean finished) {
    public AgentContextSnapshot {
        previousHistory=List.copyOf(previousHistory);modelHistory=List.copyOf(modelHistory);
        responseMessages=List.copyOf(responseMessages);retrievedKnowledge=List.copyOf(retrievedKnowledge);
        java.util.Objects.requireNonNull(memoryBrief);java.util.Objects.requireNonNull(responsePlan);
        java.util.Objects.requireNonNull(riskLevel);java.util.Objects.requireNonNull(responseAgent);
        if(intentRouted && intent==null || finished && (!responsePlanned || responseMessages.isEmpty()))
            throw new IllegalArgumentException("Incomplete context snapshot");
    }
    public static AgentContextSnapshot capture(AgentContext c) {
        return new AgentContextSnapshot(List.copyOf(c.previousHistory()),List.copyOf(c.modelHistory()),List.copyOf(c.responseMessages()),
                List.copyOf(c.retrievedKnowledge()),c.memoryBrief(),c.knowledgeQuery(),c.knowledgeStatus(),
                c.knowledgePackId(),c.knowledgePackRevision(),c.forceNormalKnowledge(),c.responsePlan(),c.intent(),
                c.assessment(),c.riskLevel(),c.responseAgent(),c.memoryLoaded(),c.intentRouted(),c.knowledgeHandled(),
                c.riskAssessed(),c.responsePlanned(),c.finished());
    }
}
