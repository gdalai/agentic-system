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

## Orchestration core (in progress)

`orchestration/` holds the framework-free engine core:

- `TaskGraph` validates the DAG at construction (Kahn's algorithm rejects cycles and unknown dependencies), returns ready tasks, and computes downstream impact for replanning.
- `RunStateMachine` is the single source of truth for legal task status transitions.

Next steps are the workflow engine, agents, governance, audit, AI adapter and scenarios.

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
