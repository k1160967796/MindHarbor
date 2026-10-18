package com.mindbridge.agent.repository;

import com.mindbridge.agent.domain.AlertRecord;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 预警发送记录的数据访问接口。
 */
public interface AlertRecordRepository extends JpaRepository<AlertRecord, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select a from AlertRecord a where a.id = :id")
    java.util.Optional<AlertRecord> lock(Long id);
    @org.springframework.data.jpa.repository.Query("select a.id from AlertRecord a where a.acknowledgedAt is null and a.escalatedAt is null and a.nextReminderAt <= :now order by a.nextReminderAt")
    List<Long> reminderDue(java.time.Instant now, org.springframework.data.domain.Pageable page);

    List<AlertRecord> findByReport_Id(Long reportId);

    /**
     * 管理员后台列表需要同时展示报告、学生账号和会话 id，使用 EntityGraph 避免懒加载反复查询。
     */
    @EntityGraph(attributePaths = {"report", "report.user", "report.session"})
    List<AlertRecord> findTop100ByOrderByCreatedAtDesc();
}
