package com.mindharbor.agent.domain;

import jakarta.persistence.*;
import java.time.Instant;

/** Administrator approval is separate from chunk publication and model review. */
@Entity
@Table(name = "reviewed_knowledge_packs")
public class ReviewedKnowledgePack {
    @Id
    private String id;
    @Version
    private Long version;
    @Column(nullable = false)
    private boolean approved;
    @Column(nullable = false, length = 64)
    private String revision;
    @Lob @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String snapshotJson;
    private String reviewedBy;
    private Instant reviewedAt;
    private Instant expiresAt;

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public boolean isApproved() { return approved; }
    public void setApproved(boolean value) { approved = value; }
    public String getRevision() { return revision; }
    public void setRevision(String value) { revision = value; }
    public String getSnapshotJson() { return snapshotJson; }
    public void setSnapshotJson(String value) { snapshotJson = value; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String value) { reviewedBy = value; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant value) { reviewedAt = value; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant value) { expiresAt = value; }
}
