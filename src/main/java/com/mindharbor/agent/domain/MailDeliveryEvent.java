package com.mindharbor.agent.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Durable webhook inbox: a signed callback may arrive before send-result persistence. */
@Entity @Table(name="mail_delivery_events",indexes=@Index(name="idx_mail_event_due",columnList="applied,nextAttempt"))
public class MailDeliveryEvent {
    @Id @Column(length=160) public String id;
    @Column(nullable=false,length=160) public String providerEmailId;
    @Column(nullable=false,length=40) public String type;
    @Column(length=80) public String mailKey;
    @Column(nullable=false) public Instant occurredAt;
    public Instant receivedAt=Instant.now();
    public Instant nextAttempt=Instant.now();
    @Column(length=120) public String note;
    public boolean applied;
}
