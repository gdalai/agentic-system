package com.assessment.agentic_system.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunEventRepository extends JpaRepository<RunEvent, Long> {

    List<RunEvent> findByRunIdOrderByIdAsc(UUID runId);

    long countByEventType(RunEventType eventType);

    long countByEventTypeAndToStatus(RunEventType eventType, String toStatus);
}
