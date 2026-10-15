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
| GET | `/api/v1/workflows/{runId}/events` | ordered audit trail of every transition, decision and agent result |
| POST | `/api/v1/workflows/{runId}/approvals` | approve or reject a task at an approval gate (`taskId`, `decision`, `approver`, `reason`) |
| POST | `/api/v1/workflows/{runId}/replan` | new requirement version; re-runs only invalidated tasks (`requirement`, optional `fromTaskId`, `reason`) |
| GET | `/api/v1/metrics` | runs by status, success rate, retries, recoveries, rollbacks, approvals, replans, latency |

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
- `GraphRunner` is the framework-free execution loop:
  - **Parallel:** independent tasks (`implementation`, `security`) run concurrently on a bounded agent pool, capped by `orchestration.max-parallel-tasks`.
  - **Single writer:** only the runner thread changes the graph; agents run on workers and return results, so no workflow state is shared across threads.
  - **Retries:** retryable failures are retried with exponential backoff (`orchestration.max-retries`, `orchestration.retry.*`). Policy and validation failures are not retried.
  - **Approval gates:** tasks flagged by the policy wait in `AWAITING_APPROVAL`. Other branches keep running; the run pauses only when nothing else can progress.
  - **Rollback:** when a run fails or is cancelled, succeeded tasks with staged changes (`staging/...`) are rolled back, tasks behind the failure are `BLOCKED`, and open approvals are `CANCELLED`.
  - Every status change goes through `RunStateMachine`. A run succeeds only if every task succeeded.
- `PolicyEngine` maps each agent to a governed action (`CODE_MERGE`, `RELEASE`, ...). `MANUAL` gates every task, `CONTROLLED` gates the actions in `approvalRequiredFor`, `AUTONOMOUS` gates none.
- `ApproverPolicy` restricts who may decide (`orchestration.approvers`). Others get 403.
- Replanning records requirement v(n+1) and re-runs only the changed task and everything downstream of it, plus anything that did not complete. Upstream results are kept.
- Agents (`agent/`) implement one `EngineeringAgent` contract. They receive an immutable `AgentContext` with upstream outputs and return an `AgentResult` as evidence. They never change workflow state themselves.
- `WorkflowService` persists each run, task and transition as it happens. Paused runs are resumed by rebuilding the graph from the database, so they survive a restart.
- `FaultInjector` makes the next N executions of an agent type fail, to demonstrate retries and rollback.

Approval example:

```bash
# start with CODE_MERGE gated; the run pauses at implementation
curl -s -X POST localhost:8080/api/v1/workflows -H 'Content-Type: application/json' -d '{
  "scenario":"GREENFIELD","requirement":"Build a URL shortener","autonomyLevel":"CONTROLLED",
  "approvalRequiredFor":["CODE_MERGE"]}'

curl -s -X POST localhost:8080/api/v1/workflows/$RUN/approvals -H 'Content-Type: application/json' \
  -d '{"taskId":"implementation","decision":"APPROVE","approver":"tech-lead","reason":"design reviewed"}'
```

## Scenarios

All three run on the same engine; they differ in plan and in the decisions the policy requires.

```
GREENFIELD / AMBIGUOUS
  requirements -> architecture -> implementation --> test -> documentation
                               \-> security -------/
BROWNFIELD
  requirements -> impact -> reproduce -> implementation --> test -> documentation
                         \-> security -----------------/
```

| Scenario | What happens |
|---|---|
| `GREENFIELD` | Requirement analysis produces acceptance criteria; design, parallel implementation and security review, tests, engineering summary. |
| `BROWNFIELD` | Impact analysis maps the defect onto this repo's URL shortener (classes, files, test suites), a failing reproduction test is written before any fix, the fix must stay within 3 files, and the reproduction test plus impacted regression suites must pass. An unknown defect fails fast without retries. |
| `AMBIGUOUS` | If requirement analysis finds vague or open-ended wording, the run stops at a **decision gate** before architecture, even in `AUTONOMOUS` mode. A human either approves (proceed on the recorded assumptions, which the summary then lists) or replans with a clarified requirement (v2), which re-runs from requirements. |

```bash
# ambiguous: pauses at the decision gate
curl -s -X POST localhost:8080/api/v1/workflows -H 'Content-Type: application/json' -d '{
  "scenario":"AMBIGUOUS","autonomyLevel":"AUTONOMOUS",
  "requirement":"Build a fast, scalable URL shortener with analytics etc."}'

# clarify instead of accepting assumptions
curl -s -X POST localhost:8080/api/v1/workflows/$RUN/replan -H 'Content-Type: application/json' -d '{
  "requirement":"Build a URL shortener; links expire after 30 days; redirects respond within 50 ms; analytics count unique visitors per day.",
  "reason":"Product owner clarified"}'

# brownfield defect
curl -s -X POST localhost:8080/api/v1/workflows -H 'Content-Type: application/json' -d '{
  "scenario":"BROWNFIELD","autonomyLevel":"AUTONOMOUS",
  "requirement":"Fix defect: expired short links still redirect at the exact expiry time"}'
```

## AI integration

The Requirement agent is model-backed through `LlmClient`:

- `ai.provider=mock` (default): `MockLlmClient` answers with deterministic, rule-based output in the same line format a real model is asked for. CI and tests need no credentials.
- `ai.provider=anthropic`: `AnthropicLlmClient` calls the Claude Messages API using Spring's `RestClient` (no extra dependency). Set `ANTHROPIC_API_KEY`; optionally `ANTHROPIC_MODEL` (default `claude-sonnet-5-5`). Startup fails fast if the key is missing.

```bash
AI_PROVIDER=anthropic ANTHROPIC_API_KEY=sk-... ./mvnw spring-boot:run
```

The model only recommends. `RequirementAnalyzer` owns the prompt, parses `CRITERION / AMBIGUITY / ASSUMPTION` lines and rejects replies without criteria. A provider outage maps to `LLM_UNAVAILABLE` (retried with backoff); unusable output maps to `VALIDATION_FAILED` (not retried). Whether ambiguities block the run is decided by `PolicyEngine`, not by the model. Graph validation, dependency readiness, retries, approvals and rollback stay deterministic.

Next: Dockerfile and compose app service, architecture / security / testing docs, demo script.

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
