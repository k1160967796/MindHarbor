package com.mindharbor.agent.service;

import com.mindharbor.agent.domain.*;
import com.mindharbor.agent.dto.ChatRequest;
import com.mindharbor.agent.repository.*;
import com.mindharbor.agent.service.agent.*;
import com.mindharbor.agent.service.ai.*;
import com.mindharbor.agent.service.knowledge.SearchResult;
import com.mindharbor.agent.service.memory.*;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:agent-checkpoints;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "mindharbor.agent.recovery-enabled=false","mindharbor.agent.retry-delay-ms=0","mindharbor.memory.background-enabled=false",
    "mindharbor.memory.use-chroma=false","mindharbor.knowledge.use-chroma=false","mindharbor.tools.initial-delay-ms=3600000",
    "spring.ai.mcp.server.enabled=false","spring.ai.mcp.client.enabled=false"})
@AutoConfigureWebTestClient
class AgentCheckpointAcceptanceTests {
    @Autowired ChatService chat;@Autowired ReplyStore replies;@Autowired AgentCheckpointStore checkpoints;
    @Autowired AgentPreparationRecovery recovery;@Autowired AgentRuntimeService runtime;
    @Autowired UserAccountRepository users;@Autowired ChatMessageRepository messages;
    @Autowired EntityManager em;@Autowired TransactionTemplate tx;@Autowired WebTestClient web;
    @MockBean MemoryAgent memory;@MockBean SupervisorAgent supervisor;@MockBean KnowledgeAgent knowledge;
    @MockBean RiskGuardianAgent risk;@MockBean CompanionAgent companion;@MockBean CounselorAgent counselor;
    @MockBean AiClient ai;@MockBean ShortTermMemoryService shortMemory;
    Long user;
    @BeforeEach void setup(){
        tx.executeWithoutResult(s->{em.createQuery("delete from AgentExecutionStep").executeUpdate();em.createQuery("delete from AgentCheckpoint").executeUpdate();
            em.createQuery("update ChatMessage m set m.executionOwner=null,m.leaseUntil=null where m.executionOwner is not null").executeUpdate();});
        user=users.findByUsername("student").orElseThrow().getId();
        when(memory.name()).thenReturn(AgentName.MEMORY_AGENT);when(supervisor.name()).thenReturn(AgentName.SUPERVISOR_AGENT);
        when(knowledge.name()).thenReturn(AgentName.KNOWLEDGE_AGENT);when(risk.name()).thenReturn(AgentName.RISK_GUARDIAN_AGENT);
        when(companion.name()).thenReturn(AgentName.COMPANION_AGENT);when(counselor.name()).thenReturn(AgentName.COUNSELOR_AGENT);
        when(memory.supports(any())).thenAnswer(i->!((AgentContext)i.getArgument(0)).memoryLoaded());
        when(supervisor.supports(any())).thenAnswer(i->{AgentContext c=i.getArgument(0);return c.memoryLoaded()&&!c.intentRouted();});
        when(knowledge.supports(any())).thenAnswer(i->{AgentContext c=i.getArgument(0);return c.intentRouted()&&!c.knowledgeHandled();});
        when(risk.supports(any())).thenAnswer(i->{AgentContext c=i.getArgument(0);return c.knowledgeHandled()&&!c.riskAssessed();});
        when(counselor.supports(any())).thenAnswer(i->{AgentContext c=i.getArgument(0);return c.riskAssessed()&&c.knowledgeHandled()&&!c.responsePlanned();});
        when(memory.act(any())).thenAnswer(i->{AgentContext c=i.getArgument(0);c.setMemoryBrief("考试背景摘要");
            c.setPreviousHistory(List.of(AiMessage.user("最近考试压力很大")));c.setModelHistory(List.of(AiMessage.user("最近考试压力很大"),AiMessage.user(c.modelInput())));
            c.markMemoryLoaded();return AgentDecision.continueWith(AgentAction.READ_MEMORY,"loaded");});
        when(supervisor.act(any())).thenAnswer(i->{AgentContext c=i.getArgument(0);c.setIntent(IntentType.CONSULT);c.markIntentRouted();return AgentDecision.continueWith(AgentAction.ROUTE_INTENT,"CONSULT");});
        when(knowledge.act(any())).thenAnswer(i->{AgentContext c=i.getArgument(0);c.setKnowledgeQuery("考试焦虑");c.setRetrievedKnowledge(List.of(new SearchResult(1L,"fixture","知识内容",.9)));
            c.setKnowledgeStatus("SUFFICIENT");c.markKnowledgeHandled();return AgentDecision.continueWith(AgentAction.RETRIEVE_KNOWLEDGE,"found");});
        when(risk.act(any())).thenAnswer(i->assess(i.getArgument(0)));
        when(counselor.act(any())).thenAnswer(i->{AgentContext c=i.getArgument(0);c.setResponseAgent(AgentName.COUNSELOR_AGENT);c.setResponsePlan("考试支持策略");
            c.setResponseMessages(List.of(AiMessage.system(c.memoryBrief()+c.retrievedKnowledge().get(0).content()),AiMessage.user(c.modelInput())));
            c.markResponsePlanned();return AgentDecision.finish(AgentAction.PLAN_RESPONSE,"planned");});
        when(ai.streamAnswer(anyList(),anyInt())).thenReturn(Flux.just("完整回答"));
    }
    AgentDecision assess(AgentContext c){
        c.setAssessment(new PsychologyAssessment(EmotionLabel.ANXIETY,2,RiskLevel.LOW,.9,"考试压力"));c.setRiskLevel(RiskLevel.LOW);c.markRiskAssessed();
        return AgentDecision.continueWith(AgentAction.ASSESS_RISK,"LOW");
    }
    ChatRequest request(){return new ChatRequest(null,"考试焦虑睡不着怎么办",UUID.randomUUID().toString());}
    ReplyStore.Ticket open(){return replies.open(user,request());}
    AgentCheckpoint row(ReplyStore.Ticket t){return tx.execute(s->em.find(AgentCheckpoint.class,t.view().requestId()));}
    AgentContext context(ReplyStore.Ticket t,AgentCheckpointStore.State state){return AgentContext.restore(t.user(),t.session(),t.input(),t.input(),state.snapshot(),state.steps());}
    void commit(ReplyStore.Ticket t,MindHarborAgent agent){
        var state=checkpoints.load(t);var c=context(t,state);var attempt=checkpoints.begin(t,state,agent.name(),30000);
        var decision=agent.act(c);c.addStep(AgentStep.of(attempt.step(),agent.name(),decision));if(decision.complete())c.finish();checkpoints.complete(t,attempt,c,decision);
    }
    void firstThree(ReplyStore.Ticket t){commit(t,memory);commit(t,supervisor);commit(t,knowledge);}
    void expire(ReplyStore.Ticket t){tx.executeWithoutResult(s->{var m=em.find(ChatMessage.class,t.view().replyId());m.setLeaseUntil(Instant.EPOCH);});replies.recoverExpired();}
    void resume(ReplyStore.Ticket t){chat.resume(user,t.view().requestId(),null).collectList().block(Duration.ofSeconds(10));}
    long count(Class<?> entity){return tx.execute(s->em.createQuery("select count(x) from "+entity.getSimpleName()+" x",Long.class).getSingleResult());}

    @Test void crashAtRiskRestoresResultsWithoutRepeatingFirstThreeAgents(){
        var t=open();firstThree(t);
        var oldAttempt=checkpoints.begin(t,checkpoints.load(t),AgentName.RISK_GUARDIAN_AGENT,30000);expire(t);resume(t);
        assertThat(replies.status(user,t.view().requestId()).status()).isEqualTo(ReplyStatus.SUCCESS);
        assertThat(count(AgentCheckpoint.class)).isEqualTo(1);assertThat(row(t).completedSteps).isEqualTo(5);assertThat(row(t).revision).isEqualTo(5);
        assertThat(row(t).status).isEqualTo("COMMITTED");
        verify(memory,times(1)).act(any());verify(supervisor,times(1)).act(any());verify(knowledge,times(1)).act(any());
        verify(risk).act(argThat(c->c.memoryBrief().equals("考试背景摘要")&&c.retrievedKnowledge().get(0).content().equals("知识内容")));
        assertThat(checkpoints.latest().get(0).steps().get(3).attempts()).isEqualTo(2);
        assertThatThrownBy(()->checkpoints.failedAttempt(t,oldAttempt,new java.io.IOException(),true)).isInstanceOf(AgentCheckpointStore.LostLease.class);
    }
    @Test void transientFailureDiscardsPartialMutationAndRetriesOnlyFailedAgent(){
        AtomicInteger calls=new AtomicInteger();
        doAnswer(i->{AgentContext c=i.getArgument(0);
            if(calls.incrementAndGet()==1){c.setRiskLevel(RiskLevel.HIGH);c.setMemoryBrief("半成品错误值");throw new IllegalStateException(new java.io.IOException("offline"));}
            assertThat(c.memoryBrief()).isEqualTo("考试背景摘要");assertThat(c.riskLevel()).isEqualTo(RiskLevel.LOW);return assess(c);}).when(risk).act(any());
        var r=request();chat.streamChat(user,r).collectList().block(Duration.ofSeconds(10));
        assertThat(replies.status(user,r.requestId()).status()).isEqualTo(ReplyStatus.SUCCESS);
        verify(memory,times(1)).act(any());verify(knowledge,times(1)).act(any());verify(risk,times(2)).act(any());
        assertThat(checkpoints.latest().get(0).steps().get(3).attempts()).isEqualTo(2);
    }
    @Test void permanentErrorStopsWithoutLowRiskFallbackOrTools(){
        doThrow(new IllegalArgumentException("invalid result")).when(risk).act(any());
        long reports=count(PsychologicalReport.class);var r=request();chat.streamChat(user,r).collectList().block(Duration.ofSeconds(10));
        assertThat(replies.status(user,r.requestId()).status()).isEqualTo(ReplyStatus.FAILED);
        assertThat(count(PsychologicalReport.class)).isEqualTo(reports);verify(risk,times(1)).act(any());verify(ai,never()).streamAnswer(anyList(),anyInt());
        assertThat(checkpoints.latest().get(0).completedSteps()).isEqualTo(3);
        chat.resume(user,r.requestId(),null).collectList().block();verify(risk,times(1)).act(any());
    }
    @Test void timedOutStepIsBoundedAndNeverReplaysCompletedAgents(){
        org.springframework.test.util.ReflectionTestUtils.setField(runtime,"stepTimeoutMs",100L);
        try {
            doAnswer(i->{Thread.sleep(5000);return assess(i.getArgument(0));}).when(risk).act(any());
            var r=request();chat.streamChat(user,r).collectList().block(Duration.ofSeconds(10));
            assertThat(replies.status(user,r.requestId()).status()).isEqualTo(ReplyStatus.FAILED);
            verify(memory,times(1)).act(any());verify(knowledge,times(1)).act(any());verify(risk,times(3)).act(any());
            verify(ai,never()).streamAnswer(anyList(),anyInt());
            var progress=checkpoints.latest().get(0);
            assertThat(progress.completedSteps()).isEqualTo(3);
            assertThat(progress.errorType()).isEqualTo("TimeoutException");
            assertThat(progress.steps().get(3).attempts()).isEqualTo(3);
        } finally {org.springframework.test.util.ReflectionTestUtils.setField(runtime,"stepTimeoutMs",30000L);}
    }
    @Test void reclaimedLeaseRejectsLateSuccessfulOldResult(){
        var t=open();firstThree(t);var saved=checkpoints.load(t);var oldContext=context(t,saved);
        var old=checkpoints.begin(t,saved,AgentName.RISK_GUARDIAN_AGENT,30000);var decision=assess(oldContext);
        expire(t);resume(t);long revision=row(t).revision;
        assertThatThrownBy(()->checkpoints.complete(t,old,oldContext,decision)).isInstanceOf(AgentCheckpointStore.LostLease.class);
        assertThat(row(t).revision).isEqualTo(revision);
    }
    @Test void revokedKnowledgePackRestoresFlagsAndReexecutesKnowledge(){
        var t=open();firstThree(t);
        var saved=checkpoints.load(t);var c=context(t,saved);c.useKnowledgePack("pack","r1");
        var a=checkpoints.begin(t,saved,AgentName.RISK_GUARDIAN_AGENT,30000);assess(c);c.setRiskLevel(RiskLevel.MEDIUM);c.revokeKnowledgePack();
        checkpoints.complete(t,a,c,AgentDecision.continueWith(AgentAction.ASSESS_RISK,"revoked"));
        expire(t);resume(t);
        verify(knowledge,times(2)).act(any());verify(knowledge).act(argThat(x->x.forceNormalKnowledge()&&x.riskAssessed()&&x.knowledgePackId()==null));
        assertThat(row(t).completedSteps).isEqualTo(6);assertThat(replies.status(user,t.view().requestId()).status()).isEqualTo(ReplyStatus.SUCCESS);
    }
    @Test void automaticRecoveryPreparesOnceThenBrowserOnlyGenerates(){
        var t=open();firstThree(t);long tasksBefore=count(MemoryTask.class);long reportsBefore=count(PsychologicalReport.class);
        expire(t);recovery.recoverOnce();
        assertThat(replies.status(user,t.view().requestId()).prepared()).isTrue();
        assertThat(replies.status(user,t.view().requestId()).status()).isEqualTo(ReplyStatus.INTERRUPTED);
        verify(ai,never()).streamAnswer(anyList(),anyInt());
        resume(t);resume(t);recovery.recoverOnce();
        verify(counselor,times(1)).act(any());verify(ai,times(1)).streamAnswer(anyList(),anyInt());
        assertThat(count(MemoryTask.class)).isEqualTo(tasksBefore+2);assertThat(count(PsychologicalReport.class)).isEqualTo(reportsBefore+1);
    }
    @Test void concurrentResumeHasOnlyOneLeaseOwner() throws Exception {
        var t=open();firstThree(t);expire(t);var pool=Executors.newFixedThreadPool(6);
        try {var results=pool.invokeAll(java.util.stream.IntStream.range(0,6).mapToObj(i->(Callable<ReplyStore.Ticket>)()->replies.resume(user,t.view().requestId(),null)).toList());
            int owners=0;for(var f:results)if(f.get().owner()!=null)owners++;assertThat(owners).isEqualTo(1);
        }finally{pool.shutdownNow();}
    }
    @Test void attemptsAreNotResetByRestart(){
        var t=open();firstThree(t);
        for(int i=0;i<2;i++){var a=checkpoints.begin(t,checkpoints.load(t),AgentName.RISK_GUARDIAN_AGENT,30000);checkpoints.failedAttempt(t,a,new java.io.IOException(),true);}
        checkpoints.begin(t,checkpoints.load(t),AgentName.RISK_GUARDIAN_AGENT,30000);expire(t);
        assertThat(replies.status(user,t.view().requestId()).status()).isEqualTo(ReplyStatus.FAILED);
        assertThat(row(t).stepAttempts).isEqualTo(3);resume(t);verify(risk,never()).act(any());
    }
    @Test void stepLimitSurvivesResume(){
        var t=open();commit(t,memory);commit(t,supervisor);
        doReturn(AgentDecision.continueWith(AgentAction.RETRIEVE_KNOWLEDGE,"no progress")).when(knowledge).act(any());
        for(int i=0;i<5;i++)commit(t,knowledge);
        expire(t);resume(t);
        assertThat(row(t).completedSteps).isEqualTo(8);assertThat(replies.status(user,t.view().requestId()).status()).isEqualTo(ReplyStatus.FAILED);
        verify(knowledge,times(6)).act(any());verify(ai,never()).streamAnswer(anyList(),anyInt());
    }
    @Test void rolledBackFinalizationResumesReadySnapshotWithoutDuplicatingMessages(){
        var t=open();firstThree(t);commit(t,risk);commit(t,counselor);
        assertThatThrownBy(()->replies.prepared(t,List.of(AiMessage.user("test")),reply->{
            var m=new ChatMessage();m.setUser(t.user());m.setSession(t.session());m.setRole(MessageRole.USER);m.setContent("must rollback");em.persist(m);
            throw new IllegalStateException("transaction interrupted");})).isInstanceOf(IllegalStateException.class);
        assertThat(row(t).status).isEqualTo("READY");assertThat(messages.findBySession_PublicIdOrderByCreatedAtAsc(t.view().sessionId())).hasSize(1);
        expire(t);resume(t);resume(t);
        assertThat(messages.findBySession_PublicIdOrderByCreatedAtAsc(t.view().sessionId())).hasSize(2);
        verify(memory,times(1)).act(any());verify(counselor,times(1)).act(any());
    }
    @Test void deadlineAndVersionMismatchCannotResume(){
        var t=open();firstThree(t);tx.executeWithoutResult(s->em.find(AgentCheckpoint.class,t.view().requestId()).deadline=Instant.EPOCH);expire(t);
        assertThat(replies.status(user,t.view().requestId()).status()).isEqualTo(ReplyStatus.FAILED);
        var newer=open();tx.executeWithoutResult(s->em.find(AgentCheckpoint.class,newer.view().requestId()).schemaVersion=99);expire(newer);
        assertThat(replies.status(user,newer.view().requestId()).status()).isEqualTo(ReplyStatus.FAILED);
        verify(ai,never()).streamAnswer(anyList(),anyInt());
    }
    @Test void oneSnapshotPerRequestAndAdminProgressDoesNotExposePayload(){
        var a=open();var b=replies.open(user,new ChatRequest(a.view().sessionId(),"另一个问题",UUID.randomUUID().toString()));
        firstThree(a);assertThat(count(AgentCheckpoint.class)).isEqualTo(2);
        web.get().uri("/api/admin/agent-checkpoints").headers(h->h.setBasicAuth("student","student123")).exchange().expectStatus().isForbidden();
        web.get().uri("/api/admin/agent-checkpoints").headers(h->h.setBasicAuth("admin","admin123")).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$[0].snapshotJson").doesNotExist();
    }
}
