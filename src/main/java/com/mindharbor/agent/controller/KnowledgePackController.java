package com.mindharbor.agent.controller;

import com.mindharbor.agent.service.knowledge.ReviewedKnowledgePackService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** /api/admin/** is protected by the existing ADMIN authorization rule. */
@RestController
@RequestMapping("/api/admin/knowledge/packs")
public class KnowledgePackController {
    private final ReviewedKnowledgePackService service;
    public KnowledgePackController(ReviewedKnowledgePackService service) { this.service = service; }
    @GetMapping
    public Mono<List<ReviewedKnowledgePackService.Preview>> list() {
        return Mono.fromCallable(service::previews).subscribeOn(Schedulers.boundedElastic());
    }
    public record Approval(@NotBlank String digest) {}
    @PutMapping("/{id}/approve")
    public Mono<Void> approve(@PathVariable String id, @Valid @RequestBody Approval request, Principal principal) {
        return Mono.<Void>fromRunnable(() -> service.approve(id, request.digest(), principal.getName()))
                .subscribeOn(Schedulers.boundedElastic());
    }
    @PutMapping("/{id}/disable")
    public Mono<Void> disable(@PathVariable String id) {
        return Mono.<Void>fromRunnable(() -> service.disable(id)).subscribeOn(Schedulers.boundedElastic());
    }
}
