package com.assessment.agentic_system.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demo-only endpoint to make the next N executions of an agent type fail, so retries, rollback and
 * blocked tasks can be shown live. Disabled unless
 * {@code orchestration.demo.fault-injection-enabled=true}; when disabled it answers 404.
 */
@RestController
@RequestMapping("/api/v1/demo/faults")
public class FaultInjectionController {

    private final FaultInjector faults;
    private final AgentRegistry agents;
    private final boolean enabled;

    public FaultInjectionController(FaultInjector faults, AgentRegistry agents,
            @Value("${orchestration.demo.fault-injection-enabled:false}") boolean enabled) {
        this.faults = faults;
        this.agents = agents;
        this.enabled = enabled;
    }

    public record FaultRequest(@NotBlank String agentType, @Min(1) @Max(20) int times) {
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> inject(@Valid @RequestBody FaultRequest request) {
        if (!enabled) {
            return ResponseEntity.notFound().build();
        }
        if (!agents.supports(request.agentType())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown agent type", "known", agents.types()));
        }
        faults.failNext(request.agentType(), request.times());
        return ResponseEntity.ok(Map.of("agentType", request.agentType(), "failNext", request.times()));
    }

    @DeleteMapping
    public ResponseEntity<Void> reset() {
        if (!enabled) {
            return ResponseEntity.notFound().build();
        }
        faults.reset();
        return ResponseEntity.noContent().build();
    }
}
