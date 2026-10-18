package com.mindbridge.agent.repository;

import com.mindbridge.agent.domain.ReportTask;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;

public interface ReportTaskRepository extends JpaRepository<ReportTask, Long> {
    List<ReportTask> findByReportId(Long reportId);
    List<ReportTask> findByAlertIdOrderByReminderRoundAsc(Long alertId);
    Optional<ReportTask> findFirstByProviderEmailId(String providerEmailId);
    Optional<ReportTask> findFirstByIdempotencyKey(String idempotencyKey);
    @Query("select t.id from ReportTask t where t.provider = 'RESEND' and t.providerEmailId is not null and t.nextReconcileAt <= :now order by t.nextReconcileAt")
    List<Long> reconcileDue(Instant now, Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ReportTask t where t.id = :id")
    Optional<ReportTask> lock(Long id);
    @Query("select t.id from ReportTask t where (t.status in ('PENDING', 'RETRYING') and t.nextAttempt <= :now) or (t.status = 'RUNNING' and t.leaseUntil < :now) order by t.id")
    List<Long> due(Instant now, Pageable page);
}
