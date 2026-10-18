package com.mindbridge.agent.service.agent;

import com.mindbridge.agent.domain.IntentType;
import com.mindbridge.agent.service.IntentClassifier;
import com.mindbridge.agent.service.RouteGuard;
import org.springframework.stereotype.Component;

/**
 * 主控 Agent。
 *
 * <p>Supervisor 负责把本轮输入路由到普通陪伴、心理咨询或风险守护链路。</p>
 */
@Component
public class SupervisorAgent implements MindBridgeAgent {

    private final IntentClassifier intentClassifier;
    private final RouteGuard routeGuard;

    public SupervisorAgent(IntentClassifier intentClassifier, RouteGuard routeGuard) {
        this.intentClassifier = intentClassifier;
        this.routeGuard = routeGuard;
    }

    @Override
    public AgentName name() {
        return AgentName.SUPERVISOR_AGENT;
    }

    @Override
    public boolean supports(AgentContext context) {
        return context.memoryLoaded() && !context.intentRouted();
    }

    @Override
    public AgentDecision act(AgentContext context) {
        IntentType candidate = intentClassifier.classify(context.modelInput(), context.modelHistory());
        RouteGuard.Result checked = routeGuard.validate(candidate, context.modelInput(), context.previousHistory());
        IntentType intent = checked.intent();
        context.setIntent(intent);
        context.markIntentRouted();
        if (intent == IntentType.CHAT) {
            context.markKnowledgeHandled();
            context.markRiskAssessed();
        }
        return AgentDecision.continueWith(AgentAction.ROUTE_INTENT,
                "candidate=%s; intent=%s; routeGuard=%s".formatted(candidate, intent, checked.reason()));
    }
}
