package com.mindbridge.agent.domain;

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
@Table(name = "chat_messages")
/**
 * 单条会话消息。
 *
 * <p>保存学生输入、助手回复和必要的系统消息，用于后续上下文构建和管理员完整会话回看。</p>
 */
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id")
    private ChatSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private UserAccount user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageRole role;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    // Legacy rows have a null status and remain readable. Only SUCCESS replies enter memory.
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ReplyStatus replyStatus;
    @Column(unique = true, length = 64)
    private String requestId;
    @Lob @Column(columnDefinition = "LONGTEXT")
    private String promptJson;
    @Column(length = 4000)
    private String requestInput;
    private Integer attempts = 0;
    private Integer generation = 1;
    @Column(length = 64)
    private String executionOwner;
    private Instant leaseUntil;
    private Instant generationDeadline;
    private Long remainingMillis = 120000L;
    @Column(length = 500)
    private String replyError;

    public ReplyStatus getReplyStatus() { return replyStatus; }
    public void setReplyStatus(ReplyStatus value) { replyStatus = value; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String value) { requestId = value; }
    public String getPromptJson() { return promptJson; }
    public void setPromptJson(String value) { promptJson = value; }
    public String getRequestInput() { return requestInput; }
    public void setRequestInput(String value) { requestInput = value; }
    public int getAttempts() { return attempts == null ? 0 : attempts; }
    public void setAttempts(int value) { attempts = value; }
    public int getGeneration() { return generation == null ? 1 : generation; }
    public void setGeneration(int value) { generation = value; }
    public String getExecutionOwner() { return executionOwner; }
    public void setExecutionOwner(String value) { executionOwner = value; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public void setLeaseUntil(Instant value) { leaseUntil = value; }
    public Instant getGenerationDeadline() { return generationDeadline; }
    public void setGenerationDeadline(Instant value) { generationDeadline = value; }
    public long getRemainingMillis() { return remainingMillis == null ? 120000L : remainingMillis; }
    public void setRemainingMillis(long value) { remainingMillis = value; }
    public String getReplyError() { return replyError; }
    public void setReplyError(String value) { replyError = value; }

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    public void setCreatedAt(Instant value) { createdAt = value; }

    public Long getId() {
        return id;
    }

    public ChatSession getSession() {
        return session;
    }

    public void setSession(ChatSession session) {
        this.session = session;
    }

    public UserAccount getUser() {
        return user;
    }

    public void setUser(UserAccount user) {
        this.user = user;
    }

    public MessageRole getRole() {
        return role;
    }

    public void setRole(MessageRole role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
