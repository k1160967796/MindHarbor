package com.mindharbor.agent.controller;

import com.mindharbor.agent.domain.ModelCallRecord;
import com.mindharbor.agent.repository.ModelCallRecordRepository;
import java.util.List;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Existing /api/admin/** rule restricts all observations to administrators. */
@RestController
@RequestMapping("/api/admin/model-calls")
public class ModelObservationController {
    private final ModelCallRecordRepository repository;
    public ModelObservationController(ModelCallRecordRepository repository) { this.repository = repository; }
    public record Report(String scope, int calls, long unsuccessfulCalls, long usageReportedCalls,
                         long usageMissingCalls, long knownInputTokens, long knownOutputTokens,
                         long knownTotalTokens, long summedCallMs, List<ModelCallRecord> records) {
        public static Report from(String scope, List<ModelCallRecord> rows) {
            return new Report(scope, rows.size(), rows.stream().filter(r -> !"SUCCESS".equals(r.outcome)).count(),
                    rows.stream().filter(r -> r.totalTokens != null).count(),
                    rows.stream().filter(r -> r.totalTokens == null).count(),
                    rows.stream().mapToLong(r -> r.inputTokens == null ? 0 : r.inputTokens).sum(),
                    rows.stream().mapToLong(r -> r.outputTokens == null ? 0 : r.outputTokens).sum(),
                    rows.stream().mapToLong(r -> r.totalTokens == null ? 0 : r.totalTokens).sum(),
                    rows.stream().mapToLong(r -> r.durationMs).sum(), rows);
        }
    }
    @GetMapping public Mono<Report> list(@RequestParam(required=false) String requestId) {
        return Mono.fromCallable(() -> Report.from(requestId == null ? "LATEST_100_CALLS" : "REQUEST",
                requestId == null ? repository.findTop100ByOrderByStartedAtDesc()
                        : repository.findByRequestIdOrderByStartedAtAsc(requestId)))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
