package com.assessment.agentic_system.orchestration.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskExecutionRepository extends JpaRepository<TaskExecution, Long> {

    List<TaskExecution> findByRunIdOrderByIdAsc(UUID runId);

    Optional<TaskExecution> findByRunIdAndTaskId(UUID runId, String taskId);
}
