package com.mindharbor.agent.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindharbor.agent.domain.*;
import com.mindharbor.agent.dto.ChatRequest;
import com.mindharbor.agent.repository.*;
import com.mindharbor.agent.service.*;
import com.mindharbor.agent.service.agent.*;
import com.mindharbor.agent.service.ai.*;
import com.mindharbor.agent.service.knowledge.*;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:memory-maintenance;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "mindharbor.memory.background-enabled=false","mindharbor.memory.background-idle-ms=0",
    "mindharbor.knowledge.use-chroma=false","mindharbor.memory.use-chroma=false",
    "mindharbor.tools.initial-delay-ms=3600000","spring.ai.mcp.server.enabled=false","spring.ai.mcp.client.enabled=false"})
@AutoConfigureWebTestClient
class MemoryMaintenanceAcceptanceTests {
    @Autowired MemoryMaintenanceService jobs;
    @Autowired MemoryFactStore facts;
    @Autowired ChatService chat;
    @Autowired ReplyStore replies;
    @Autowired ConversationSummaryService summaries;
    @Autowired MemoryAgent memoryAgent;
    @Autowired UserAccountRepository users;
    @Autowired ChatSessionRepository sessions;
    @Autowired ChatMessageRepository messages;
    @Autowired UserMemoryItemRepository memories;
    @Autowired AgentRunTraceRepository traces;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;
    @Autowired ObjectMapper json;
    @Autowired PrivacySanitizer sanitizer;
    @Autowired MeterRegistry meters;
    @Autowired WebTestClient web;
    @MockBean AiClient ai;
    @MockBean UserMemoryChromaGateway chroma;
    @MockBean ShortTermMemoryService shortMemory;
    @MockBean KnowledgeService knowledge;
    Long userId;
    @BeforeEach void setup() {
        tx.executeWithoutResult(s -> {
            em.createQuery("delete from MemoryTask").executeUpdate();em.createQuery("delete from MemoryUserState").executeUpdate();
            em.createQuery("delete from ConversationMemorySummary").executeUpdate();em.createQuery("delete from UserMemoryItem").executeUpdate();
            var g=em.find(MemoryWorkerGate.class,1L);g.owner=null;g.leaseUntil=null;g.lastForegroundAt=Instant.EPOCH;
            em.createQuery("update ChatMessage m set m.executionOwner=null,m.leaseUntil=null").executeUpdate();
        });
        userId=users.findByUsername("student").orElseThrow().getId();
        when(shortMemory.recent(anyString())).thenReturn(List.of());
        when(chroma.query(anyLong(),anyString(),anyInt())).thenReturn(List.of());
        when(ai.complete(anyList())).thenAnswer(c -> {
            var p=(List<AiMessage>)c.getArgument(0);String system=p.get(0).content();
            if(system.contains("意图"))return "CONSULT";
            return "{\"emotion\":\"ANXIETY\",\"emotionScore\":2,\"risk\":\"LOW\",\"confidence\":0.9,\"summary\":\"考试压力\"}";
        });
        when(ai.completeWithin(anyList(),any(),anyMap(),anyInt())).thenAnswer(c -> {
            var p=(List<AiMessage>)c.getArgument(0);
            if(p.get(0).content().contains("后台长期画像")) {
                String latest=p.get(1).content().split("本轮用户输入：\\n",2)[1];
                String preference=latest.contains("详细")?"希望回答详细":"希望回答简短";
                return json.writeValueAsString(List.of(Map.of("type","PREFERENCE","key","response_length","summary",preference,"evidence",latest,"confidence",0.9,
                        "value",latest.contains("详细")?"detailed":"concise","scope","GLOBAL","subject","SELF","temporal","CURRENT","effectiveDate","")));
            }
            if(p.get(0).content().contains("后台会话摘要")) return "历史中用户提到考试压力和睡眠问题，助手提供了支持建议；当前风险仍需根据近期原文判断。";
            return "{\"focus\":\"回应考试压力\",\"action\":\"提供两个小步骤\"}";
        });
        when(ai.streamAnswer(anyList(),anyInt())).thenReturn(Flux.just("先安排可完成的小任务，再规律休息。"));
        var found=List.of(new SearchResult(1L,"fixture","考试压力可采用合理作息与拆分任务。",.9));
        when(knowledge.recall(anyString(),anyInt(),any())).thenReturn(new KnowledgeCandidates(found,List.of()));
        when(knowledge.review(anyString(),anyString(),anyString(),any(),anyInt(),any())).thenReturn(new KnowledgeReview(found,KnowledgeReview.Coverage.SUFFICIENT,null,"REVIEWED"));
        when(knowledge.materialize(any(),anyInt())).thenReturn(found);
    }
    ChatRequest request(String session,String input) {return new ChatRequest(session,input,UUID.randomUUID().toString());}
    ReplyStore.View send(ChatRequest r) {chat.streamChat(userId,r).collectList().block(Duration.ofSeconds(10));return replies.status(userId,r.requestId());}
    void drain() {
        // Timestamp precision and asynchronous stream cleanup can defer one poll;
        // production polls again rather than treating one empty claim as completion.
        for(int i=0;i<30;i++) {
            jobs.runNext();
            if(jobs.recentTasks().stream().noneMatch(t -> t.status.equals("PENDING") || t.status.equals("RUNNING"))) return;
            try {Thread.sleep(5);} catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException(e);}
        }
    }
    long count(String kind,String status) {return jobs.recentTasks().stream().filter(t -> t.kind.equals(kind)&&t.status.equals(status)).count();}

    @Test void twoRoundsPreserveRawContextWithoutSynchronousSummaryOrProfileModel() {
        var a=send(request(null,"最近考试压力很大，晚上睡不着，我想咨询缓解方法"));
        var b=send(request(a.sessionId(),"那我该怎么办？"));
        assertThat(a.status()).isEqualTo(ReplyStatus.SUCCESS);assertThat(b.status()).isEqualTo(ReplyStatus.SUCCESS);
        verify(ai,never()).completeWithin(argThat(p -> p.get(0).content().contains("后台")),any(),anyMap(),anyInt());
        verify(ai).streamAnswer(argThat(p -> p.stream().anyMatch(m -> m.content().contains("考试压力很大"))
                && p.get(p.size()-1).content().contains("那我该怎么办")),eq(1024));
        assertThat(count("PROFILE","PENDING")).isEqualTo(2);
        assertThat(count("SUMMARY","PENDING")).isEqualTo(2);
    }
    @Test void backgroundFailureRetriesWithoutFailingOrBlockingChat() {
        var a=send(request(null,"我希望以后用温和语气回应，请记住这个偏好"));
        doThrow(new IllegalStateException("fixture model down")).when(ai).completeWithin(
                argThat(p -> p.get(0).content().contains("后台长期画像")),any(),anyMap(),anyInt());
        assertThat(jobs.runNext()).isTrue();assertThat(count("PROFILE","RETRYING")).isEqualTo(1);
        var b=send(request(a.sessionId(),"那我该怎么办？"));assertThat(b.status()).isEqualTo(ReplyStatus.SUCCESS);
        for(int i=0;i<5;i++) {tx.executeWithoutResult(s -> em.createQuery("update MemoryTask t set t.nextAttempt=:n where t.status='RETRYING'").setParameter("n",Instant.EPOCH).executeUpdate());jobs.runNext();}
        assertThat(count("PROFILE","FAILED")).isGreaterThanOrEqualTo(1);
        assertThat(replies.status(userId,a.requestId()).status()).isEqualTo(ReplyStatus.SUCCESS);
        web.get().uri("/api/admin/memory-tasks").headers(h->h.setBasicAuth("student","student123")).exchange().expectStatus().isForbidden();
        web.get().uri("/api/admin/memory-tasks").headers(h->h.setBasicAuth("admin","admin123")).exchange().expectStatus().isOk();
    }
    @Test void newerPreferenceWinsEvenIfAnOldFailedTaskIsManuallyRetried() {
        send(request(null,"以后请回答详细一些，我希望多一些解释"));drain();
        send(request(null,"现在请改成回答简短一些，不要展开太多"));drain();
        assertThat(memories.findByUser_IdOrderByUpdatedAtDesc(userId)).singleElement().satisfies(m -> assertThat(m.getSummary()).isEqualTo("希望回答简短"));
        Long old=jobs.recentTasks().stream().filter(t -> t.kind.equals("PROFILE")).map(t->t.id).min(Long::compare).orElseThrow();
        tx.executeWithoutResult(s -> em.find(MemoryTask.class,old).status="FAILED");jobs.retry(old);drain();
        assertThat(memories.findByUser_IdOrderByUpdatedAtDesc(userId)).singleElement().satisfies(m -> assertThat(m.getSummary()).isEqualTo("希望回答简短"));
        assertThat(jobs.recentTasks().stream().filter(t->t.id.equals(old)).findFirst().orElseThrow().status).isEqualTo("SKIPPED");
    }
    @Test void crashedWorkerLeaseCanBeRecoveredByNewWorkerButOldOwnerCannotCommit() {
        send(request(null,"以后请回答详细一些，我希望多一些解释"));
        Object target=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(jobs);
        MemoryTask old=ReflectionTestUtils.invokeMethod(target,"claim");assertThat(old).isNotNull();
        tx.executeWithoutResult(s -> {em.find(MemoryTask.class,old.id).leaseUntil=Instant.EPOCH;em.find(MemoryWorkerGate.class,1L).leaseUntil=Instant.EPOCH;});
        var replacement=new MemoryMaintenanceService(em,tx,ai,json,sanitizer,chroma,meters,facts);
        ReflectionTestUtils.setField(replacement,"idleMs",0L);ReflectionTestUtils.setField(replacement,"timeoutSeconds",15L);
        try {assertThat(replacement.runNext()).isTrue();} finally {replacement.close();}
        assertThat(memories.findByUser_IdOrderByUpdatedAtDesc(userId)).hasSize(1);
        assertThatThrownBy(() -> tx.execute(s -> ReflectionTestUtils.invokeMethod(target,"owned",old))).isInstanceOf(IllegalStateException.class);
        assertThat(jobs.recentTasks().stream().filter(t -> t.id.equals(old.id)).findFirst().orElseThrow().attempts).isEqualTo(2);
    }
    @Test void disconnectAndResumeDoNotDuplicateMemoryTasks() throws Exception {
        doReturn(Flux.concat(Flux.just("半段"),Flux.never())).when(ai).streamAnswer(anyList(),anyInt());
        var r=request(null,"最近考试压力很大，我想咨询怎么缓解");
        StepVerifier.create(chat.streamChat(userId,r)).thenConsumeWhile(e -> !"token".equals(e.event())).expectNextCount(1).thenCancel().verify(Duration.ofSeconds(10));
        for(int i=0;i<100 && replies.status(userId,r.requestId()).status()!=ReplyStatus.INTERRUPTED;i++)Thread.sleep(20);
        assertThat(replies.status(userId,r.requestId()).status()).isEqualTo(ReplyStatus.INTERRUPTED);
        doReturn(Flux.just("恢复后的完整回复。")).when(ai).streamAnswer(anyList(),anyInt());
        chat.resume(userId,r.requestId(),null).collectList().block(Duration.ofSeconds(10));
        assertThat(jobs.recentTasks()).hasSize(2);
        assertThat(replies.status(userId,r.requestId()).status()).isEqualTo(ReplyStatus.SUCCESS);
    }
    @Test void activeForegroundRequestPreventsBackgroundClaim() {
        send(request(null,"以后请回答简短一些，请记住这个偏好"));
        var foreground=replies.open(userId,request(null,"正在处理的另一个请求"));
        assertThat(jobs.runNext()).isFalse();assertThat(count("PROFILE","PENDING")).isEqualTo(1);
        replies.failure(foreground,"fixture complete");assertThat(jobs.runNext()).isTrue();
    }
    @Test void indexFailureKeepsCommittedProfileAndRetriesIndependently() {
        send(request(null,"以后请回答简短一些，请记住这个偏好"));
        doThrow(new IllegalStateException("index offline")).when(chroma).mirror(any());drain();
        assertThat(count("PROFILE","SUCCESS")).isEqualTo(1);assertThat(count("INDEX","RETRYING")).isEqualTo(1);
        assertThat(memories.findByUser_IdOrderByUpdatedAtDesc(userId)).hasSize(1);
    }
    @Test void fabricatedProfileEvidenceCannotBePersisted() {
        send(request(null,"以后请回答简短一些，请记住这个偏好"));
        doReturn("[{\"type\":\"PERSONAL_CONTEXT\",\"key\":\"background\",\"summary\":\"用户是计算机专业学生\",\"evidence\":\"我是计算机专业学生\",\"confidence\":0.99}]")
                .when(ai).completeWithin(argThat(p -> p.get(0).content().contains("后台长期画像")),any(),anyMap(),anyInt());
        drain();
        assertThat(memories.findByUser_IdOrderByUpdatedAtDesc(userId)).singleElement().satisfies(m->{
            assertThat(m.getFactKey()).isEqualTo("response_length");assertThat(m.getSummary()).doesNotContain("计算机");
        });
        assertThat(count("PROFILE","SUCCESS")).isEqualTo(1);
    }
    @Test void summariesAdvanceOnlyBeyondThresholdAndKeepRecentRawMessages() {
        var v=send(request(null,"最近考试压力很大，我想咨询怎么缓解"));
        Long sid=sessions.findByPublicIdAndUser_Id(v.sessionId(),userId).orElseThrow().getId();
        tx.executeWithoutResult(s -> {
            for(int i=0;i<12;i++) {var m=new ChatMessage();m.setSession(em.getReference(ChatSession.class,sid));m.setUser(em.getReference(UserAccount.class,userId));
                m.setRole(i%2==0?MessageRole.USER:MessageRole.ASSISTANT);m.setContent("考试历史消息"+i);m.setCreatedAt(Instant.now().plusMillis(i));
                if(i%2==1)m.setReplyStatus(ReplyStatus.SUCCESS);em.persist(m);}
            var failed=new ChatMessage();failed.setSession(em.getReference(ChatSession.class,sid));failed.setUser(em.getReference(UserAccount.class,userId));
            failed.setRole(MessageRole.ASSISTANT);failed.setReplyStatus(ReplyStatus.FAILED);failed.setContent("不应进入摘要的半段回答");em.persist(failed);
        });
        drain();
        var summary=tx.execute(s -> em.find(ConversationMemorySummary.class,sid));assertThat(summary).isNotNull();assertThat(summary.revision).isEqualTo(1);
        var recall=summaries.read(sid,"考试怎么办",List.of());assertThat(recall.source()).isEqualTo("SUMMARY_REUSED");assertThat(recall.history()).hasSize(6);
        verify(ai).completeWithin(argThat(p -> p.get(0).content().contains("后台会话摘要") && !p.get(1).content().contains("不应进入摘要")),any(),anyMap(),anyInt());
        send(request(v.sessionId(),"考试这件事仍让我焦虑"));drain();
        Long revision=tx.execute(s -> em.find(ConversationMemorySummary.class,sid).revision);
        assertThat(revision).isEqualTo(1L);
    }
}
