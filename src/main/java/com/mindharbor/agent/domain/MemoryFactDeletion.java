package com.mindharbor.agent.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Content-free tombstone prevents an already queued task from recreating a user-deleted fact. */
@Entity @Table(name="memory_fact_deletions",uniqueConstraints=@UniqueConstraint(columnNames={"userId","factKey","factScope"}))
public class MemoryFactDeletion {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    public Long userId;
    @Column(length=50) public String factKey;
    @Column(length=24) public String factScope;
    public Instant deletedAt;
}
