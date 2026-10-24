package com.mindharbor.agent.service.ai;

/** Immutable correlation data. Synchronous scopes MUST close; streams use Reactor Context. */
public record ModelCallContext(String requestId, String sessionId, String phase, int generation, int attempt) {
    public static final String KEY = ModelCallContext.class.getName();
    private static final ThreadLocal<ModelCallContext> LOCAL = new ThreadLocal<>();
    public static ModelCallContext current() {
        var value = LOCAL.get();
        return value == null ? new ModelCallContext(null, null, "UNATTRIBUTED", 0, 0) : value;
    }
    public ModelCallContext phase(String phase) {
        return new ModelCallContext(requestId, sessionId, phase, generation, attempt);
    }
    public static Scope use(ModelCallContext value) {
        var old = LOCAL.get(); LOCAL.set(value);
        return () -> { if (old == null) LOCAL.remove(); else LOCAL.set(old); };
    }
    public interface Scope extends AutoCloseable { @Override void close(); }
}
