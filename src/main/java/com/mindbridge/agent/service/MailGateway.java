package com.mindbridge.agent.service;

import com.fasterxml.jackson.databind.*;
import com.mindbridge.agent.config.*;
import com.mindbridge.agent.domain.*;
import com.mindbridge.agent.service.mcp.AlertNotifier;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class MailGateway {
    private final MailWorkflowProperties config;
    private final MindBridgeProperties app;
    private final AlertNotifier legacy;
    private final ObjectMapper json;
    private final com.mindbridge.agent.service.mcp.McpToolClient mcp;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public MailGateway(MailWorkflowProperties config,MindBridgeProperties app,AlertNotifier legacy,ObjectMapper json) {
        this(config,app,legacy,json,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public MailGateway(MailWorkflowProperties config,MindBridgeProperties app,AlertNotifier legacy,ObjectMapper json,
            com.mindbridge.agent.service.mcp.McpToolClient mcp) {
        this.config=config;this.app=app;this.legacy=legacy;this.json=json;this.mcp=mcp;
    }
    public static boolean resendProvider(String provider){return "RESEND".equals(provider)||"RESEND_MCP".equals(provider);}
    public boolean resend(){return "resend".equalsIgnoreCase(config.getProvider());}
    public boolean safeReplay(ReportTask task) {
        return resendProvider(task.provider) && task.firstSendAt!=null
                && Instant.now().isBefore(task.firstSendAt.plus(Duration.ofHours(23)));
    }
    public void prepare(ReportTask task, AlertRecord alert) {
        if(task.mailPayload!=null) return;
        try {
            task.provider="mcp-resend".equalsIgnoreCase(config.getProvider())?"RESEND_MCP":resend()?"RESEND":app.getMcp().getEmail().getMode().toUpperCase(Locale.ROOT);
            task.idempotencyKey="mindbridge-mail-"+UUID.randomUUID();
            String link=config.getPublicUrl().replaceAll("/+$","")+"/?alert="+alert.getId();
            String subject=task.reminderRound==0?"【MindBridge 高风险预警】待确认 #"+alert.getId()
                    :"【MindBridge 待确认提醒 "+task.reminderRound+"】预警 #"+alert.getId();
            // Keep sensitive conversation content in the authenticated application.
            task.mailPayload=json.writeValueAsString(Map.of("from",app.getMcp().getEmail().getFrom(),
                    "to",List.of(task.recipient),"subject",subject,"text",
                    "有一份高风险报告需要您查看。报告编号："+task.reportId+"。\n请登录网站查看详情，并主动点击“确认接收并跟进”。\n"
                    +link+"\n此邮件发送成功不等于您已确认。确认后将停止后续提醒。",
                    "tags",List.of(Map.of("name","mail_key","value",task.idempotencyKey))));
        } catch(Exception ex){throw new IllegalStateException("Cannot freeze mail payload",ex);}
    }
    public record Receipt(String id,String delivery) {}
    public Receipt send(ReportTask task,AlertRecord alert,PsychologicalReport report) throws Exception {
        if("RESEND_MCP".equals(task.provider)) {
            if(!safeReplay(task)) throw new IllegalStateException("Provider idempotency window expired");
            var result=mcp.callJson("mindbridge_send_risk_alert",Map.of("taskId",task.id,"reportId",task.reportId,
                    "alertId",task.alertId,"reminderRound",task.reminderRound,"idempotencyKey",task.idempotencyKey,
                    "firstSendAt",task.firstSendAt.toString(),"payloadJson",task.mailPayload));
            if("ACCEPTED".equals(result.path("status").asText()) && !result.path("providerEmailId").asText().isBlank())
                return new Receipt(result.path("providerEmailId").asText(),"ACCEPTED");
            throw new ProviderException("FAILED".equals(result.path("status").asText())?422:503);
        }
        if("RESEND".equals(task.provider)) {
            if(!safeReplay(task)) throw new IllegalStateException("Provider idempotency window expired; manual reconciliation required");
            JsonNode response=request("POST","/emails",task.mailPayload,task.idempotencyKey);
            String id=response.path("id").asText();
            if(id.isBlank()) throw new java.io.IOException("Provider accepted response without email id");
            return new Receipt(id,"ACCEPTED");
        }
        if(!task.provider.equalsIgnoreCase(app.getMcp().getEmail().getMode()))
            throw new IllegalStateException("Mail transport changed after task creation");
        legacy.notifyFrozen(task,alert,report);
        return new Receipt(null,"SMTP".equals(task.provider)?"ACCEPTED":"UNVERIFIED");
    }
    public String lookup(String id) throws Exception {
        if(!id.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Invalid provider id");
        return request("GET","/emails/"+id,null,null).path("last_event").asText("unknown");
    }
    public String lookup(String provider,String id) throws Exception {
        if(!"RESEND_MCP".equals(provider))return lookup(id);
        var result=mcp.callJson("mindbridge_get_mail_status",Map.of("providerEmailId",id));
        if(!"OK".equals(result.path("status").asText()))throw new java.io.IOException("MCP delivery lookup failed");
        return result.path("deliveryStatus").asText("unknown");
    }
    private JsonNode request(String method,String path,String payload,String key) throws Exception {
        if(config.getApiKey().isBlank()) throw new IllegalStateException("Resend API key is not configured");
        HttpRequest.Builder builder=HttpRequest.newBuilder(URI.create(config.getApiBase().replaceAll("/+$","")+path))
                .timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+config.getApiKey());
        if(payload!=null) builder.header("Content-Type","application/json").header("Idempotency-Key",key)
                .POST(HttpRequest.BodyPublishers.ofString(payload)); else builder.GET();
        HttpResponse<String> response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()/100!=2) throw new ProviderException(response.statusCode());
        return json.readTree(response.body());
    }
    public static class ProviderException extends java.io.IOException {
        public final int code;
        public ProviderException(int code){super("Mail provider HTTP "+code);this.code=code;}
        public boolean permanent(){return code>=400 && code<500 && code!=408 && code!=429 && code!=409;}
    }
}
