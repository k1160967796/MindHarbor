package com.mindharbor.agent.repository;

import com.mindharbor.agent.domain.ModelCallRecord;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelCallRecordRepository extends JpaRepository<ModelCallRecord, String> {
    List<ModelCallRecord> findTop100ByOrderByStartedAtDesc();
    List<ModelCallRecord> findByRequestIdOrderByStartedAtAsc(String requestId);
}
