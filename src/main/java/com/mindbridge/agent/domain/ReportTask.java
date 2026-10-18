package com.mindbridge.agent.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Durable outbox, atomically inserted with the psychological report. */
@Entity
@Table(name = "report_tasks", uniqueConstraints = @UniqueConstraint(name="uk_report_task_round", columnNames = {"reportId", "kind", "recipient", "reminderRound"}))
public class ReportTask {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public Long reportId;
    @Column(nullable = false, length = 16) public String kind;
    @Column(nullable = false, length = 240) public String recipient = "";
    public Long alertId;
    @Column(nullable=false, columnDefinition="integer default 0") public int reminderRound;
    @Column(length=80, unique=true) public String idempotencyKey;
    @Column(length=20) public String provider;
    @Column(length=160, unique=true) public String providerEmailId;
    @Lob @Column(columnDefinition="LONGTEXT") public String mailPayload;
    public Instant firstSendAt;
    public Instant acceptedAt;
    @Column(length=24) public String deliveryStatus;
    public Instant deliveryEventAt;
    public Instant nextReconcileAt;
    @Column(nullable=false, columnDefinition="integer default 0") public int reconcileAttempts;
    @Column(nullable = false, length = 20) public String status = "PENDING";
    public int attempts;
    public Instant nextAttempt = Instant.now();
    public Instant leaseUntil;
    @Column(length = 64) public String owner;
    @Column(length = 500) public String error;
}
