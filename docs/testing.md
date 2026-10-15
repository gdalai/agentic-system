# Testing

```bash
./mvnw test     # all unit and integration tests; mock AI provider, in-memory H2, no Docker or credentials
./mvnw verify   # what CI runs
```

CI (`.github/workflows/ci.yml`) runs `./mvnw verify` on every push and pull request, uploads the Surefire reports, then builds the container image.

## Strategy

The engine core (`TaskGraph`, `RunStateMachine`, `GraphRunner`, `PolicyEngine`, `RetryPolicy`) has no Spring dependencies, so most behaviour is tested with fast, plain unit tests. A smaller set of end-to-end tests drives the real application context through HTTP to prove the wiring: async execution, persistence, Flyway migrations, audit and error mapping.

All tests are deterministic: the AI provider is the mock, failures are injected with `FaultInjector`, and time-dependent shortener logic uses an injected `Clock`.

## Test suites

| Suite | Type | What it proves |
|---|---|---|
| `TaskGraphTest` | Unit | Topological ordering, diamond dependencies, cycle / unknown / duplicate / self-dependency rejection, downstream impact, attempts reset on replan |
| `RunStateMachineTest` | Unit, parameterized | Every legal transition allowed, illegal ones rejected, no self-transitions, approval gates cannot be skipped |
| `GraphRunnerTest` | Unit, concurrent | Dependency order; **independent tasks overlap in time** (latch-based proof); parallelism cap honoured; upstream outputs visible; retry then success; bounded retries then failure with rollback; non-retryable errors fail once; agent exceptions contained; approval pause, resume after approval, cancellation after rejection; failures cancel open approvals |
| `WorkflowPlannerTest` | Unit | Each scenario's plan is valid; brownfield reproduces before fixing; parallel branches in greenfield |
| `PolicyEngineTest` | Unit | MANUAL / CONTROLLED / AUTONOMOUS gates; ambiguity decision gate rules per scenario; approver authorization |
| `RetryPolicyTest` | Unit | Retry bounds, non-retryable codes, exponential backoff, invalid configuration |
| `AgentsTest` | Unit | Registry fail-fast; deterministic agents; requirement analysis via mock; provider outage -> `LLM_UNAVAILABLE`, unusable output -> `VALIDATION_FAILED`; brownfield impact, reproduction and bounded fix; unknown defect |
| `RequirementAnalysisTest` | Unit | Tolerant parsing of model replies, rejection without criteria, mock determinism and ambiguity detection, Claude response text extraction |
| `JsonCodecTest` | Unit | Round-trips special characters, stable key order, malformed input rejected |
| `CodeGeneratorTest`, `UrlServiceTest`, `UrlControllerTest` | Unit / web slice | Shortener: Base62 codes with bounded collision retry, expiry boundary, click recording, 201 / 302 / 400 / 404 / 410 |
| `WorkflowApiIntegrationTest` | End-to-end (`@SpringBootTest`) | Full runs through HTTP for all three scenarios; audit trail order and counts; retry recovery; retry exhaustion with rollback and blocked tasks; approval pause and approve; reject -> cancel -> replan -> approve -> succeed; unauthorized approver 403; out-of-gate approval 409; demo fault endpoint; validation 400; unknown run 404; metrics |
| `AgenticSystemApplicationTests` | Smoke | Context loads with Flyway migrations validated by Hibernate |

## Requirement-to-test traceability

| Assessment requirement | Evidence |
|---|---|
| Dependency graph | `TaskGraphTest`, `WorkflowPlannerTest` |
| Persistent state | `WorkflowApiIntegrationTest` (state read back through the API mid-run and after resume) |
| Parallel execution | `GraphRunnerTest.independentTasksRunInParallel`, `parallelismIsCappedAtMaxParallel` |
| Approvals | `GraphRunnerTest` approval tests, `WorkflowApiIntegrationTest.approvalGatePauses...`, `unauthorizedApproverIsRejected` |
| Retries | `GraphRunnerTest.transientFailureIsRetriedThenSucceeds`, `WorkflowApiIntegrationTest.transientFailureIsRetried...` |
| Rollback | `GraphRunnerTest.retriesAreBoundedAndThenTheRunFailsWithRollback`, `WorkflowApiIntegrationTest.exhaustedRetries...` |
| Dynamic replanning | `WorkflowApiIntegrationTest.rejectedApproval...Replan...`, `ambiguousRequirement...Replans` |
| Greenfield / brownfield / ambiguous scenarios | `WorkflowApiIntegrationTest` scenario tests |
| Audit and metrics | Event assertions in `WorkflowApiIntegrationTest`, `metricsReflectCompletedRuns` |

## Manual and exploratory

- `scripts/demo.sh` exercises every capability against a running instance and prints the evidence.
- Swagger UI at `/swagger-ui.html` for ad hoc calls.
- With `AI_PROVIDER=anthropic`, the same flows run against Claude; ambiguity detection then reflects the model's judgement rather than the mock's rules.

## Not covered (and why)

- **Real model behaviour** is not asserted in CI (non-deterministic and needs credentials); only the adapter's request and response handling is unit-tested.
- **PostgreSQL** is exercised manually via `docker compose`; CI uses H2 in PostgreSQL mode. Adding Testcontainers is the next step for CI parity.
- **Load and soak testing** are out of scope for the assessment.
