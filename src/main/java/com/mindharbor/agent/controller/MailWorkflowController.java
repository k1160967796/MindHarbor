package com.mindharbor.agent.controller;

import com.mindharbor.agent.service.*;
import com.mindharbor.agent.security.CurrentUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
public class MailWorkflowController {
    private final MailWorkflowService workflow;private final MailWebhookService webhook;
    public MailWorkflowController(MailWorkflowService workflow,MailWebhookService webhook){this.workflow=workflow;this.webhook=webhook;}
    @GetMapping("/api/alerts/{id}")
    public Mono<MailWorkflowService.Detail> detail(@PathVariable Long id,@AuthenticationPrincipal CurrentUser user) {
        return Mono.fromCallable(()->workflow.detail(id,user)).subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping("/api/alerts/{id}/acknowledge")
    public Mono<MailWorkflowService.Detail> acknowledge(@PathVariable Long id,@AuthenticationPrincipal CurrentUser user,
            @RequestHeader(value="X-MindHarbor-Action",defaultValue="") String action) {
        // Explicit custom header prevents cross-site form submissions with ambient Basic credentials.
        if(!"acknowledge".equals(action))throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST);
        return Mono.fromCallable(()->workflow.acknowledge(id,user)).subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping("/api/mail/webhooks/resend")
    public Mono<Void> callback(@RequestHeader(value="svix-id",required=false) String id,
            @RequestHeader(value="svix-timestamp",required=false) String timestamp,
            @RequestHeader(value="svix-signature",required=false) String signature,@RequestBody String raw) {
        return Mono.fromRunnable(()->webhook.receive(id,timestamp,signature,raw)).subscribeOn(Schedulers.boundedElastic()).then();
    }
}
