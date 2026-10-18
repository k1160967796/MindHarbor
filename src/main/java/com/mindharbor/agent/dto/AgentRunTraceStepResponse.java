package com.mindharbor.agent.dto;

import com.mindharbor.agent.domain.AgentRunTraceStep;
import com.mindharbor.agent.service.agent.AgentAction;
import com.mindharbor.agent.service.agent.AgentName;
import java.time.Instant;

/**
 * 管理员查看 agent run trace 时的单步轨迹。
 */
public record AgentRunTraceStepResponse(
        int step,
        AgentName agent,
        AgentAction action,
        String observation,
        Instant createdAt
) {
    public static AgentRunTraceStepResponse from(AgentRunTraceStep step) {
        return new AgentRunTraceStepResponse(
                step.getStepNumber(),
                step.getAgent(),
                step.getAction(),
                step.getObservation(),
                step.getCreatedAt());
    }
}
