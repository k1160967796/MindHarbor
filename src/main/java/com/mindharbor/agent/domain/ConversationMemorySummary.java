package com.mindharbor.agent.domain;
import jakarta.persistence.*;
import java.time.Instant;

@Entity @Table(name="conversation_memory_summaries")
public class ConversationMemorySummary {
    @Id public Long sessionId;
    @Lob @Column(columnDefinition="LONGTEXT") public String content;
    public Instant coveredAt;
    public Long coveredMessageId;
    public long revision;
    public Instant updatedAt;
}
