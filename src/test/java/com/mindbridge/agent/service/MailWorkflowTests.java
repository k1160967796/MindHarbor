package com.mindbridge.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindbridge.agent.config.*;
import com.mindbridge.agent.domain.*;
import com.mindbridge.agent.repository.*;
import com.mindbridge.agent.security.CurrentUser;
import com.mindbridge.agent.service.ai.AiClient;
import com.mindbridge.agent.service.mcp.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:mail-workflow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "mindbridge.knowledge.use-chroma=false","mindbridge.memory.use-chroma=false",
    "spring.ai.mcp.server.enabled=false","spring.ai.mcp.client.enabled=false",
    "mindbridge.tools.initial-delay-ms=3600000","mindbridge.mail.recipient-accounts=counselor-alerts@example.com=admin"})
class MailWorkflowTests {
    @Autowired MailWorkflowService workflow;
    @Autowired ToolOrchestrationService tools;
    @Autowired MailWebhookService webhooks;
    @Autowired ReportTaskRepository tasks;
    @Autowired AlertRecordRepository alerts;
    @Autowired PsychologicalReportRepository reports;
    @Autowired UserAccountRepository users;
    @Autowired MailDeliveryEventRepository events;
    @Autowired TransactionTemplate tx;
    @Autowired MailWorkflowProperties config;
    @Autowired MindBridgeProperties app;
    @Autowired ObjectMapper json;
    @Autowired WebTestClient client;
    @MockBean MailGateway gateway;
    @MockBean AiClient ai;
    @MockBean ExcelReportWriter excel;
    @MockBean AlertNotifier notifier;
    CurrentUser admin,student;
    @BeforeEach void setup() throws Exception {
        admin=new CurrentUser(users.findByUsername("admin").orElseThrow());
        student=new CurrentUser(users.findByUsername("student").orElseThrow());
        config.setMaxReminders(2);config.setReminderSeconds(600);
        config.setWebhookSecret("whsec_"+Base64.getEncoder().encodeToString("test-signing-key".getBytes()));
        MailGateway real=new MailGateway(config,app,notifier,json);
        doAnswer(i->{var t=i.getArgument(0,ReportTask.class);real.prepare(t,i.getArgument(1));t.provider="RESEND";return null;})
                .when(gateway).prepare(any(),any());
        when(gateway.safeReplay(any())).thenAnswer(i->{var t=i.getArgument(0,ReportTask.class);
            return "RESEND".equals(t.provider)&&t.firstSendAt!=null&&t.firstSendAt.plusSeconds(23*3600).isAfter(Instant.now());});
        when(gateway.send(any(),any(),any())).thenAnswer(i->new MailGateway.Receipt("provider-"+i.getArgument(0,ReportTask.class).id,"ACCEPTED"));
        when(gateway.lookup(anyString())).thenReturn("delivered");
    }
    private ReportTask create() {
        Long reportId=tx.execute(s->{PsychologicalReport p=new PsychologicalReport();p.setUser(users.findById(student.getId()).orElseThrow());
            p.setContent("mail workflow test");p.setIntent(IntentType.RISK);p.setRiskLevel(RiskLevel.HIGH);p.setEmotion(EmotionLabel.values()[0]);
            reports.save(p);tools.enqueue(p.getId());return p.getId();});
        return tasks.findByReportId(reportId).stream().filter(t->t.kind.equals("MAIL")).findFirst().orElseThrow();
    }
    private ReportTask reload(ReportTask t){return tasks.findById(t.id).orElseThrow();}
    private void due(Long alertId){tx.executeWithoutResult(s->alerts.lock(alertId).orElseThrow().nextReminderAt=Instant.now().minusSeconds(1));}
    private void retryDue(ReportTask t){tx.executeWithoutResult(s->tasks.lock(t.id).orElseThrow().nextAttempt=Instant.now().minusSeconds(1));}

    @Test void retryReusesFrozenPayloadAndKeyAndDoesNotClaimSentBeforeAcceptance() throws Exception {
        var task=create();List<String> payloads=new ArrayList<>(),keys=new ArrayList<>();
        doAnswer(i->{ReportTask t=i.getArgument(0);payloads.add(t.mailPayload);keys.add(t.idempotencyKey);
            if(keys.size()==1)throw new java.net.SocketTimeoutException("unknown outcome");return new MailGateway.Receipt("id-recovered","ACCEPTED");}).when(gateway).send(any(),any(),any());
        workflow.execute(task.id);assertThat(reload(task).status).isEqualTo("RETRYING");assertThat(reload(task).acceptedAt).isNull();
        retryDue(task);workflow.execute(task.id);
        assertThat(new HashSet<>(keys)).hasSize(1);assertThat(new HashSet<>(payloads)).hasSize(1);
        assertThat(reload(task).providerEmailId).isEqualTo("id-recovered");assertThat(reload(task).deliveryStatus).isEqualTo("ACCEPTED");
    }
    @Test void expiredIdempotencyWindowPreventsResend() throws Exception {
        var task=create();doThrow(new java.net.SocketTimeoutException()).when(gateway).send(any(),any(),any());workflow.execute(task.id);
        tx.executeWithoutResult(s->tasks.lock(task.id).orElseThrow().firstSendAt=Instant.now().minus(Duration.ofHours(25)));
        retryDue(task);workflow.execute(task.id);assertThat(reload(task).status).isEqualTo("UNKNOWN");verify(gateway,times(1)).send(any(),any(),any());
    }
    @Test void concurrentReminderScansCreateOneNewRound() throws Exception {
        var task=create();workflow.execute(task.id);due(task.alertId);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {var a=pool.submit(()->workflow.remind(task.alertId));var b=pool.submit(()->workflow.remind(task.alertId));a.get();b.get();}
        finally{pool.shutdownNow();}
        var rounds=tasks.findByAlertIdOrderByReminderRoundAsc(task.alertId);assertThat(rounds).hasSize(2);
        workflow.execute(rounds.get(1).id);assertThat(reload(rounds.get(1)).idempotencyKey).isNotEqualTo(reload(task).idempotencyKey);
    }
    @Test void acknowledgmentIsOwnedIdempotentAndCancelsQueuedReminder() throws Exception {
        var task=create();workflow.execute(task.id);due(task.alertId);workflow.remind(task.alertId);
        assertThatThrownBy(()->workflow.acknowledge(task.alertId,student)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        var first=workflow.acknowledge(task.alertId,admin);var second=workflow.acknowledge(task.alertId,admin);
        assertThat(second.acknowledgedAt()).isEqualTo(first.acknowledgedAt());
        var round=tasks.findByAlertIdOrderByReminderRoundAsc(task.alertId).get(1);workflow.execute(round.id);
        assertThat(reload(round).status).isEqualTo("CANCELLED");verify(gateway,times(1)).send(any(),any(),any());
    }
    @Test void confirmationRacingWithReminderScanLeavesNoSendableReminder() throws Exception {
        var task=create();workflow.execute(task.id);due(task.alertId);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {var a=pool.submit(()->workflow.remind(task.alertId));var b=pool.submit(()->workflow.acknowledge(task.alertId,admin));a.get();b.get();}
        finally{pool.shutdownNow();}
        assertThat(tasks.findByAlertIdOrderByReminderRoundAsc(task.alertId).stream().filter(t->t.reminderRound>0))
                .allMatch(t->"CANCELLED".equals(t.status));
    }
    @Test void reminderLimitEscalatesRatherThanSendingForever() {
        config.setMaxReminders(1);var task=create();workflow.execute(task.id);due(task.alertId);workflow.remind(task.alertId);
        workflow.execute(tasks.findByAlertIdOrderByReminderRoundAsc(task.alertId).get(1).id);due(task.alertId);workflow.remind(task.alertId);
        assertThat(tasks.findByAlertIdOrderByReminderRoundAsc(task.alertId)).hasSize(2);
        assertThat(alerts.findById(task.alertId).orElseThrow().escalatedAt).isNotNull();
    }
    @Test void unknownRoundCannotBeBypassedWithReminder() {
        var task=create();tx.executeWithoutResult(s->{tasks.lock(task.id).orElseThrow().status="UNKNOWN";alerts.lock(task.alertId).orElseThrow().nextReminderAt=Instant.now().minusSeconds(1);});
        workflow.remind(task.alertId);assertThat(tasks.findByAlertIdOrderByReminderRoundAsc(task.alertId)).hasSize(1);
    }
    @Test void reconciliationConfirmsDeliveryButNeverAcknowledgesTeacher() {
        var task=create();workflow.execute(task.id);tx.executeWithoutResult(s->tasks.lock(task.id).orElseThrow().nextReconcileAt=Instant.now().minusSeconds(1));
        workflow.reconcile(task.id);assertThat(reload(task).deliveryStatus).isEqualTo("DELIVERED");
        assertThat(alerts.findById(task.alertId).orElseThrow().acknowledgedAt).isNull();
    }
    private String callback(String email,String type,Instant time) throws Exception {
        return json.writeValueAsString(Map.of("type",type,"created_at",time.toString(),"data",Map.of("email_id",email)));
    }
    private String sign(String id,String stamp,String body) throws Exception {
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec("test-signing-key".getBytes(),"HmacSHA256"));
        return "v1,"+Base64.getEncoder().encodeToString(mac.doFinal((id+"."+stamp+"."+body).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private String event(String email,String type,Instant time) throws Exception {
        String id="event-"+UUID.randomUUID(),stamp=""+Instant.now().getEpochSecond(),body=callback(email,type,time);
        webhooks.receive(id,stamp,sign(id,stamp,body),body);return id;
    }
    @Test void earlyWebhookIsStoredUntilProviderIdBecomesKnownAndStaleSentDoesNotDowngrade() throws Exception {
        var task=create();String providerId="provider-"+task.id;
        String delivered=event(providerId,"email.delivered",Instant.now());workflow.applyEvent(delivered);
        assertThat(events.findById(delivered).orElseThrow().applied).isFalse();
        workflow.execute(task.id);workflow.applyEvent(delivered);workflow.applyEvent(delivered);
        workflow.applyEvent(event(providerId,"email.sent",Instant.now().minusSeconds(20)));
        assertThat(reload(task).deliveryStatus).isEqualTo("DELIVERED");
        assertThat(alerts.findById(task.alertId).orElseThrow().acknowledgedAt).isNull();
    }
    @Test void bounceCancelsQueuedReminderAndEscalates() throws Exception {
        var task=create();workflow.execute(task.id);due(task.alertId);workflow.remind(task.alertId);
        workflow.applyEvent(event("provider-"+task.id,"email.bounced",Instant.now()));
        assertThat(reload(task).deliveryStatus).isEqualTo("BOUNCED");
        assertThat(tasks.findByAlertIdOrderByReminderRoundAsc(task.alertId).get(1).status).isEqualTo("CANCELLED");
        assertThat(alerts.findById(task.alertId).orElseThrow().escalatedAt).isNotNull();
    }
    @Test void signedCorrelationTagRecoversIdAfterLostSendResponse() throws Exception {
        var task=create();doThrow(new java.net.SocketTimeoutException("response lost")).when(gateway).send(any(),any(),any());
        workflow.execute(task.id);String key=reload(task).idempotencyKey;
        String id="event-"+UUID.randomUUID(),stamp=""+Instant.now().getEpochSecond();
        String body=json.writeValueAsString(Map.of("type","email.delivered","created_at",Instant.now().toString(),
                "data",Map.of("email_id","recovered-"+task.id,"tags",Map.of("mail_key",key))));
        webhooks.receive(id,stamp,sign(id,stamp,body),body);workflow.applyEvent(id);workflow.execute(task.id);
        assertThat(reload(task).providerEmailId).isEqualTo("recovered-"+task.id);
        assertThat(reload(task).status).isEqualTo("SUCCESS");assertThat(reload(task).deliveryStatus).isEqualTo("DELIVERED");
        verify(gateway,times(1)).send(any(),any(),any());
    }
    @Test void openEventDoesNotCountAsTeacherConfirmation() throws Exception {
        var task=create();workflow.execute(task.id);workflow.applyEvent(event("provider-"+task.id,"email.opened",Instant.now()));
        assertThat(alerts.findById(task.alertId).orElseThrow().acknowledgedAt).isNull();
        assertThat(reload(task).deliveryStatus).isEqualTo("ACCEPTED");
    }
    @Test void permanentProviderRejectionStopsAutomaticRetries() throws Exception {
        var task=create();doThrow(new MailGateway.ProviderException(422)).when(gateway).send(any(),any(),any());
        workflow.execute(task.id);workflow.execute(task.id);
        assertThat(reload(task).status).isEqualTo("FAILED");assertThat(alerts.findById(task.alertId).orElseThrow().escalatedAt).isNotNull();
        verify(gateway,times(1)).send(any(),any(),any());
    }
    @Test void signedCallbacksDeduplicateAndInvalidOrOldSignaturesFail() throws Exception {
        String id="event-"+UUID.randomUUID(),stamp=""+Instant.now().getEpochSecond(),body=callback("unmatched","email.sent",Instant.now());
        String signature=sign(id,stamp,body);webhooks.receive(id,stamp,signature,body);webhooks.receive(id,stamp,signature,body);
        assertThat(events.findById(id)).isPresent();
        assertThatThrownBy(()->webhooks.receive(id,stamp,signature,body+" ")).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        String old=""+Instant.now().minusSeconds(600).getEpochSecond();
        assertThatThrownBy(()->webhooks.receive(id,old,sign(id,old,body),body)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void httpOwnershipAndExplicitActionHeaderAreRequired() {
        var task=create();
        client.post().uri("/api/alerts/"+task.alertId+"/acknowledge").headers(h->h.setBasicAuth("student","student123"))
                .header("X-MindBridge-Action","acknowledge").exchange().expectStatus().isForbidden();
        client.post().uri("/api/alerts/"+task.alertId+"/acknowledge").headers(h->h.setBasicAuth("admin","admin123"))
                .exchange().expectStatus().isBadRequest();
        client.get().uri("/api/alerts/"+task.alertId).headers(h->h.setBasicAuth("student","student123"))
                .exchange().expectStatus().isForbidden();
        client.post().uri("/api/alerts/"+task.alertId+"/acknowledge").headers(h->h.setBasicAuth("admin","admin123"))
                .header("X-MindBridge-Action","acknowledge").exchange().expectStatus().isOk().expectBody().jsonPath("$.acknowledgedBy").isEqualTo(admin.getId());
    }
}
