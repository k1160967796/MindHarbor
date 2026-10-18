package com.mindharbor.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindharbor.agent.config.*;
import com.mindharbor.agent.domain.*;
import com.mindharbor.agent.service.mcp.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MailGatewayTests {
    @Test void providerAdapterSendsStableKeyAndBodyAndRetrievesDelivery() throws Exception {
        List<String> keys=new ArrayList<>(),bodies=new ArrayList<>(),auth=new ArrayList<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/emails",exchange->{
            String response;
            if("POST".equals(exchange.getRequestMethod())) {
                keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
                bodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));response="{\"id\":\"email-test\"}";
            } else response="{\"id\":\"email-test\",\"last_event\":\"delivered\"}";
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var cfg=new MailWorkflowProperties();cfg.setProvider("resend");cfg.setApiKey("test-key");cfg.setApiBase("http://127.0.0.1:"+server.getAddress().getPort());
            var gateway=new MailGateway(cfg,new MindHarborProperties(),mock(AlertNotifier.class),new ObjectMapper());
            var task=new ReportTask();task.id=5L;task.reportId=9L;task.recipient="teacher@example.com";
            var alert=new AlertRecord();gateway.prepare(task,alert);task.firstSendAt=Instant.now();
            var first=gateway.send(task,alert,null);var second=gateway.send(task,alert,null);
            assertThat(first.id()).isEqualTo(second.id());assertThat(first.delivery()).isEqualTo("ACCEPTED");
            assertThat(keys).hasSize(2).containsOnly(task.idempotencyKey);assertThat(new HashSet<>(bodies)).hasSize(1);
            assertThat(auth).containsOnly("Bearer test-key");assertThat(gateway.lookup(first.id())).isEqualTo("delivered");
            assertThat(bodies.get(0)).contains("mail_key").doesNotContain("password");
        }finally{server.stop(0);}
    }
    @Test void smtpUsesFrozenPayloadAndOnlyClaimsAcceptance() throws Exception {
        var cfg=new MailWorkflowProperties();var app=new MindHarborProperties();app.getMcp().getEmail().setMode("smtp");
        var sender=mock(org.springframework.mail.javamail.JavaMailSender.class);
        var mime=new jakarta.mail.internet.MimeMessage((jakarta.mail.Session)null);when(sender.createMimeMessage()).thenReturn(mime);
        var gateway=new MailGateway(cfg,app,new SmtpAlertNotifier(sender,app),new ObjectMapper());
        var task=new ReportTask();task.id=3L;task.reportId=10L;task.recipient="teacher@example.com";
        var alert=new AlertRecord();gateway.prepare(task,alert);String frozen=task.mailPayload;
        app.getMcp().getEmail().setFrom("changed@example.com");gateway.prepare(task,alert);
        var receipt=gateway.send(task,alert,null);
        assertThat(task.mailPayload).isEqualTo(frozen);assertThat(receipt.delivery()).isEqualTo("ACCEPTED");assertThat(receipt.id()).isNull();
        verify(sender).send(mime);assertThat(mime.getHeader("X-MindHarbor-Task-ID",null)).isEqualTo(task.idempotencyKey);
        assertThat(mime.getContent().toString()).contains("确认接收并跟进");
    }
    @Test void providerRejectsSendingPastSafeReplayWindowWithoutCallingHttp() {
        var cfg=new MailWorkflowProperties();cfg.setProvider("resend");
        var gateway=new MailGateway(cfg,new MindHarborProperties(),mock(AlertNotifier.class),new ObjectMapper());
        var task=new ReportTask();task.provider="RESEND";task.firstSendAt=Instant.now().minusSeconds(24*3600);
        assertThatThrownBy(()->gateway.send(task,null,null)).hasMessageContaining("window expired");
    }
}
