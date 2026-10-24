package com.mindharbor.agent.service;

import com.mindharbor.agent.domain.*;
import com.mindharbor.agent.dto.*;
import com.mindharbor.agent.repository.*;
import com.mindharbor.agent.service.agent.*;
import com.mindharbor.agent.service.ai.*;
import com.mindharbor.agent.service.memory.*;
import com.mindharbor.agent.service.mcp.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:h2:mem:reply-recovery;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "mindharbor.knowledge.use-chroma=false", "mindharbor.memory.use-chroma=false",
    "spring.ai.mcp.server.enabled=false", "spring.ai.mcp.client.enabled=false",
    "mindharbor.tools.initial-delay-ms=3600000"})
class ReplyRecoveryTests {
    @Autowired ChatService chat;
    @Autowired ReplyStore store;
    @Autowired ChatMessageRepository messages;
    @Autowired UserAccountRepository users;
    @Autowired ChatSessionRepository sessions;
    @Autowired ReportTaskRepository tasks;
    @Autowired PsychologicalReportRepository reports;
    @Autowired ToolOrchestrationService tools;
    @Autowired TransactionTemplate tx;
    @MockBean AiClient ai;
    @MockBean AgentRuntimeService runtime;
    @MockBean ShortTermMemoryService memory;
    @MockBean UserProfileMemoryService profile;
    @MockBean ExcelReportWriter excel;
    @MockBean AlertNotifier notifier;
    Long userId;

    @BeforeEach void setup() {
        userId=users.findByUsername("student").orElseThrow().getId();
        when(runtime.runRecoverable(any(),anyString())).thenReturn(run(false));
        when(ai.stream(anyList())).thenReturn(Flux.just("完整回答"));
        when(ai.streamAnswer(anyList(), anyInt())).thenCallRealMethod();
    }
    private AgentRunResult run(boolean risk) {
        return new AgentRunResult(risk?IntentType.RISK:IntentType.CHAT, risk?RiskLevel.HIGH:RiskLevel.LOW,
                risk?new PsychologyAssessment(EmotionLabel.values()[0], 1, RiskLevel.HIGH, .9, "test"):null,
                List.of(),List.of(),List.of(AiMessage.system("frozen prompt"),AiMessage.user("hello")),
                "brief","query","plan",AgentName.COUNSELOR_AGENT,List.of());
    }
    private ChatRequest request() { return new ChatRequest(null,"测试消息",UUID.randomUUID().toString()); }
    private List<ChatStreamEvent> send(ChatRequest r) {
        return chat.streamChat(userId,r).map(e->e.data()).collectList().block(Duration.ofSeconds(20));
    }
    private ReplyStore.Ticket ready() {
        var t=store.open(userId,request());
        return store.prepared(t,List.of(AiMessage.user("frozen")), m -> {});
    }
    @Test void partialFailureRetriesOnlyModelAndSavesOnlyCompleteAnswer() {
        AtomicInteger calls=new AtomicInteger();
        when(ai.stream(anyList())).thenAnswer(i -> calls.incrementAndGet()==1
            ? Flux.concat(Flux.just("不完整"),Flux.error(new java.io.IOException("connection reset"))) : Flux.just("完整回答"));
        var r=request(); var events=send(r); var v=store.status(userId,r.requestId());
        assertThat(v.status()).isEqualTo(ReplyStatus.SUCCESS); assertThat(v.attempts()).isEqualTo(2);
        assertThat(v.content()).isEqualTo("完整回答");
        assertThat(events).extracting(ChatStreamEvent::type).contains("retry","done");
        verify(runtime,times(1)).runRecoverable(any(),anyString());
        verify(memory,never()).append(anyString(),eq(MessageRole.ASSISTANT),eq("不完整"));
        verify(memory,times(1)).append(anyString(),eq(MessageRole.ASSISTANT),eq("完整回答"));
        assertThat(messages.findBySession_PublicIdOrderByCreatedAtAsc(v.sessionId())).hasSize(2);
    }
    @Test void threeTotalAttemptsFailWithEmptyContentAndManualGenerationDoesNotRerunAgents() {
        when(ai.stream(anyList())).thenReturn(Flux.concat(Flux.just("partial"),Flux.error(new java.io.IOException("offline"))));
        var r=request(); send(r); var failed=store.status(userId,r.requestId());
        assertThat(failed.status()).isEqualTo(ReplyStatus.FAILED); assertThat(failed.attempts()).isEqualTo(3);
        assertThat(failed.content()).isEmpty();
        when(ai.stream(anyList())).thenReturn(Flux.just("new full"));
        chat.resume(userId,r.requestId(),1).collectList().block(Duration.ofSeconds(5));
        assertThat(store.status(userId,r.requestId()).generation()).isEqualTo(2);
        assertThat(store.status(userId,r.requestId()).content()).isEqualTo("new full");
        verify(runtime,times(1)).runRecoverable(any(),anyString());
    }
    @Test void truncatedAnswerContinuesOnceWithoutRepeatingAgentsOrTools() {
        doReturn(Flux.concat(Flux.just("先做"), Flux.error(new OutputTruncatedException()))).when(ai).streamAnswer(anyList(), eq(1024));
        doReturn(Flux.just("两次深呼吸。")).when(ai).streamAnswer(anyList(), eq(512));
        var r=request();
        var events=chat.streamChat(userId,r).collectList().block(Duration.ofSeconds(5));
        var status=store.status(userId,r.requestId());
        assertThat(status.status()).isEqualTo(ReplyStatus.SUCCESS);
        assertThat(status.content()).isEqualTo("先做两次深呼吸。");
        assertThat(status.continuationCount()).isEqualTo(1);
        assertThat(status.attempts()).isEqualTo(1);
        assertThat(events.stream().filter(e -> "continuation".equals(e.event())).count()).isEqualTo(1);
        verify(runtime,times(1)).runRecoverable(any(),anyString());
        verify(ai).streamAnswer(argThat(p -> p.stream().anyMatch(m -> m.role().equals("assistant") && m.content().equals("先做"))),eq(512));
        verify(memory).append(anyString(),eq(MessageRole.ASSISTANT),eq("先做两次深呼吸。"));
    }

    @Test void secondTruncationFailsWithoutSavingPartialHistoryOrRetrying() {
        doReturn(Flux.concat(Flux.just("半段"), Flux.error(new OutputTruncatedException()))).when(ai).streamAnswer(anyList(), anyInt());
        var r=request(); send(r);
        var status=store.status(userId,r.requestId());
        assertThat(status.status()).isEqualTo(ReplyStatus.FAILED);
        assertThat(status.content()).isEmpty();
        assertThat(status.error()).contains("完整收尾");
        assertThat(status.continuationCount()).isEqualTo(1);
        assertThat(status.attempts()).isEqualTo(1);
        verify(ai,times(2)).streamAnswer(anyList(),anyInt());
        verify(memory,never()).append(anyString(),eq(MessageRole.ASSISTANT),anyString());
    }

    @Test void continuationBudgetSurvivesDisconnectAndResetsOnlyOnManualRegeneration() {
        var initial=store.attempt(ready());
        assertThat(store.reserveContinuation(initial)).isTrue();
        assertThat(store.reserveContinuation(initial)).isFalse();
        store.interrupt(initial);
        var resumed=store.attempt(store.resume(userId,initial.view().requestId(),null));
        assertThat(store.reserveContinuation(resumed)).isFalse();
        store.failure(resumed,"未完成");
        var fresh=store.attempt(store.resume(userId,initial.view().requestId(),initial.view().generation()));
        assertThat(store.reserveContinuation(fresh)).isTrue();
    }

    @Test void continuationNetworkFailureIsNotRetriedAsAFullAnswer() {
        doReturn(Flux.concat(Flux.just("半段"), Flux.error(new OutputTruncatedException()))).when(ai).streamAnswer(anyList(),eq(1024));
        doReturn(Flux.error(new java.io.IOException("offline"))).when(ai).streamAnswer(anyList(),eq(512));
        var r=request(); send(r);
        assertThat(store.status(userId,r.requestId()).status()).isEqualTo(ReplyStatus.FAILED);
        assertThat(store.status(userId,r.requestId()).attempts()).isEqualTo(1);
        verify(ai,times(2)).streamAnswer(anyList(),anyInt());
    }

    @Test void configurationFailureDoesNotRetry() {
        when(ai.stream(anyList())).thenReturn(Flux.error(new IllegalArgumentException("404 model not found")));
        var r=request(); send(r);
        assertThat(store.status(userId,r.requestId()).attempts()).isEqualTo(1);
        assertThat(store.status(userId,r.requestId()).status()).isEqualTo(ReplyStatus.FAILED);
    }
    @Test void cancelledStreamResumesFrozenPromptWithoutPartialMemory() throws Exception {
        when(ai.stream(anyList())).thenReturn(Flux.concat(Flux.just("half"),Flux.never()));
        var r=request();
        StepVerifier.create(chat.streamChat(userId,r)).thenConsumeWhile(e -> !"token".equals(e.event()))
                .expectNextMatches(e -> "token".equals(e.event())).thenCancel().verify(Duration.ofSeconds(5));
        for(int i=0;i<100 && store.status(userId,r.requestId()).status()!=ReplyStatus.INTERRUPTED;i++) Thread.sleep(20);
        assertThat(store.status(userId,r.requestId()).status()).isEqualTo(ReplyStatus.INTERRUPTED);
        assertThat(store.status(userId,r.requestId()).content()).isEmpty();
        when(ai.stream(anyList())).thenReturn(Flux.just("resumed"));
        chat.resume(userId,r.requestId(),null).collectList().block(Duration.ofSeconds(5));
        assertThat(store.status(userId,r.requestId()).attempts()).isEqualTo(2);
        verify(runtime,times(1)).runRecoverable(any(),anyString());
        verify(memory,never()).append(anyString(),eq(MessageRole.ASSISTANT),eq("half"));
        verify(ai,times(2)).stream(eq(run(false).responseMessages()));
    }
    @Test void completedReconnectAndDuplicateSubmissionDoNotGenerateAgain() {
        var r=request(); send(r); send(r);
        chat.resume(userId,r.requestId(),null).collectList().block(Duration.ofSeconds(5));
        verify(ai,times(1)).stream(anyList()); verify(runtime,times(1)).runRecoverable(any(),anyString());
    }
    @Test void requestOwnershipIsChecked() {
        var t=ready(); Long admin=users.findByUsername("admin").orElseThrow().getId();
        assertThatThrownBy(() -> store.status(admin,t.view().requestId()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(() -> store.resume(admin,t.view().requestId(),null))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void concurrentResumeHasOneOwnerAndOldProducerCannotCommit() throws Exception {
        var old=store.attempt(ready()); store.interrupt(old);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var futures=pool.invokeAll(List.of(
                () -> store.resume(userId,old.view().requestId(),null),
                () -> store.resume(userId,old.view().requestId(),null)));
            long owners=0; for(var f:futures) if(((ReplyStore.Ticket)f.get()).owner()!=null) owners++;
            assertThat(owners).isEqualTo(1);
            assertThat(store.success(old,"stale answer")).isFalse();
        } finally { pool.shutdownNow(); }
    }
    @Test void memoryFiltersFailedRowsBeforeLimitAndRetainsLegacyMessages() {
        var t=ready(); store.failure(t,"test");
        assertThat(messages.findMemoryHistory(t.session().getId(),org.springframework.data.domain.PageRequest.of(0,20))).isEmpty();
        ChatMessage legacy=new ChatMessage(); legacy.setUser(t.user()); legacy.setSession(t.session());
        legacy.setRole(MessageRole.ASSISTANT); legacy.setContent("legacy"); messages.save(legacy);
        assertThat(messages.findMemoryHistory(t.session().getId(),org.springframework.data.domain.PageRequest.of(0,20)))
            .extracting(ChatMessage::getContent).containsExactly("legacy");
    }
    @Test void staleExecutionRecoversAndExhaustedAttemptsDoNotResetOnReconnect() {
        var t=store.attempt(ready());
        tx.executeWithoutResult(s -> { var m=messages.lockRequest(t.view().requestId()).orElseThrow(); m.setLeaseUntil(Instant.now().minusSeconds(1)); m.setAttempts(3); });
        store.recoverExpired();
        assertThat(store.status(userId,t.view().requestId()).status()).isEqualTo(ReplyStatus.INTERRUPTED);
        assertThat(store.resume(userId,t.view().requestId(),null).view().status()).isEqualTo(ReplyStatus.FAILED);
    }
    @Test void preparationDisconnectStillRegistersReportAndIndependentJobs() throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(runtime.runRecoverable(any(),anyString())).thenAnswer(i->{ entered.countDown(); release.await(5,TimeUnit.SECONDS); return run(true); });
        var r=request(); var subscription=chat.streamChat(userId,r).subscribe();
        assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue(); subscription.dispose();
        for(int i=0;i<100 && store.status(userId,r.requestId()).status()!=ReplyStatus.INTERRUPTED;i++) Thread.sleep(20);
        release.countDown();
        for(int i=0;i<100 && !store.status(userId,r.requestId()).prepared();i++) Thread.sleep(20);
        assertThat(store.status(userId,r.requestId()).prepared()).isTrue();
        assertThat(store.status(userId,r.requestId()).status()).isEqualTo(ReplyStatus.INTERRUPTED);
        assertThat(tasks.findAll()).isNotEmpty(); verify(ai,never()).stream(anyList());
    }
    private Long createReportTasks() {
        var t=ready();
        return tx.execute(s -> {
            PsychologicalReport p=new PsychologicalReport(); p.setUser(t.user()); p.setSession(t.session());
            p.setContent("test"); p.setIntent(IntentType.RISK); p.setRiskLevel(RiskLevel.HIGH);
            p.setEmotion(EmotionLabel.values()[0]); reports.save(p); tools.enqueue(p.getId()); return p.getId();
        });
    }
    @Test void excelFailureDoesNotBlockMailAndEnqueueIsIdempotent() {
        Long id=createReportTasks(); tools.enqueue(id);
        var jobs=tasks.findByReportId(id); assertThat(jobs).hasSize(2);
        doThrow(new IllegalStateException("file busy")).when(excel).write(any());
        jobs.forEach(j -> tools.execute(j.id));
        assertThat(reports.findById(id).orElseThrow().getEmailStatus()).isEqualTo(ToolStatus.SUCCESS);
        assertThat(tasks.findByReportId(id).stream().filter(j -> j.kind.equals("EXCEL")).findFirst().orElseThrow().status).isEqualTo("RETRYING");
        jobs.forEach(j -> tools.execute(j.id)); verify(notifier,times(1)).notifyFrozen(any(),any(),any());
    }
    @Test void ambiguousMailResultIsNotBlindlyRetried() {
        Long id=createReportTasks();
        doThrow(new IllegalStateException(new java.net.SocketTimeoutException("read timed out"))).when(notifier).notifyFrozen(any(),any(),any());
        var mail=tasks.findByReportId(id).stream().filter(j -> j.kind.equals("MAIL")).findFirst().orElseThrow();
        tools.execute(mail.id); tools.execute(mail.id);
        assertThat(tasks.findById(mail.id).orElseThrow().status).isEqualTo("UNKNOWN");
        verify(notifier,times(1)).notifyFrozen(any(),any(),any());
    }

    @Test void reportAndTaskRegistrationRollbackTogether() {
        var t=ready(); long before=tasks.count(); long reportsBefore=reports.count();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            PsychologicalReport p=new PsychologicalReport(); p.setUser(t.user()); p.setSession(t.session());
            p.setContent("rollback"); p.setIntent(IntentType.RISK); p.setRiskLevel(RiskLevel.HIGH);
            p.setEmotion(EmotionLabel.values()[0]); reports.save(p); tools.enqueue(p.getId());
            throw new IllegalStateException("simulated crash before commit");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(tasks.count()).isEqualTo(before); assertThat(reports.count()).isEqualTo(reportsBefore);
    }
    @Test void disconnectedTimeDoesNotConsumeRemainingBudget() {
        var t=store.attempt(ready()); store.interrupt(t);
        tx.executeWithoutResult(s -> {
            var m=messages.lockRequest(t.view().requestId()).orElseThrow();
            assertThat(m.getGenerationDeadline()).isNull(); assertThat(m.getRemainingMillis()).isPositive();
        });
        var resumed=store.attempt(store.resume(userId,t.view().requestId(),null));
        assertThat(resumed.deadline()).isAfter(Instant.now()); assertThat(resumed.view().attempts()).isEqualTo(2);
    }
    @Test void expiredGenerationBudgetStopsWithoutExtraModelCalls() {
        var t=ready();
        tx.executeWithoutResult(s -> messages.lockRequest(t.view().requestId()).orElseThrow().setRemainingMillis(30));
        store.interrupt(t);
        when(ai.stream(anyList())).thenReturn(Flux.never());
        chat.resume(userId,t.view().requestId(),null).collectList().block(Duration.ofSeconds(5));
        assertThat(store.status(userId,t.view().requestId()).status()).isEqualTo(ReplyStatus.FAILED);
        assertThat(store.status(userId,t.view().requestId()).attempts()).isEqualTo(1);
    }
    @Test void localExcelRetryDoesNotAppendDuplicateReport() throws Exception {
        var config=new com.mindharbor.agent.config.MindHarborProperties();
        var folder=java.nio.file.Files.createTempDirectory("mindharbor-excel-test");
        var file=folder.resolve("reports.xlsx"); config.getMcp().getExcel().setLocalPath(file.toString());
        var writer=new LocalExcelReportWriter(config); Long id=createReportTasks();
        tx.executeWithoutResult(s -> { var p=reports.findById(id).orElseThrow(); writer.write(p); writer.write(p); });
        try(var input=java.nio.file.Files.newInputStream(file); var book=org.apache.poi.ss.usermodel.WorkbookFactory.create(input)) {
            assertThat(book.getSheetAt(0).getPhysicalNumberOfRows()).isEqualTo(2);
        }
        java.nio.file.Files.delete(file); java.nio.file.Files.delete(folder);
    }
}
