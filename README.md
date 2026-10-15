# agentic-system

An agentic software-engineering orchestration engine in Java 21 and Spring Boot 4. Given a requirement, it plans a validated dependency graph of engineering tasks, runs specialised agents in parallel where possible, and governs the run with approval gates, bounded retries, rollback and dynamic replanning. Every step is persisted and audited. A URL shortener with expiry and click analytics, in the same repository, is the demonstration workload and the brownfield codebase.

**Design principle:** models recommend, the engine decides. Graph validation, readiness, permissions, approvals, retries, rollback and completion are deterministic and unit-tested; the model contributes content through a validated interface.

| Document | Contents |
|---|---|
| [docs/engineering-summary.md](docs/engineering-summary.md) | What was built, key decisions, limitations, next steps |
| [docs/architecture.md](docs/architecture.md) | Components, execution model, task lifecycle, governance, data model, trade-offs |
| [docs/security.md](docs/security.md) | Controls in place and known gaps |
| [docs/testing.md](docs/testing.md) | Test strategy, suites, requirement-to-test traceability |

## Quick start

```bash
./mvnw test                 # all tests: mock AI provider, in-memory H2, no Docker or credentials
./mvnw spring-boot:run      # http://localhost:8080 with in-memory H2 (PostgreSQL mode)
./scripts/demo.sh           # walk through every capability (needs curl and jq)
```

Full stack with PostgreSQL in containers:

```bash
cp .env.example .env        # optional: set AI_PROVIDER=anthropic and ANTHROPIC_API_KEY
docker compose up --build
./scripts/demo.sh
```

Database only, app from your IDE or Maven:

```bash
docker compose up -d postgres
./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
```

Useful URLs: Swagger UI `/swagger-ui.html`, health `/actuator/health`, H2 console `/h2-console` (local profile, JDBC URL `jdbc:h2:mem:agentic`).

## What the demo shows

`scripts/demo.sh` runs against a live instance and prints the evidence for each step:

1. **Greenfield, autonomous:** plan, parallel implementation and security review, validated engineering summary.
2. **Governance:** `CODE_MERGE` waits for approval; an unauthorized approver gets 403; an authorized one resumes the run.
3. **Retry and recovery:** the security agent fails twice, is retried with backoff, and succeeds.
4. **Retry exhaustion and rollback:** the run fails, staged implementation work is rolled back, downstream tasks are blocked.
5. **Ambiguous requirement:** decision gate even in autonomous mode; clarification replans to requirement v2, which completes.
6. **Brownfield defect:** impact analysis, failing reproduction test, bounded fix, regression.
7. **Metrics.**

## Workflow API

| Method | Path | Result |
|---|---|---|
| POST | `/api/v1/workflows` | 202 + `runId`; runs in the background (`scenario`, `requirement`, `autonomyLevel`, `approvalRequiredFor`) |
| GET | `/api/v1/workflows/{runId}` | run status, requirement version, progress |
| GET | `/api/v1/workflows/{runId}/tasks` | each task's status, attempts, outputs, artifacts, error, timing |
| GET | `/api/v1/workflows/{runId}/events` | ordered audit trail of every transition, decision and agent result |
| POST | `/api/v1/workflows/{runId}/approvals` | approve or reject a waiting task (`taskId`, `decision`, `approver`, `reason`) |
| POST | `/api/v1/workflows/{runId}/replan` | new requirement version; re-runs only invalidated tasks (`requirement`, optional `fromTaskId`, `reason`) |
| GET | `/api/v1/metrics` | runs by status, success rate, retries, recoveries, rollbacks, approvals, replans, latency |
| POST / DELETE | `/api/v1/demo/faults` | demo only, off by default: make the next N runs of an agent type fail (`agentType`, `times`) |

Autonomy levels: `MANUAL` gates every task, `CONTROLLED` (default) gates the actions listed in `approvalRequiredFor` (`ANALYSIS`, `DESIGN`, `CODE_MERGE`, `SECURITY_REVIEW`, `TEST_RUN`, `RELEASE`), `AUTONOMOUS` gates none. Approvers are configured in `orchestration.approvers` (default `tech-lead`, `release-manager`).

```bash
RUN=$(curl -s -X POST localhost:8080/api/v1/workflows -H 'Content-Type: application/json' -d '{
  "scenario": "GREENFIELD",
  "requirement": "Build a URL shortener with expiration and click analytics.",
  "autonomyLevel": "CONTROLLED",
  "approvalRequiredFor": ["CODE_MERGE"]
}' | jq -r .runId)

curl -s localhost:8080/api/v1/workflows/$RUN/tasks | jq '.[] | {taskId, status}'

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
| `GREENFIELD` | Requirement analysis produces acceptance criteria; design; implementation and security review in parallel; tests sized from the criteria; engineering summary. |
| `BROWNFIELD` | Impact analysis maps the defect onto this repo's URL shortener (classes, files, test suites); a failing reproduction test is written before any fix; the fix must stay within 3 files; the reproduction test and impacted regression suites must pass. An untraceable defect fails without retries. |
| `AMBIGUOUS` | If requirement analysis finds vague or open-ended wording, the run stops at a **decision gate** before architecture, even in `AUTONOMOUS` mode. A human approves (proceed on the recorded assumptions, which the summary lists) or replans with a clarified requirement (v2). |

```bash
# ambiguous: pauses at the decision gate
curl -s -X POST localhost:8080/api/v1/workflows -H 'Content-Type: application/json' -d '{
  "scenario":"AMBIGUOUS","autonomyLevel":"AUTONOMOUS",
  "requirement":"Build a fast, scalable URL shortener with analytics etc."}'

# clarify instead of accepting the assumptions
curl -s -X POST localhost:8080/api/v1/workflows/$RUN/replan -H 'Content-Type: application/json' -d '{
  "requirement":"Build a URL shortener; links expire after 30 days; redirects respond within 50 ms; analytics count unique visitors per day.",
  "reason":"Product owner clarified"}'

# brownfield defect
curl -s -X POST localhost:8080/api/v1/workflows -H 'Content-Type: application/json' -d '{
  "scenario":"BROWNFIELD","autonomyLevel":"AUTONOMOUS",
  "requirement":"Fix defect: expired short links still redirect at the exact expiry time"}'
```

## How the engine works

- `WorkflowPlanner` turns a scenario into a `TaskGraph`, which rejects cycles, unknown and duplicate tasks at construction.
- `GraphRunner` is the framework-free execution loop:
  - **Parallel:** independent tasks run concurrently on a bounded agent pool (`orchestration.max-parallel-tasks`).
  - **Single writer:** only the runner thread changes the graph; agents return results, so no workflow state is shared across threads.
  - **Retries:** retryable failures are retried with exponential backoff (`orchestration.max-retries`, `orchestration.retry.*`); policy and validation failures are not.
  - **Approval gates:** tasks flagged by policy wait in `AWAITING_APPROVAL`; other branches keep running; the run pauses only when nothing else can progress.
  - **Rollback:** if a run fails or is cancelled, succeeded tasks with staged changes are rolled back, downstream tasks are `BLOCKED`, open approvals are `CANCELLED`.
  - Every status change goes through `RunStateMachine`. A run succeeds only if every task succeeded.
- `PolicyEngine` decides gates from autonomy, requested approvals and upstream evidence (such as ambiguities). `ApproverPolicy` restricts who may decide.
- `WorkflowService` persists every run, task and transition as it happens. Approvals and replans resume a run by rebuilding it from the database, so paused runs survive a restart.
- Replanning records requirement v(n+1) and re-runs only the changed task, its downstream tasks and anything incomplete. Upstream results are kept.

## AI integration

The Requirement agent is model-backed through `LlmClient`:

- `AI_PROVIDER=mock` (default): `MockLlmClient` returns deterministic, rule-based output in the same line format a real model is asked for. CI needs no credentials.
- `AI_PROVIDER=anthropic`: `AnthropicLlmClient` calls the Claude Messages API via Spring's `RestClient`. Set `ANTHROPIC_API_KEY`; optionally `ANTHROPIC_MODEL` (default `claude-sonnet-5-5`). Startup fails fast without a key.

```bash
AI_PROVIDER=anthropic ANTHROPIC_API_KEY=... ./mvnw spring-boot:run
```

`RequirementAnalyzer` owns the prompt, parses `CRITERION / AMBIGUITY / ASSUMPTION` lines and rejects replies without criteria. A provider outage is `LLM_UNAVAILABLE` (retried); unusable output is `VALIDATION_FAILED` (not retried). Whether ambiguities block a run is a policy decision, not the model's.

## URL shortener API (the workload)

| Method | Path | Result |
|---|---|---|
| POST | `/api/v1/urls` | 201, creates a short URL (`targetUrl`, optional `expiresAt`) |
| GET | `/api/v1/urls/{code}` | 200, URL details |
| DELETE | `/api/v1/urls/{code}` | 204 |
| GET | `/api/v1/urls/{code}/analytics` | 200, click count, last click, expiry state |
| GET | `/r/{code}` | 302 redirect; 404 unknown; 410 expired |

## Project layout

```
src/main/java/com/assessment/agentic_system/
  orchestration/  TaskGraph, RunStateMachine, GraphRunner, WorkflowPlanner, WorkflowService, REST API
  agent/          EngineeringAgent contract, registry, eight agents, fault injection
  ai/             LlmClient (mock, Anthropic), RequirementAnalyzer
  governance/     PolicyEngine, ApproverPolicy
  execution/      RetryPolicy
  audit/          run events, metrics
  shortener/      URL shortener workload
  common/         error mapping, clock, JSON codec
src/main/resources/db/migration/   Flyway V1-V3
docs/                              architecture, security, testing, engineering summary
scripts/demo.sh                    end-to-end demo
Dockerfile, compose.yaml           container build and local stack
.github/workflows/ci.yml           tests and image build
```
