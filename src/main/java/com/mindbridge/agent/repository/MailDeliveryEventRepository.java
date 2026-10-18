package com.mindbridge.agent.repository;
import com.mindbridge.agent.domain.MailDeliveryEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface MailDeliveryEventRepository extends JpaRepository<MailDeliveryEvent,String> {
    @org.springframework.data.jpa.repository.Query("select e from MailDeliveryEvent e where e.applied=false and e.nextAttempt <= :now order by e.nextAttempt")
    List<MailDeliveryEvent> due(java.time.Instant now,org.springframework.data.domain.Pageable page);
}
