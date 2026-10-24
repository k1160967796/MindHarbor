package com.mindharbor.agent.repository;

import com.mindharbor.agent.domain.UserMemoryItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 用户画像记忆的数据访问接口。
 */
public interface UserMemoryItemRepository extends JpaRepository<UserMemoryItem, Long> {

    List<UserMemoryItem> findTop12ByUser_IdOrderByUpdatedAtDesc(Long userId);

    List<UserMemoryItem> findByUser_IdOrderByUpdatedAtDesc(Long userId);

    @org.springframework.data.jpa.repository.Query("select m from UserMemoryItem m where m.user.id=:userId and m.factStatus=com.mindharbor.agent.domain.MemoryFactStatus.ACTIVE and (m.expiresAt is null or m.expiresAt>:now) order by m.updatedAt desc")
    List<UserMemoryItem> findCurrent(@org.springframework.data.repository.query.Param("userId") Long userId,
            @org.springframework.data.repository.query.Param("now") java.time.Instant now,org.springframework.data.domain.Pageable page);
}
