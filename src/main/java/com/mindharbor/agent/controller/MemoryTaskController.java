package com.mindharbor.agent.controller;
import com.mindharbor.agent.service.memory.MemoryMaintenanceService;
import java.time.Instant;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController @RequestMapping("/api/admin/memory-tasks")
public class MemoryTaskController {
    private final MemoryMaintenanceService tasks;
    public MemoryTaskController(MemoryMaintenanceService tasks) {this.tasks=tasks;}
    public record Row(Long id,String requestId,Long userId,Long sessionId,String kind,String status,int attempts,
                      Instant createdAt,Instant updatedAt,Instant nextAttempt,String errorType,String resultNote) {}
    @GetMapping public Mono<List<Row>> list() {
        return Mono.fromCallable(() -> tasks.recentTasks().stream().map(t -> new Row(t.id,t.requestId,t.userId,t.sessionId,t.kind,t.status,t.attempts,t.createdAt,t.updatedAt,t.nextAttempt,t.errorType,t.resultNote)).toList())
                .subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping("/{id}/retry") public Mono<Void> retry(@PathVariable Long id) {
        return Mono.<Void>fromRunnable(() -> tasks.retry(id)).subscribeOn(Schedulers.boundedElastic());
    }
}
