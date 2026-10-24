package com.mindharbor.agent.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Fact changes only; no full conversation copies. Removed when the user deletes the memory. */
@Entity
@Table(name="user_memory_changes", indexes=@Index(name="idx_memory_change_owner",columnList="userId,memoryId"))
public class UserMemoryChange {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    public Long userId;
    public Long memoryId;
    public Long sourceMessageId;
    public long sourceOrder;
    public long fromRevision;
    public long toRevision;
    @Column(length=40) public String reason;
    @Column(length=32) public String priorStatus;
    @Column(length=32) public String resultingStatus;
    @Lob @Column(columnDefinition="LONGTEXT") public String beforeJson;
    @Lob @Column(columnDefinition="LONGTEXT") public String afterJson;
    public Instant recordedAt=Instant.now();
}
