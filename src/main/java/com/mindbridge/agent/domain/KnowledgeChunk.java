package com.mindbridge.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "knowledge_chunks")
/**
 * 知识库切块。
 *
 * <p>每个上传文件会被拆成多个 chunk；sourceIndex 用于恢复相邻上下文。</p>
 */
public class KnowledgeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 180)
    private String source;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int sourceIndex;

    private Integer pageNumber;
    @Column(length = 32)
    private String blockType;
    @Column(length = 80)
    private String imageRef;
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean needsReview;

    public Integer getPageNumber() { return pageNumber; }
    public void setPageNumber(Integer value) { pageNumber = value; }
    public String getBlockType() { return blockType; }
    public void setBlockType(String value) { blockType = value; }
    public String getImageRef() { return imageRef; }
    public void setImageRef(String value) { imageRef = value; }
    public boolean isNeedsReview() { return needsReview; }
    public void setNeedsReview(boolean value) { needsReview = value; }

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    @Lob
    private String embeddingJson;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public int getSourceIndex() {
        return sourceIndex;
    }

    public void setSourceIndex(int sourceIndex) {
        this.sourceIndex = sourceIndex;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getEmbeddingJson() {
        return embeddingJson;
    }

    public void setEmbeddingJson(String embeddingJson) {
        this.embeddingJson = embeddingJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
