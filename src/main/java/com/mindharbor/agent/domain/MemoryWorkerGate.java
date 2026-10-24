package com.mindharbor.agent.domain;
import jakarta.persistence.*;
import java.time.Instant;

/** One durable background-worker lease for the local-model deployment. */
@Entity @Table(name="memory_worker_gate")
public class MemoryWorkerGate {
    @Id public Long id=1L;
    public Instant lastForegroundAt=Instant.now();
    @Column(length=64) public String owner;
    public Instant leaseUntil;
}
