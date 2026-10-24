package com.mindharbor.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindharbor.agent.domain.*;
import com.mindharbor.agent.dto.ChatRequest;
import com.mindharbor.agent.repository.*;
import com.mindharbor.agent.service.ai.AiMessage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Short database transactions arbitrate producers; no model or tool calls under these locks. */
@Service
public class ReplyStore {
    public static final int MAX_ATTEMPTS = 3;
    public static final int GENERATION_SECONDS = 120;
    private final ChatMessageRepository messages;
    private final ChatSessionRepository sessions;
    private final EntityManager em;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final com.mindharbor.agent.service.memory.MemoryMaintenanceService memoryTasks;
    private final com.mindharbor.agent.service.agent.AgentCheckpointStore checkpoints;

    public ReplyStore(ChatMessageRepository messages, ChatSessionRepository sessions,
                      EntityManager em, TransactionTemplate tx, ObjectMapper json,
                      com.mindharbor.agent.service.memory.MemoryMaintenanceService memoryTasks,
                      com.mindharbor.agent.service.agent.AgentCheckpointStore checkpoints) {
        this.messages = messages; this.sessions = sessions; this.em = em; this.tx = tx; this.json = json;this.memoryTasks=memoryTasks;
        this.checkpoints=checkpoints;
    }

    public record View(String requestId, Long replyId, String sessionId, ReplyStatus status,
                       int attempts, int generation, String content, String error, boolean prepared, int continuationCount) {}
    public record Ticket(View view, String owner, UserAccount user, ChatSession session, String input,
                         List<AiMessage> prompt, Instant deadline, boolean prepare) {}

    public Ticket open(Long userId, ChatRequest request) {
        String id = request.requestId() == null ? UUID.randomUUID().toString() : request.requestId();
        return tx.execute(s -> {
            memoryTasks.touchForeground();
            // Serialize idempotency-key creation for this user, including first-session creation.
            UserAccount user = em.find(UserAccount.class, userId, LockModeType.PESSIMISTIC_WRITE);
            if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
            var existing = messages.lockRequest(id);
            if (existing.isPresent()) {
                ChatMessage m = authorized(existing.get(), userId);
                if (!Objects.equals(m.getRequestInput(), request.message().trim()))
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "请求编号已用于其他消息");
                return claim(m);
            }
            ChatSession session;
            if (request.sessionId() != null && !request.sessionId().isBlank()) {
                session = sessions.findByPublicIdAndUser_Id(request.sessionId(), userId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            } else {
                session = new ChatSession(); session.setUser(user);
                session.setPublicId(UUID.randomUUID().toString().replace("-", ""));
                session.setTitle(request.message().substring(0, Math.min(36, request.message().length())));
                sessions.save(session);
            }
            ChatMessage m = new ChatMessage(); m.setUser(user); m.setSession(session);
            m.setRole(MessageRole.ASSISTANT); m.setContent(""); m.setRequestId(id);
            m.setRequestInput(request.message().trim()); m.setReplyStatus(ReplyStatus.GENERATING);
            m.setExecutionOwner(UUID.randomUUID().toString()); m.setLeaseUntil(Instant.now().plusSeconds(180));
            messages.saveAndFlush(m);
            checkpoints.initialize(m);
            return ticket(m, m.getExecutionOwner(), true);
        });
    }

    public View status(Long userId, String id) {
        return tx.execute(s -> { var m = get(id, userId); expire(m); return view(m); });
    }

    public Ticket resume(Long userId, String id, Integer expectedGeneration) {
        return tx.execute(s -> {
            memoryTasks.touchForeground();
            var m = get(id, userId); expire(m);
            // Manual regeneration uses a generation CAS so duplicate button clicks cannot reset attempts.
            if (expectedGeneration != null && m.getReplyStatus() == ReplyStatus.FAILED
                    && m.getPromptJson() != null && m.getGeneration() == expectedGeneration) {
                m.setAttempts(0); m.setGeneration(m.getGeneration() + 1);
                m.setContinuationCount(0);
                m.setGenerationDeadline(null); m.setReplyStatus(ReplyStatus.INTERRUPTED); m.setReplyError(null);
                m.setRemainingMillis(GENERATION_SECONDS * 1000L);
            }
            return claim(m);
        });
    }

    private Ticket claim(ChatMessage m) {
        expire(m);
        if (m.getReplyStatus() != ReplyStatus.INTERRUPTED || m.getExecutionOwner()!=null)
            return ticket(m, null, false);
        if(m.getPromptJson()==null) {
            String problem=checkpoints.recoveryProblem(m);
            if(problem!=null){fail(m,problem);return ticket(m,null,false);}
            m.setExecutionOwner(UUID.randomUUID().toString());m.setReplyStatus(ReplyStatus.RETRYING);m.setReplyError(null);
            m.setLeaseUntil(Instant.now().plusSeconds(180));
            return ticket(m,m.getExecutionOwner(),true);
        }
        if (m.getAttempts() >= MAX_ATTEMPTS) { fail(m, "已达到三次尝试上限"); return ticket(m, null, false); }
        // A disconnected client can come back later; only the remaining generation budget is reused.
        if (m.getRemainingMillis() <= 0) {
            fail(m, "本轮生成时间预算已用尽，请重新生成"); return ticket(m, null, false);
        }
        m.setExecutionOwner(UUID.randomUUID().toString()); m.setReplyStatus(ReplyStatus.RETRYING);
        m.setLeaseUntil(Instant.now().plusSeconds(GENERATION_SECONDS + 10));
        return ticket(m, m.getExecutionOwner(), false);
    }

    public Ticket prepared(Ticket ticket, List<AiMessage> prompt, Consumer<ChatMessage> persistRelated) {
        return tx.execute(s -> {
            var m = get(ticket.view().requestId(), ticket.user().getId());
            // Cancellation during preparation leaves the owner in place until preparation commits.
            if (!Objects.equals(ticket.owner(), m.getExecutionOwner()) || m.getPromptJson() != null
                    || m.getLeaseUntil()==null || !m.getLeaseUntil().isAfter(Instant.now()))
                throw new IllegalStateException("Preparation lease lost");
            persistRelated.accept(m);
            try { m.setPromptJson(json.writeValueAsString(prompt)); }
            catch (Exception ex) { throw new IllegalStateException(ex); }
            checkpoints.committed(m);
            m.setLeaseUntil(Instant.now().plusSeconds(GENERATION_SECONDS + 10));
            if (m.getReplyStatus() == ReplyStatus.INTERRUPTED) { m.setExecutionOwner(null); m.setLeaseUntil(null); }
            return ticket(m, m.getExecutionOwner(), false);
        });
    }

    public Ticket attempt(Ticket t) {
        return tx.execute(s -> {
            var m = owned(t);
            if (m.getAttempts() >= MAX_ATTEMPTS) throw new IllegalStateException("Attempt limit reached");
            if (m.getGenerationDeadline() == null) m.setGenerationDeadline(Instant.now().plusMillis(m.getRemainingMillis()));
            if (!m.getGenerationDeadline().isAfter(Instant.now())) throw new IllegalStateException("Generation deadline exceeded");
            m.setAttempts(m.getAttempts() + 1);
            m.setReplyStatus(m.getAttempts() == 1 ? ReplyStatus.GENERATING : ReplyStatus.RETRYING);
            m.setLeaseUntil(m.getGenerationDeadline().plusSeconds(10));
            return ticket(m, t.owner(), false);
        });
    }

    /** Persist the one-continuation budget across retry/disconnect; reset only on explicit regeneration. */
    public boolean reserveContinuation(Ticket t) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            var m = owned(t);
            if (m.getContinuationCount() >= 1) return false;
            m.setContinuationCount(1);
            return true;
        }));
    }

    public boolean success(Ticket t, String content) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            memoryTasks.touchForeground();
            var m = get(t.view().requestId(), t.user().getId());
            if (!Objects.equals(t.owner(), m.getExecutionOwner()) || m.getReplyStatus() == ReplyStatus.INTERRUPTED) return false;
            m.setContent(content); m.setReplyStatus(ReplyStatus.SUCCESS); m.setReplyError(null);
            m.setCreatedAt(Instant.now());
            m.setExecutionOwner(null); m.setLeaseUntil(null); m.getSession().touch(); return true;
        }));
    }

    public void failure(Ticket t, String reason) {
        tx.executeWithoutResult(s -> { memoryTasks.touchForeground(); var m = get(t.view().requestId(), t.user().getId());
            if (Objects.equals(t.owner(), m.getExecutionOwner())) fail(m, reason); });
    }

    public void preparationFailure(Ticket t) {
        tx.executeWithoutResult(s -> {
            memoryTasks.touchForeground();var m=get(t.view().requestId(),t.user().getId());
            if(!Objects.equals(t.owner(),m.getExecutionOwner()) || m.getPromptJson()!=null)return;
            String problem=checkpoints.recoveryProblem(m);
            if(problem!=null)fail(m,problem);
            else {m.setReplyStatus(ReplyStatus.INTERRUPTED);m.setReplyError("准备阶段中断，将从已保存进度恢复");m.setExecutionOwner(null);m.setLeaseUntil(null);}
        });
    }

    public Ticket resumePreparation(String id) {
        return tx.execute(s -> {
            memoryTasks.touchForeground();var m=messages.lockRequest(id).orElse(null);
            if(m==null || m.getPromptJson()!=null)return null;
            return claim(m);
        });
    }

    public void interrupt(Ticket t) {
        tx.executeWithoutResult(s -> { memoryTasks.touchForeground(); var m = get(t.view().requestId(), t.user().getId());
            if (Objects.equals(t.owner(), m.getExecutionOwner()) && m.getReplyStatus() != ReplyStatus.SUCCESS) {
                m.setReplyStatus(ReplyStatus.INTERRUPTED); m.setContent("");
                pauseBudget(m);
                if (m.getPromptJson() != null) { m.setExecutionOwner(null); m.setLeaseUntil(null); }
            }
        });
    }

    @Scheduled(fixedDelay = 15000)
    public void recoverExpired() {
        for (String id : messages.findExpiredRequests(Instant.now()))
            tx.executeWithoutResult(s -> messages.lockRequest(id).ifPresent(this::expire));
    }
    private void expire(ChatMessage m) {
        if (m.getLeaseUntil() != null && m.getLeaseUntil().isBefore(Instant.now()) && m.getExecutionOwner() != null) {
            if (m.getPromptJson() == null) {
                String problem=checkpoints.recoveryProblem(m);
                if(problem!=null){fail(m,problem);return;}
            }
            pauseBudget(m); m.setReplyStatus(ReplyStatus.INTERRUPTED); m.setExecutionOwner(null); m.setLeaseUntil(null);
        }
    }
    private void pauseBudget(ChatMessage m) {
        if (m.getGenerationDeadline() != null) {
            m.setRemainingMillis(Math.max(0, java.time.Duration.between(Instant.now(), m.getGenerationDeadline()).toMillis()));
            m.setGenerationDeadline(null);
        }
    }
    private void fail(ChatMessage m, String reason) {
        m.setReplyStatus(ReplyStatus.FAILED); m.setContent(""); m.setExecutionOwner(null); m.setLeaseUntil(null);
        m.setReplyError(reason == null ? "生成失败" : reason.substring(0, Math.min(500, reason.length())));
    }
    private ChatMessage owned(Ticket t) {
        var m = get(t.view().requestId(), t.user().getId());
        if (t.owner() == null || !Objects.equals(t.owner(), m.getExecutionOwner()) || m.getReplyStatus() == ReplyStatus.INTERRUPTED)
            throw new IllegalStateException("Execution lease lost");
        return m;
    }
    private ChatMessage get(String id, Long userId) {
        return authorized(messages.lockRequest(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)), userId);
    }
    private ChatMessage authorized(ChatMessage m, Long userId) {
        if (!m.getUser().getId().equals(userId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return m;
    }
    private View view(ChatMessage m) {
        return new View(m.getRequestId(), m.getId(), m.getSession().getPublicId(), m.getReplyStatus(), m.getAttempts(),
                m.getGeneration(), m.getContent(), m.getReplyError(), m.getPromptJson() != null, m.getContinuationCount());
    }
    private Ticket ticket(ChatMessage m, String owner, boolean prepare) {
        List<AiMessage> prompt = List.of();
        if (m.getPromptJson() != null) try { prompt = json.readValue(m.getPromptJson(), new TypeReference<List<AiMessage>>() {}); }
        catch (Exception ex) { throw new IllegalStateException("Invalid saved prompt", ex); }
        // Initialize fields used outside the transaction.
        m.getUser().getDisplayName();
        return new Ticket(view(m), owner, m.getUser(), m.getSession(), m.getRequestInput(), prompt, m.getGenerationDeadline(), prepare);
    }
}
