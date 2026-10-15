# Engineering summary

## What was built

An agentic software-engineering orchestration engine in Java 21 and Spring Boot 4. Given a requirement, it plans a dependency graph of engineering tasks, executes them with specialised agents in parallel where possible, and governs the run with approvals, retries, rollback and replanning. Every step is persisted and audited. A URL shortener with expiry and click analytics, in the same repository, is both the greenfield workload and the brownfield codebase.

| Capability | Implementation |
|---|---|
| Dependency graph | `TaskGraph`: validated DAG (cycles, unknown and duplicate tasks rejected), ready-set and downstream-impact queries |
| Persistent state | `workflow_run`, `task_execution`, `run_event`; paused runs are rebuilt from the database, so they survive restarts |
| Parallel execution | Bounded agent pool; independent tasks overlap; single writer thread owns all state changes |
| Approvals | `PolicyEngine` action gates by autonomy level; authorized approvers only; approve / reject API |
| Retries | `RetryPolicy`: bounded, exponential backoff, non-retryable error classes |
| Rollback | Staged changes of succeeded tasks discarded on failure or cancellation; downstream `BLOCKED`, open approvals `CANCELLED` |
| Dynamic replanning | New requirement version; only the changed task, its downstream and incomplete tasks re-run |
| AI integration | `LlmClient` with deterministic mock (default) and Claude adapter; structured, validated output |
| Scenarios | Greenfield, brownfield (impact -> failing reproduction -> bounded fix -> regression), ambiguous (decision gate -> approve on assumptions or clarify and replan) |
| Observability | Ordered audit trail per run; `/api/v1/metrics` for outcomes, retries, recoveries, rollbacks, approvals, replans and latency |
| Delivery | GitHub Actions (tests + image build), multi-stage non-root Docker image, compose stack, demo script |

## Key decisions

1. **The orchestrator is the product, not the model.** Graph validation, readiness, permissions, retries, approvals, rollback and completion are deterministic Java with unit tests. The model contributes content (acceptance criteria, ambiguities, assumptions) through a validated interface. This is what makes the system auditable and safe to give autonomy to.
2. **Framework-free core.** `GraphRunner` takes a handler, a gate and a listener. Spring, persistence and agents plug in from outside, so concurrency, retry and approval semantics are tested in milliseconds without a container or database.
3. **Single writer for concurrency.** Agents run in parallel but only return results; one runner thread applies all transitions. This removes a class of race conditions instead of managing them with locks.
4. **Pause by persisting, not by blocking.** A run waiting for a human holds no thread. Resume rebuilds state from the database, which is also what makes restarts safe.
5. **Policy decides on evidence.** Approval gates receive the outputs of upstream tasks, so a rule such as "stop if requirements are ambiguous" is one policy function, independent of any agent.
6. **One deployable.** Package boundaries instead of services. Nothing in the workload justifies distributed-systems cost, and the seams (`LlmClient`, `EngineeringAgent`, `GraphRunner` listener) are where services would split later.
7. **Deterministic by default.** Mock model, injected faults and an injected clock make every test and every demo reproducible.

## How the scenarios map to the requirements

- **Greenfield:** requirement -> 5 acceptance criteria -> design -> implementation and security review in parallel -> tests sized from the criteria -> engineering summary.
- **Brownfield:** the defect "expired links still redirect at the exact expiry time" is traced to `ShortUrl`, `UrlService` and `UrlController`. A failing reproduction test is written before the fix, the fix is bounded to 3 files, and the reproduction test plus impacted regression suites must pass. An untraceable defect fails immediately rather than retrying.
- **Ambiguous:** "fast, scalable ... etc." yields three ambiguities with proposed assumptions. The run stops at a decision gate even in autonomous mode. Approving proceeds on the recorded assumptions, which the final summary lists. Clarifying replans to requirement v2 and completes with no ambiguities.

## Quality evidence

- Unit tests for every core component, including a latch-based proof that independent tasks actually overlap, and end-to-end tests through HTTP for all three scenarios, every failure mode and every governance path. See `docs/testing.md` for the traceability table.
- `scripts/demo.sh` reproduces each capability against a running instance.

## Limitations

- Agents other than requirement analysis simulate engineering work with structured outputs; they do not edit source files or run a real build. The brownfield impact analysis uses a static index of this repository.
- The approver is identified by name, not by an authenticated principal. See `docs/security.md`.
- Per-run locking is in-process, which is correct for one instance only.
- CI runs on H2 in PostgreSQL mode; PostgreSQL itself is verified via `docker compose`.

## Next steps

1. Spring Security with OAuth2, approver from the token, separation of duties.
2. Real code-change agents: work in a git worktree, run `./mvnw verify` as the test agent, open a pull request as the release step.
3. LLM-proposed plans, validated by `TaskGraph` and policy before execution.
4. Database leases for multi-instance runners; Testcontainers PostgreSQL in CI.
5. Micrometer metrics and OpenTelemetry traces per task.
