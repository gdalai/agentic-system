# agentic-system

An agentic software-engineering orchestration engine built on Java 21 and Spring Boot 4, with a URL shortener as its demonstration workload.

## Run locally

```bash
./mvnw test               # unit + web-layer tests
./mvnw spring-boot:run    # starts on :8080 with in-memory H2 (PostgreSQL mode)
```

Against real PostgreSQL:

```bash
docker compose up -d
./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
```

Useful URLs:

- Swagger UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health
- H2 console (local profile): http://localhost:8080/h2-console, JDBC URL `jdbc:h2:mem:agentic`

## URL shortener API

| Method | Path | Result |
|---|---|---|
| POST | `/api/v1/urls` | 201, creates a short URL (`targetUrl`, optional `expiresAt`) |
| GET | `/api/v1/urls/{code}` | 200, URL details |
| DELETE | `/api/v1/urls/{code}` | 204 |
| GET | `/api/v1/urls/{code}/analytics` | 200, click count, last click, expiry state |
| GET | `/r/{code}` | 302 redirect; 404 unknown; 410 expired |

```bash
curl -s -X POST localhost:8080/api/v1/urls \
  -H 'Content-Type: application/json' \
  -d '{"targetUrl":"https://example.com"}'
```

## Workflow API

| Method | Path | Result |
|---|---|---|
| POST | `/api/v1/workflows` | 202 + `runId`; the run executes in the background |
| GET | `/api/v1/workflows/{runId}` | run status and progress |
| GET | `/api/v1/workflows/{runId}/tasks` | each task's status, attempts, outputs, errors |
| GET | `/api/v1/workflows/{runId}/events` | ordered audit trail of every transition and agent result |

```bash
RUN=$(curl -s -X POST localhost:8080/api/v1/workflows -H 'Content-Type: application/json' -d '{
  "scenario": "GREENFIELD",
  "requirement": "Build a URL shortener with expiration and click analytics.",
  "autonomyLevel": "CONTROLLED",
  "approvalRequiredFor": ["CODE_MERGE", "RELEASE"]
}' | jq -r .runId)

curl -s localhost:8080/api/v1/workflows/$RUN | jq
curl -s localhost:8080/api/v1/workflows/$RUN/tasks | jq
curl -s localhost:8080/api/v1/workflows/$RUN/events | jq
```

## How the engine works

```
requirements -> architecture -> implementation --> test -> documentation
                             \-> security -------/
```

- `WorkflowPlanner` turns a scenario into a `TaskGraph`. Construction validates the DAG (cycles and unknown dependencies are rejected).
- `GraphRunner` is the framework-free execution loop. It runs ready tasks, moves every status through `RunStateMachine`, and marks tasks behind a failure as `BLOCKED`. A run succeeds only if every task succeeded.
- Agents (`agent/`) implement one `EngineeringAgent` contract. They receive an immutable `AgentContext` with upstream outputs and return an `AgentResult` as evidence. They never change workflow state themselves.
- `WorkflowService` persists each run, task and transition as it happens (`workflow_run`, `task_execution`, `run_event`), so progress is visible while a run executes.
- `FaultInjector` makes the next N executions of an agent type fail, for demonstrating failure handling.

Next: parallel execution, policy and approval gates, retries, rollback, replanning, metrics, AI adapter, scenarios.

## Project layout

```
src/main/java/com/assessment/agentic_system/
  shortener/      URL shortener (demo workload)
  orchestration/  DAG, state machine, (next) WorkflowEngine, Replanner
  agent/          specialised engineering agents
  governance/     policy engine, approvals, safe stop
  execution/      retry, fallback, rollback, artifact validation
  audit/          run events and metrics
  ai/             LlmClient with Spring AI and deterministic mock
  common/         error handling and shared config
```
