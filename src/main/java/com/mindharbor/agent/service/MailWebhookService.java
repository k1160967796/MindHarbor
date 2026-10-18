package com.mindharbor.agent.service;

import com.fasterxml.jackson.databind.*;
import com.mindharbor.agent.config.MailWorkflowProperties;
import com.mindharbor.agent.domain.MailDeliveryEvent;
import com.mindharbor.agent.repository.MailDeliveryEventRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MailWebhookService {
    private final MailWorkflowProperties config;private final ObjectMapper json;
    private final MailDeliveryEventRepository events;private final TransactionTemplate tx;
    public MailWebhookService(MailWorkflowProperties config,ObjectMapper json,MailDeliveryEventRepository events,TransactionTemplate tx) {
        this.config=config;this.json=json;this.events=events;this.tx=tx;
    }
    public void receive(String id,String timestamp,String signature,String raw) {
        if(config.getWebhookSecret().isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Webhook not configured");
        verify(id,timestamp,signature,raw,config.getWebhookSecret(),Instant.now());
        MailDeliveryEvent event=new MailDeliveryEvent();event.id=id;
        try {
            JsonNode body=json.readTree(raw);
            event.providerEmailId=body.path("data").path("email_id").asText();
            event.type=body.path("type").asText();event.occurredAt=Instant.parse(body.path("created_at").asText());
            JsonNode tags=body.path("data").path("tags");
            String taskTag=tags.path("mail_key").asText("");
            if(tags.isArray()) for(JsonNode tag:tags) if("mail_key".equals(tag.path("name").asText()))taskTag=tag.path("value").asText("");
            if(taskTag.matches("mindharbor-mail-[a-f0-9-]{36}"))event.mailKey=taskTag;
            if(event.providerEmailId.isBlank()||event.providerEmailId.length()>160||event.type.length()>40
                    ||!event.type.startsWith("email."))throw new IllegalArgumentException();
        }catch(Exception ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid mail event");}
        try {
            tx.executeWithoutResult(s->{if(!events.existsById(id))events.saveAndFlush(event);});
        }catch(org.springframework.dao.DataIntegrityViolationException duplicate) {
            if(!events.existsById(id))throw duplicate;
        }
        // Return success only after the signed event is durably stored; the poller applies it.
    }
    public static void verify(String id,String timestamp,String signatures,String raw,String secret,Instant now) {
        try {
            if(id==null||id.isBlank()||id.length()>160||timestamp==null||signatures==null||raw==null||raw.length()>262144)throw new IllegalArgumentException();
            long seconds=Long.parseLong(timestamp);
            if(seconds<now.getEpochSecond()-300||seconds>now.getEpochSecond()+300)throw new IllegalArgumentException();
            byte[] key=Base64.getDecoder().decode(secret.startsWith("whsec_")?secret.substring(6):secret);
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            byte[] expected=mac.doFinal((id+"."+timestamp+"."+raw).getBytes(StandardCharsets.UTF_8));
            for(String signature:signatures.split(" ")) {
                if(signature.startsWith("v1,") && MessageDigest.isEqual(expected,Base64.getDecoder().decode(signature.substring(3))))return;
            }
        }catch(Exception ignored) { }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid webhook signature");
    }
}
