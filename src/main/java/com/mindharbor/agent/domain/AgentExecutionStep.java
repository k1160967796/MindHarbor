package com.mindharbor.agent.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Small progress record, without history, knowledge text, or complete context. */
@Entity @Table(name="agent_execution_steps",uniqueConstraints=@UniqueConstraint(columnNames={"requestId","stepNumber"}))
public class AgentExecutionStep {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(length=64,nullable=false) public String requestId;
    public int stepNumber;
    @Column(length=40) public String agent;
    @Column(length=40) public String action;
    @Column(length=24) public String status;
    public int attempts;
    public Instant startedAt;
    public Instant completedAt;
    public long durationMs;
    @Column(length=2000) public String observation;
    @Column(length=120) public String errorType;
}
