package com.mindharbor.agent.service.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindharbor.agent.domain.*;
import com.mindharbor.agent.service.ReplyStore;
import jakarta.persistence.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Request row first, checkpoint row second: shared lease and consistent lock order. */
@Service
public class AgentCheckpointStore {
    public static final int MAX_STEPS=8,MAX_ATTEMPTS=3;
    private final EntityManager em;private final TransactionTemplate tx;private final ObjectMapper json;
    @Value("${mindharbor.agent.preparation-seconds:300}") private long preparationSeconds=300;
    public AgentCheckpointStore(EntityManager em,TransactionTemplate tx,ObjectMapper json) {this.em=em;this.tx=tx;this.json=json;}
    public record State(AgentContextSnapshot snapshot,long revision,int completedSteps,int attempts,Instant deadline,List<AgentStep> steps) {}
    public record Attempt(long revision,int step,int attempt,AgentName agent) {}
    public static class LostLease extends IllegalStateException {public LostLease(){super("Agent execution lease lost");}}
    public static class Terminal extends IllegalStateException {public Terminal(String reason){super(reason);}}

    /** Caller is ReplyStore.open's transaction; initial snapshot and reply are created atomically. */
    public void initialize(ChatMessage message) {
        AgentCheckpoint c=new AgentCheckpoint();c.requestId=message.getRequestId();c.userId=message.getUser().getId();
        c.sessionId=message.getSession().getId();c.deadline=Instant.now().plusSeconds(preparationSeconds);
        c.snapshotJson=encode(AgentContextSnapshot.capture(new AgentContext(message.getUser(),message.getSession(),null,null)));
        em.persist(c);
    }
    public String recoveryProblem(ChatMessage message) {
        var c=em.find(AgentCheckpoint.class,message.getRequestId(),LockModeType.PESSIMISTIC_WRITE);
        if(c==null)return "缺少准备检查点，请重新提交消息";
        String problem=null;
        if(c.schemaVersion!=1 || c.workflowVersion!=1)problem="检查点版本不兼容，请重新提交消息";
        else if(!c.deadline.isAfter(Instant.now()))problem="准备阶段时间预算已用尽，请重新提交消息";
        else if(c.status.equals("FAILED"))problem="Agent 步骤已失败，请重新提交消息";
        else if(c.stepAttempts>=MAX_ATTEMPTS && !c.status.equals("READY"))problem="Agent 步骤已达到三次尝试上限";
        if(problem!=null){c.status="FAILED";c.updatedAt=Instant.now();}
        return problem;
    }
    public State load(ReplyStore.Ticket ticket) {
        return tx.execute(s -> {
            owned(ticket);
            var c=checkpoint(ticket);
            if(c.schemaVersion!=1 || c.workflowVersion!=1)throw new Terminal("Unsupported checkpoint version");
            if(!c.deadline.isAfter(Instant.now()))throw new Terminal("Preparation deadline exceeded");
            if(c.status.equals("FAILED") || c.status.equals("COMMITTED"))throw new Terminal("Checkpoint is terminal");
            if(c.status.equals("RUNNING")) {
                if(Objects.equals(c.lastOwner,ticket.owner()))throw new LostLease();
                var step=step(c.requestId,c.completedSteps+1);
                if(step!=null){step.status="INTERRUPTED";step.errorType="WorkerLeaseExpired";step.completedAt=Instant.now();}
                c.status="RETRYING";
            }
            return state(c);
        });
    }
    public Attempt begin(ReplyStore.Ticket ticket,State expected,AgentName agent,long timeoutMillis) {
        return tx.execute(s -> {
            ChatMessage reply=owned(ticket);var c=checkpoint(ticket);
            if(c.revision!=expected.revision || c.status.equals("RUNNING"))throw new LostLease();
            if(!c.deadline.isAfter(Instant.now()))throw new Terminal("Preparation deadline exceeded");
            if(c.completedSteps>=MAX_STEPS)throw new Terminal("Agent step limit reached");
            if(c.stepAttempts>=MAX_ATTEMPTS)throw new Terminal("Agent attempt limit reached");
            c.stepAttempts++;c.status="RUNNING";c.lastOwner=ticket.owner();c.updatedAt=Instant.now();
            reply.setLeaseUntil(Instant.now().plusMillis(timeoutMillis+15000));
            var row=step(c.requestId,c.completedSteps+1);
            if(row==null){row=new AgentExecutionStep();row.requestId=c.requestId;row.stepNumber=c.completedSteps+1;em.persist(row);}
            row.agent=agent.name();row.status="RUNNING";row.attempts=c.stepAttempts;row.startedAt=Instant.now();
            row.completedAt=null;row.errorType=null;
            return new Attempt(c.revision,row.stepNumber,c.stepAttempts,agent);
        });
    }
    public void complete(ReplyStore.Ticket ticket,Attempt attempt,AgentContext context,AgentDecision decision) {
        String snapshot=encode(AgentContextSnapshot.capture(context));
        tx.executeWithoutResult(s -> {
            owned(ticket);var c=checkpoint(ticket);verify(c,attempt);
            if(!c.deadline.isAfter(Instant.now()))throw new Terminal("Preparation deadline exceeded");
            var row=step(c.requestId,attempt.step);row.status="SUCCESS";row.action=decision.action().name();
            row.observation=shorten(decision.observation(),2000);row.completedAt=Instant.now();
            row.durationMs=Duration.between(row.startedAt,row.completedAt).toMillis();
            c.snapshotJson=snapshot;c.completedSteps=attempt.step;c.stepAttempts=0;c.revision++;
            c.status=context.finished()?"READY":"PENDING";c.errorType=null;c.updatedAt=Instant.now();
        });
    }
    public boolean failedAttempt(ReplyStore.Ticket ticket,Attempt attempt,Throwable error,boolean retryable) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            owned(ticket);var c=checkpoint(ticket);verify(c,attempt);
            boolean retry=retryable && c.stepAttempts<MAX_ATTEMPTS && c.deadline.isAfter(Instant.now());
            c.status=retry?"RETRYING":"FAILED";c.errorType=error.getClass().getSimpleName();c.updatedAt=Instant.now();
            var row=step(c.requestId,attempt.step);row.status=retry?"RETRYING":"FAILED";row.errorType=c.errorType;
            row.completedAt=Instant.now();row.durationMs=Duration.between(row.startedAt,row.completedAt).toMillis();
            return retry;
        }));
    }
    public void terminal(ReplyStore.Ticket ticket,Throwable error) {
        tx.executeWithoutResult(s -> {owned(ticket);var c=checkpoint(ticket);
            if(!c.status.equals("FAILED"))c.errorType=error.getClass().getSimpleName();
            c.status="FAILED";c.updatedAt=Instant.now();
            var row=step(c.requestId,c.completedSteps+1);
            if(row!=null && row.status.equals("RUNNING")){row.status="FAILED";row.errorType=c.errorType;row.completedAt=Instant.now();}
        });
    }
    /** Called within the same transaction that commits prompt, report and outbox tasks. */
    public void committed(ChatMessage reply) {
        var c=em.find(AgentCheckpoint.class,reply.getRequestId(),LockModeType.PESSIMISTIC_WRITE);
        if(c!=null){c.status="COMMITTED";c.updatedAt=Instant.now();}
    }
    private void verify(AgentCheckpoint c,Attempt a) {
        if(c.revision!=a.revision || c.completedSteps+1!=a.step || c.stepAttempts!=a.attempt || !c.status.equals("RUNNING"))throw new LostLease();
    }
    private ChatMessage owned(ReplyStore.Ticket t) {
        var m=em.find(ChatMessage.class,t.view().replyId(),LockModeType.PESSIMISTIC_WRITE);
        if(m==null || !m.getUser().getId().equals(t.user().getId()) || t.owner()==null
                || !t.owner().equals(m.getExecutionOwner()) || m.getLeaseUntil()==null || !m.getLeaseUntil().isAfter(Instant.now()))throw new LostLease();
        return m;
    }
    private AgentCheckpoint checkpoint(ReplyStore.Ticket t) {
        var c=em.find(AgentCheckpoint.class,t.view().requestId(),LockModeType.PESSIMISTIC_WRITE);
        if(c==null || !c.userId.equals(t.user().getId()) || !c.sessionId.equals(t.session().getId()))throw new Terminal("Checkpoint unavailable");
        return c;
    }
    private AgentExecutionStep step(String id,int number) {
        return em.createQuery("select s from AgentExecutionStep s where s.requestId=:id and s.stepNumber=:n",AgentExecutionStep.class)
                .setParameter("id",id).setParameter("n",number).getResultStream().findFirst().orElse(null);
    }
    private State state(AgentCheckpoint c) {
        try {
            var snapshot=json.readValue(c.snapshotJson,AgentContextSnapshot.class);
            var steps=em.createQuery("select s from AgentExecutionStep s where s.requestId=:id and s.status='SUCCESS' order by s.stepNumber",AgentExecutionStep.class)
                    .setParameter("id",c.requestId).getResultList().stream()
                    .map(r -> new AgentStep(r.stepNumber,AgentName.valueOf(r.agent),AgentAction.valueOf(r.action),r.observation,r.completedAt)).toList();
            return new State(snapshot,c.revision,c.completedSteps,c.stepAttempts,c.deadline,steps);
        } catch(Exception e){throw new Terminal("Invalid checkpoint snapshot");}
    }
    private String encode(AgentContextSnapshot snapshot) {
        try {String value=json.writeValueAsString(snapshot);if(value.length()>512000)throw new Terminal("Checkpoint too large");return value;}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new Terminal("Checkpoint serialization failed");}
    }
    private String shorten(String value,int max){return value==null?"":value.substring(0,Math.min(max,value.length()));}
    public List<String> recoverableRequests() {
        return tx.execute(s -> em.createQuery("select m.requestId from ChatMessage m where m.promptJson is null and m.executionOwner is null and m.replyStatus=com.mindharbor.agent.domain.ReplyStatus.INTERRUPTED order by m.id",String.class).setMaxResults(10).getResultList());
    }
    public record Progress(String requestId,String status,long revision,int completedSteps,int currentAttempts,Instant deadline,String errorType,List<StepView> steps) {}
    public record StepView(int step,String agent,String status,int attempts,long durationMs,String errorType) {}
    public List<Progress> latest() {
        return tx.execute(s -> em.createQuery("select c from AgentCheckpoint c order by c.updatedAt desc",AgentCheckpoint.class).setMaxResults(50).getResultList().stream().map(c ->
            new Progress(c.requestId,c.status,c.revision,c.completedSteps,c.stepAttempts,c.deadline,c.errorType,
                em.createQuery("select s from AgentExecutionStep s where s.requestId=:id order by s.stepNumber",AgentExecutionStep.class).setParameter("id",c.requestId).getResultList().stream()
                .map(r->new StepView(r.stepNumber,r.agent,r.status,r.attempts,r.durationMs,r.errorType)).toList())).toList());
    }
}
