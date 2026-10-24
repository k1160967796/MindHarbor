package com.mindharbor.agent.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Metadata only: never store prompts, completions, credentials or raw exception messages. */
@Entity
@Table(name="model_call_records", indexes={
        @Index(name="idx_model_call_request", columnList="requestId,startedAt"),
        @Index(name="idx_model_call_started", columnList="startedAt")})
public class ModelCallRecord {
    @Id @Column(length=36) public String id;
    @Column(length=64) public String requestId;
    @Column(length=64) public String sessionId;
    @Column(length=48) public String phase;
    public int generation;
    public int attempt;
    @Column(length=32) public String provider;
    @Column(length=160) public String model;
    @Column(length=24) public String operation;
    public Instant startedAt;
    public long durationMs;
    public Long firstTokenMs;
    @Column(length=24) public String outcome;
    @Column(length=40) public String finishReason;
    public Boolean truncated;
    @Column(length=100) public String errorType;
    public Long inputTokens;
    public Long outputTokens;
    public Long totalTokens;
    @Column(length=24) public String usageSource;
}
