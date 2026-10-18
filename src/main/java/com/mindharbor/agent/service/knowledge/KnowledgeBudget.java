package com.mindharbor.agent.service.knowledge;

import java.time.Duration;

/** 每轮检索共用单调时钟 deadline；网络调用使用剩余预算，不重复重置计时。 */
public final class KnowledgeBudget {
    private final long started = System.nanoTime();
    private final long timeoutNanos;

    public KnowledgeBudget(long timeoutMs) {
        timeoutNanos = Duration.ofMillis(Math.max(1, timeoutMs)).toNanos();
    }

    public long remainingMs() {
        return Math.max(0, (timeoutNanos - (System.nanoTime() - started)) / 1_000_000);
    }

    public long elapsedMs() { return (System.nanoTime() - started) / 1_000_000; }

    public static String failureReason(String stage, Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof java.net.SocketTimeoutException) return stage + "_TIMEOUT";
        }
        return stage + "_FAILED";
    }

    public Duration remaining(long operationLimitMs) {
        long millis = Math.min(remainingMs(), operationLimitMs);
        if (millis <= 0) throw new IllegalStateException("KNOWLEDGE_BUDGET_EXHAUSTED");
        return Duration.ofMillis(millis);
    }
}
