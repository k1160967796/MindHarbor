package com.mindharbor.agent.service.agent;

import com.mindharbor.agent.domain.ChatSession;
import com.mindharbor.agent.domain.UserAccount;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * MindHarbor Agent Loop 运行时。
 *
 * <p>每轮学生输入都会进入有限步循环：读取记忆、主控路由、知识检索、风险守护和回复规划。
 * 这里不是无限自主循环，而是受步数限制的安全 agent loop，适合心理安全场景。</p>
 */

@Service
public class AgentRuntimeService {

    private static final int MAX_STEPS = 8;

    private final List<MindHarborAgent> agents;
    @org.springframework.beans.factory.annotation.Autowired private AgentCheckpointStore checkpoints;
    @org.springframework.beans.factory.annotation.Value("${mindharbor.agent.step-timeout-ms:30000}") private long stepTimeoutMs=30000;
    @org.springframework.beans.factory.annotation.Value("${mindharbor.agent.retry-delay-ms:500}") private long retryDelayMs=500;
    private final java.util.concurrent.ExecutorService stepWorkers=new java.util.concurrent.ThreadPoolExecutor(
            4,4,0,java.util.concurrent.TimeUnit.MILLISECONDS,new java.util.concurrent.ArrayBlockingQueue<>(32),
            r -> {Thread t=new Thread(r,"agent-step");t.setDaemon(true);return t;},new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    public AgentRuntimeService(
            MemoryAgent memoryAgent,
            SupervisorAgent supervisorAgent,
            KnowledgeAgent knowledgeAgent,
            RiskGuardianAgent riskGuardianAgent,
            CompanionAgent companionAgent,
            CounselorAgent counselorAgent
    ) {
        // 顺序就是 Supervisor 架构下的协作优先级；每个 Agent 通过 supports 判断是否该接手。
        this.agents = List.of(
                memoryAgent,
                supervisorAgent,
                knowledgeAgent,
                riskGuardianAgent,
                companionAgent,
                counselorAgent);
    }

    public AgentRunResult run(UserAccount user, ChatSession session, String originalInput, String modelInput) {
        AgentContext context = new AgentContext(user, session, originalInput, modelInput);
        for (int step = 1; step <= MAX_STEPS && !context.finished(); step++) {
            MindHarborAgent agent = nextAgent(context);
            AgentDecision decision;
            try (var scope = com.mindharbor.agent.service.ai.ModelCallContext.use(
                    com.mindharbor.agent.service.ai.ModelCallContext.current().phase(agent.name().name()))) {
                decision = agent.act(context);
            }
            context.addStep(AgentStep.of(step, agent.name(), decision));
            if (decision.complete()) {
                context.finish();
            }
        }
        return AgentRunResult.from(context);
    }

    public AgentRunResult runRecoverable(com.mindharbor.agent.service.ReplyStore.Ticket ticket,String modelInput) {
        try {
            while(true) {
                var saved=checkpoints.load(ticket);
                AgentContext context=AgentContext.restore(ticket.user(),ticket.session(),ticket.input(),modelInput,saved.snapshot(),saved.steps());
                if(context.finished())return AgentRunResult.from(context);
                if(saved.completedSteps()>=MAX_STEPS)throw new AgentCheckpointStore.Terminal("Agent step limit reached");
                MindHarborAgent agent=agents.stream().filter(candidate -> candidate.supports(context)).findFirst()
                        .orElseThrow(() -> new AgentCheckpointStore.Terminal("No agent can handle saved context"));
                long remaining=java.time.Duration.between(java.time.Instant.now(),saved.deadline()).toMillis();
                if(remaining<=0)throw new AgentCheckpointStore.Terminal("Preparation deadline exceeded");
                long timeout=Math.min(stepTimeoutMs,remaining);
                var attempt=checkpoints.begin(ticket,saved,agent.name(),timeout);
                java.util.concurrent.Future<AgentDecision> future=null;
                AgentDecision decision;
                try {
                    future=stepWorkers.submit(() -> {
                        try(var scope=com.mindharbor.agent.service.ai.ModelCallContext.use(new com.mindharbor.agent.service.ai.ModelCallContext(
                                ticket.view().requestId(),ticket.view().sessionId(),agent.name().name(),ticket.view().generation(),attempt.attempt()))) {
                            return agent.act(context);
                        }
                    });
                    decision=future.get(timeout,java.util.concurrent.TimeUnit.MILLISECONDS);
                    if(decision==null)throw new IllegalArgumentException("Agent returned no decision");
                } catch(Exception failure) {
                    if(future!=null)future.cancel(true);
                    Throwable error=failure instanceof java.util.concurrent.ExecutionException?failure.getCause():failure;
                    if(failure instanceof InterruptedException){Thread.currentThread().interrupt();throw new IllegalStateException("Preparation interrupted",failure);}
                    if(!checkpoints.failedAttempt(ticket,attempt,error,retryable(error)))throw new AgentCheckpointStore.Terminal("Agent step failed: "+agent.name());
                    try {Thread.sleep(retryDelayMs*attempt.attempt());} catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                    continue; // Rebuild a fresh context; never reuse partially mutated state from a failed attempt.
                }
                context.addStep(AgentStep.of(attempt.step(),agent.name(),decision));
                if(decision.complete())context.finish();
                checkpoints.complete(ticket,attempt,context,decision);
            }
        } catch(AgentCheckpointStore.Terminal terminal) {
            try {checkpoints.terminal(ticket,terminal);} catch(AgentCheckpointStore.LostLease ignored) { }
            throw terminal;
        }
    }
    private boolean retryable(Throwable error) {
        for(Throwable c=error;c!=null;c=c.getCause()) {
            if(c instanceof java.io.IOException || c instanceof java.util.concurrent.TimeoutException
                    || c instanceof java.util.concurrent.RejectedExecutionException
                    || c instanceof org.springframework.web.reactive.function.client.WebClientRequestException
                    || c instanceof org.springframework.dao.TransientDataAccessException)return true;
            if(c instanceof org.springframework.web.reactive.function.client.WebClientResponseException http
                    && (http.getStatusCode().is5xxServerError() || http.getStatusCode().value()==429))return true;
        }
        return false;
    }
    @jakarta.annotation.PreDestroy public void close(){stepWorkers.shutdownNow();}

    private MindHarborAgent nextAgent(AgentContext context) {
        return agents.stream()
                .filter(agent -> agent.supports(context))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No agent can handle current context."));
    }
}
