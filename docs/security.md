# Security

## Principles

1. **Models recommend, the engine decides.** Model output can only populate task outputs. It cannot change graph structure, skip gates, grant approvals or alter retry and rollback behaviour.
2. **Least autonomy by default.** `CONTROLLED` is the default autonomy level. The `AMBIGUOUS` scenario always stops for a human when requirements are unclear, regardless of autonomy.
3. **Everything is audited.** Every transition, decision and agent result is written to an append-only `run_event` table with a reason.

## Controls in place

| Area | Control |
|---|---|
| Input validation | Jakarta Bean Validation on every request body; malformed requests return 400 problem details. Short-link targets must be `http(s)` URLs (prevents `javascript:` and other scheme abuse in redirects). |
| Approval authority | Only approvers listed in `orchestration.approvers` may decide (403 otherwise). Decisions are accepted only when the run and task are actually waiting (409 otherwise), so stale or replayed approvals cannot resume a run. |
| Approval integrity | Approved tasks move `AWAITING_APPROVAL -> READY` through the state machine; a gate cannot be skipped (`AWAITING_APPROVAL -> RUNNING` is illegal and tested). |
| Bounded change | Brownfield fixes are limited to 3 files; larger changes fail validation and are not retried. |
| Rollback | Staged changes (`staging/` artifacts) from succeeded tasks are discarded when a run fails or is cancelled. |
| Model output | Parsed into a fixed structure; replies without acceptance criteria are rejected (`VALIDATION_FAILED`, not retried). Model text is never executed. |
| Secrets | `ANTHROPIC_API_KEY` comes from the environment only; `.env` is git-ignored and `.env.example` holds no values. Startup fails fast if the provider is `anthropic` without a key. No secrets are logged. |
| Demo endpoint | `POST /api/v1/demo/faults` is disabled by default (404) and enabled only in the `local` profile and the compose demo stack. |
| Concurrency | Only the runner thread mutates a run's graph; approvals and replans take a per-run lock and are refused while a runner is active. |
| Container | Multi-stage build; runtime image is JRE-only and runs as a non-root user (uid 10001). |
| Errors | RFC 9457 problem details; no stack traces in responses. |

## Known gaps (would be addressed before production)

| Gap | Production approach |
|---|---|
| API has no authentication; the approver is a name in the request body. | OAuth2 resource server (Spring Security); approver taken from the authenticated principal and checked against a role such as `ROLE_RELEASE_APPROVER`. |
| Separation of duties is not enforced (the requester could also approve). | Record the requester identity on the run and refuse approvals by the same principal. |
| H2 console is enabled in the `local` profile. | Already off in the `postgres` profile; keep it local-only. |
| Prompt injection through the requirement text. | Mitigated by design (model output is data, not instructions, and is structurally validated); add input length limits per provider and content screening if requirements come from untrusted users. |
| Per-run locks are in-process. | Database row lock or lease for multi-instance deployments. |
| No rate limiting on the redirect or workflow endpoints. | Gateway or Bucket4j rate limits. |
| Dependency and image scanning are not in CI. | Add OWASP dependency-check or GitHub Dependabot, and Trivy on the built image. |
