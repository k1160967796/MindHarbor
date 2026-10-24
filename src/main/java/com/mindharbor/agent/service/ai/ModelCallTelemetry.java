package com.mindharbor.agent.service.ai;

import com.mindharbor.agent.domain.ModelCallRecord;
import com.mindharbor.agent.repository.ModelCallRecordRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;

@Service
public class ModelCallTelemetry {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ModelCallTelemetry.class);
    private final ModelCallRecordRepository repository;
    private final MeterRegistry meters;
    // Never perform JDBC on a model/SSE event-loop thread, never use an unbounded queue.
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1000), task -> {
                Thread thread = new Thread(task, "model-observation-writer"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public ModelCallTelemetry(ModelCallRecordRepository repository, MeterRegistry meters) {
        this.repository = repository; this.meters = meters;
        meters.gauge("mindharbor.model.persistence.queue", writer, e -> e.getQueue().size());
    }

    public Call start(ModelCallContext context, String provider, String model, String operation) {
        return new Call(context, provider, model, operation);
    }

    public final class Call {
        private final ModelCallRecord record = new ModelCallRecord();
        private final long startNanos = System.nanoTime();
        private final AtomicBoolean finished = new AtomicBoolean();
        private boolean hasText;
        private Call(ModelCallContext context, String provider, String model, String operation) {
            record.id = UUID.randomUUID().toString(); record.requestId = context.requestId();
            record.sessionId = context.sessionId(); record.phase = context.phase();
            record.generation = context.generation(); record.attempt = context.attempt();
            record.provider = provider; record.model = shorten(model, 160); record.operation = operation;
            record.startedAt = Instant.now(); record.usageSource = "UNAVAILABLE";
        }
        public synchronized void response(ChatResponse response, boolean streaming) {
            if (finished.get() || response == null) return;
            String reason = ModelFinishReason.from(response);
            if (reason != null) {
                record.finishReason = shorten(reason, 40);
                record.truncated = ModelFinishReason.truncated(reason);
            }
            if (response.getResult() != null && response.getResult().getOutput() != null) {
                String text = response.getResult().getOutput().getText();
                if (text != null && !text.isEmpty()) {
                    hasText = true;
                    if (streaming && record.firstTokenMs == null) record.firstTokenMs = elapsed();
                }
            }
            var metadata = response.getMetadata();
            if (metadata == null) return;
            if (metadata.getModel() != null && !metadata.getModel().isBlank()) record.model = shorten(metadata.getModel(), 160);
            var usage = metadata.getUsage();
            // Spring AI may manufacture zero usage for chunks lacking provider metadata.
            // Keep only nonempty reported usage; do not add repeated cumulative stream totals.
            if (usage != null && !(usage instanceof EmptyUsage) && usage.getTotalTokens() != null
                    && usage.getTotalTokens() > 0) {
                usage(usage.getPromptTokens() == null ? null : usage.getPromptTokens().longValue(),
                        usage.getCompletionTokens() == null ? null : usage.getCompletionTokens().longValue(),
                        usage.getTotalTokens().longValue());
            }
        }
        public synchronized void usage(Long input, Long output, Long total) {
            if (finished.get()) return;
            if (total == null || total < 0 || (input != null && input < 0) || (output != null && output < 0)) return;
            record.inputTokens = input; record.outputTokens = output; record.totalTokens = total;
            record.usageSource = "PROVIDER";
        }
        public void success() { finish(Boolean.TRUE.equals(record.truncated) ? "TRUNCATED" : hasText ? "SUCCESS" : "EMPTY", null); }
        public void error(Throwable error) {
            String outcome = "ERROR";
            for (Throwable cause = error; cause != null; cause = cause.getCause())
                if (cause instanceof TimeoutException) { outcome = "TIMEOUT"; break; }
            finish(outcome, error);
        }
        public synchronized void finish(String outcome, Throwable error) {
            if (!finished.compareAndSet(false, true)) return;
            record.durationMs = elapsed(); record.outcome = outcome;
            record.errorType = error == null ? null : shorten(error.getClass().getSimpleName(), 100);
            publish(record);
        }
        private long elapsed() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos); }
    }

    private void publish(ModelCallRecord record) {
        try {
            // No request/user/session IDs, exception messages or provider-returned model names in metric labels.
            String[] tags = {"provider", record.provider, "phase", record.phase,
                    "operation", record.operation, "outcome", record.outcome};
            meters.timer("mindharbor.model.call", tags).record(record.durationMs, TimeUnit.MILLISECONDS);
            if (record.firstTokenMs != null) meters.timer("mindharbor.model.first.token", tags)
                    .record(record.firstTokenMs, TimeUnit.MILLISECONDS);
            if (record.inputTokens != null) meters.counter("mindharbor.model.tokens.input", tags).increment(record.inputTokens);
            if (record.outputTokens != null) meters.counter("mindharbor.model.tokens.output", tags).increment(record.outputTokens);
            if (record.totalTokens != null) meters.counter("mindharbor.model.tokens.total", tags).increment(record.totalTokens);
            if (record.totalTokens == null) meters.counter("mindharbor.model.usage.missing", tags).increment();
            writer.execute(() -> {
                try { repository.save(record); }
                catch (Exception ignored) { dropped("database"); }
            });
        } catch (RejectedExecutionException ignored) { dropped("queue_full"); }
        catch (Exception ignored) { dropped("metrics"); }
    }
    private void dropped(String reason) {
        meters.counter("mindharbor.model.persistence.dropped", "reason", reason).increment();
        log.warn("Model observation was not persisted; reason={}", reason);
    }
    static String shorten(String value, int length) { return value == null ? "unknown" : value.substring(0, Math.min(value.length(), length)); }
    @PreDestroy public void close() {
        writer.shutdown();
        try { if (!writer.awaitTermination(5, TimeUnit.SECONDS)) writer.shutdownNow(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); writer.shutdownNow(); }
    }
}
