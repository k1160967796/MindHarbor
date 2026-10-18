package com.mindharbor.agent.repository;

import com.mindharbor.agent.domain.PsychologicalReport;
import com.mindharbor.agent.domain.ToolStatus;
import java.util.List;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 心理报告的数据访问接口。
 */
public interface PsychologicalReportRepository extends JpaRepository<PsychologicalReport, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select p from PsychologicalReport p where p.id = :id")
    java.util.Optional<PsychologicalReport> lock(Long id);

    /** 当前用户自己的报告查询，主要保留给接口扩展。 */
    @EntityGraph(attributePaths = {"user", "session"})
    List<PsychologicalReport> findTop50ByUser_IdOrderByCreatedAtDesc(Long userId);

    /** 管理员后台报告列表。 */
    @EntityGraph(attributePaths = {"user", "session"})
    List<PsychologicalReport> findTop100ByOrderByCreatedAtDesc();

    /** Excel 写入记录页面只需要写入成功的报告。 */
    @EntityGraph(attributePaths = {"user", "session"})
    List<PsychologicalReport> findTop100ByExcelStatusOrderByCreatedAtDesc(ToolStatus excelStatus);
}
