package com.mindharbor.agent.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** One latest context snapshot per request; updated in place at each successful boundary. */
@Entity @Table(name="agent_checkpoints")
public class AgentCheckpoint {
    @Id @Column(length=64) public String requestId;
    public Long userId;
    public Long sessionId;
    public int schemaVersion=1;
    public int workflowVersion=1;
    public long revision;
    public int completedSteps;
    public int stepAttempts;
    @Column(length=24) public String status="PENDING";
    @Column(length=64) public String lastOwner;
    @Lob @Column(columnDefinition="LONGTEXT") public String snapshotJson;
    public Instant deadline;
    public Instant createdAt=Instant.now();
    public Instant updatedAt=Instant.now();
    @Column(length=120) public String errorType;
}
