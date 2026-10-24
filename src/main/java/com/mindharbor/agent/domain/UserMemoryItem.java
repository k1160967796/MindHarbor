package com.mindharbor.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "user_memory_items",uniqueConstraints=@jakarta.persistence.UniqueConstraint(name="uk_memory_fact_scope",columnNames={"user_id","fact_key","fact_scope"}))
/**
 * 用户长期画像记忆。
 *
 * <p>保存从对话中抽取出的稳定偏好、沟通方式、支持需求和可复用背景，
 * 原始完整对话仍由 ChatMessage 作为权威记录。</p>
 */
public class UserMemoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private UserAccount user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_session_id")
    private ChatSession sourceSession;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private UserMemoryType type = UserMemoryType.PREFERENCE;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String summary;
    @Column(length=80) private String memoryKey;
    private Long sourceOrder;
    @Column(name="fact_key",length=50) private String factKey;
    @Column(length=120) private String factValue;
    @Column(name="fact_scope",length=24) private String factScope;
    @Enumerated(EnumType.STRING) @Column(length=32) private MemoryFactStatus factStatus;
    private Long sourceMessageId;
    private Instant assertedAt;
    private Instant effectiveAt;
    private Instant expiresAt;
    private Long revision;
    public String getFactKey() { return factKey; }
    public void setFactKey(String value) { factKey=value; }
    public String getFactValue() { return factValue; }
    public void setFactValue(String value) { factValue=value; }
    public String getFactScope() { return factScope==null?"UNKNOWN":factScope; }
    public void setFactScope(String value) { factScope=value; }
    // Existing rows remain visible to the owner, but are not silently promoted to current facts.
    public MemoryFactStatus getFactStatus() { return factStatus==null?MemoryFactStatus.NEEDS_CONFIRMATION:factStatus; }
    public void setFactStatus(MemoryFactStatus value) { factStatus=value; }
    public Long getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(Long value) { sourceMessageId=value; }
    public Instant getAssertedAt() { return assertedAt; }
    public void setAssertedAt(Instant value) { assertedAt=value; }
    public Instant getEffectiveAt() { return effectiveAt; }
    public void setEffectiveAt(Instant value) { effectiveAt=value; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant value) { expiresAt=value; }
    public long getRevision() { return revision==null?0:revision; }
    public void setRevision(long value) { revision=value; }
    public MemoryFactStatus statusAt(Instant now) {
        return getFactStatus()==MemoryFactStatus.ACTIVE && expiresAt!=null && !expiresAt.isAfter(now)
                ?MemoryFactStatus.EXPIRED:getFactStatus();
    }
    public boolean currentAt(Instant now) { return statusAt(now)==MemoryFactStatus.ACTIVE; }
    public String getMemoryKey() { return memoryKey; }
    public void setMemoryKey(String value) { memoryKey=value; }
    public long getSourceOrder() { return sourceOrder==null?0:sourceOrder; }
    public void setSourceOrder(long value) { sourceOrder=value; }

    @Lob
    private String evidence;

    @Column(nullable = false)
    private double confidence = 0.7;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(nullable = false)
    private Instant lastSeenAt = Instant.now();

    public Long getId() {
        return id;
    }

    public UserAccount getUser() {
        return user;
    }

    public void setUser(UserAccount user) {
        this.user = user;
    }

    public ChatSession getSourceSession() {
        return sourceSession;
    }

    public void setSourceSession(ChatSession sourceSession) {
        this.sourceSession = sourceSession;
    }

    public UserMemoryType getType() {
        return type;
    }

    public void setType(UserMemoryType type) {
        this.type = type;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void refreshSeen(ChatSession session, String evidence, double confidence) {
        this.sourceSession = session;
        this.evidence = evidence;
        this.confidence = confidence;
        this.updatedAt = Instant.now();
        this.lastSeenAt = Instant.now();
    }
}
