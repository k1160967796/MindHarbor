package com.mindbridge.agent.repository;

import com.mindbridge.agent.domain.ChatMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 聊天消息的数据访问接口。
 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select m from ChatMessage m join fetch m.user join fetch m.session where m.requestId = :requestId")
    java.util.Optional<ChatMessage> lockRequest(String requestId);

    @org.springframework.data.jpa.repository.Query("select m from ChatMessage m where m.session.id = :sessionId and (m.replyStatus is null or m.replyStatus = com.mindbridge.agent.domain.ReplyStatus.SUCCESS) order by m.createdAt desc, m.id desc")
    List<ChatMessage> findMemoryHistory(Long sessionId, org.springframework.data.domain.Pageable pageable);

    @org.springframework.data.jpa.repository.Query("select m.requestId from ChatMessage m where m.requestId is not null and m.replyStatus in (com.mindbridge.agent.domain.ReplyStatus.GENERATING, com.mindbridge.agent.domain.ReplyStatus.RETRYING, com.mindbridge.agent.domain.ReplyStatus.INTERRUPTED) and m.leaseUntil < :now")
    List<String> findExpiredRequests(java.time.Instant now);

    /** 取最近消息用于模型上下文，Service 层会再反转为时间正序。 */
    List<ChatMessage> findTop20BySession_IdOrderByCreatedAtDesc(Long sessionId);

    /** 管理员点开记录时读取完整会话。 */
    List<ChatMessage> findBySession_PublicIdOrderByCreatedAtAsc(String publicId);
}
