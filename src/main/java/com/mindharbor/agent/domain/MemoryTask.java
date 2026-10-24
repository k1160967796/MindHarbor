package com.mindharbor.agent.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="memory_tasks", indexes={@Index(name="idx_memory_due", columnList="status,nextAttempt"),
        @Index(name="idx_memory_user_order", columnList="userId,sourceOrder")})
public class MemoryTask {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(unique=true,nullable=false,length=100) public String taskKey;
    public Long userId;
    public Long sessionId;
    public Long sourceOrder;
    public Long sourceMessageId;
    public Instant assertedAt;
    @Column(length=64) public String requestId;
    @Column(length=20) public String kind;
    @Column(length=20) public String status="PENDING";
    public int attempts;
    public Long memoryId;
    @Lob @Column(columnDefinition="LONGTEXT") public String input;
    @Column(length=64) public String owner;
    public Instant leaseUntil;
    public Instant nextAttempt=Instant.now();
    public Instant createdAt=Instant.now();
    public Instant updatedAt=Instant.now();
    @Column(length=100) public String errorType;
    @Column(length=120) public String resultNote;
}
