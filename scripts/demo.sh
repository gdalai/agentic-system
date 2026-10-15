#!/usr/bin/env bash
# Walks through every capability of the orchestration engine against a running instance.
#
#   ./mvnw spring-boot:run          (or: docker compose up --build)
#   ./scripts/demo.sh               (needs curl and jq; BASE overrides the URL)
#
# Each step prints what it is demonstrating, then the evidence from the API.
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
API="$BASE/api/v1"
JSON='Content-Type: application/json'

command -v jq >/dev/null || { echo "jq is required (brew install jq / apt-get install jq)"; exit 1; }
curl -sf "$BASE/actuator/health" >/dev/null || { echo "Application not reachable at $BASE"; exit 1; }

say()  { printf '\n\033[1;34m== %s\033[0m\n' "$*"; }
note() { printf '\033[2m   %s\033[0m\n' "$*"; }

start() { # scenario autonomy approvals-json requirement
  curl -sf -X POST "$API/workflows" -H "$JSON" -d "$(jq -n \
    --arg s "$1" --arg a "$2" --argjson ap "$3" --arg r "$4" \
    '{scenario:$s, autonomyLevel:$a, approvalRequiredFor:$ap, requirement:$r}')" | jq -r .runId
}

wait_for() { # runId expected-status
  for _ in $(seq 1 200); do
    status=$(curl -sf "$API/workflows/$1" | jq -r .status)
    [[ "$status" == "$2" ]] && return 0
    [[ "$status" =~ ^(SUCCEEDED|FAILED|CANCELLED|AWAITING_APPROVAL)$ ]] && { echo "   run settled in $status (expected $2)"; return 1; }
    sleep 0.1
  done
  echo "   timed out waiting for $2"; return 1
}

tasks()  { curl -sf "$API/workflows/$1/tasks" | jq -r '.[] | "   " + ((.taskId + "                ")[0:16]) + ((.status + "                   ")[0:19]) + "attempts=\(.attempts)"'; }
events() { curl -sf "$API/workflows/$1/events" | jq -r '.[] | "   #\(.sequence) \(.eventType) \(.taskId // "") \(.fromStatus // "")\(if .toStatus then "->"+.toStatus else "" end)  \(.detail // "" | .[0:110])"'; }
decide() { # runId taskId decision approver
  curl -s -o /dev/null -w '%{http_code}' -X POST "$API/workflows/$1/approvals" -H "$JSON" \
    -d "$(jq -n --arg t "$2" --arg d "$3" --arg a "$4" '{taskId:$t, decision:$d, approver:$a, reason:"demo"}')"
}

REQ="Build a URL shortener with expiration and click analytics."

say "1. Greenfield, autonomous: plan -> parallel agents -> validated summary"
RUN=$(start GREENFIELD AUTONOMOUS '[]' "$REQ"); wait_for "$RUN" SUCCEEDED
tasks "$RUN"
note "implementation and security run in parallel; look for overlapping RUNNING events:"
events "$RUN" | grep -E 'implementation|security' | grep -E 'RUNNING|SUCCEEDED' || true
curl -sf "$API/workflows/$RUN/tasks" | jq -r '.[] | select(.taskId=="documentation") | "   summary: " + .outputs.engineeringSummary'

say "2. Governance: CODE_MERGE needs approval; unauthorized approver is refused"
RUN=$(start GREENFIELD CONTROLLED '["CODE_MERGE"]' "$REQ"); wait_for "$RUN" AWAITING_APPROVAL
tasks "$RUN"
note "approve as 'intern' -> HTTP $(decide "$RUN" implementation APPROVE intern)"
note "approve as 'tech-lead' -> HTTP $(decide "$RUN" implementation APPROVE tech-lead)"
wait_for "$RUN" SUCCEEDED && note "run SUCCEEDED after approval"

say "3. Resilience: security agent fails twice -> retried with backoff -> recovers"
curl -sf -X POST "$API/demo/faults" -H "$JSON" -d '{"agentType":"SECURITY","times":2}' >/dev/null \
  || { echo "   demo fault endpoint disabled (set ORCHESTRATION_DEMO_FAULT_INJECTION_ENABLED=true)"; exit 1; }
RUN=$(start GREENFIELD AUTONOMOUS '[]' "$REQ"); wait_for "$RUN" SUCCEEDED
events "$RUN" | grep security | grep -E 'RETRYING|RUNNING|SUCCEEDED' || true

say "4. Resilience: retries exhausted -> run FAILED, staged work ROLLED_BACK, downstream BLOCKED"
curl -sf -X POST "$API/demo/faults" -H "$JSON" -d '{"agentType":"SECURITY","times":10}' >/dev/null
RUN=$(start GREENFIELD AUTONOMOUS '[]' "$REQ"); wait_for "$RUN" FAILED
tasks "$RUN"
curl -sf -X DELETE "$API/demo/faults" >/dev/null

say "5. Ambiguous requirement: decision gate even in AUTONOMOUS mode"
RUN=$(start AMBIGUOUS AUTONOMOUS '[]' "Build a fast, scalable URL shortener with analytics etc."); wait_for "$RUN" AWAITING_APPROVAL
curl -sf "$API/workflows/$RUN/tasks" | jq -r '.[] | select(.taskId=="requirements") | "   ambiguities: " + .outputs.ambiguities, "   assumptions: " + .outputs.assumptions'
tasks "$RUN"
note "clarify instead of accepting assumptions -> replan to requirement v2"
curl -sf -X POST "$API/workflows/$RUN/replan" -H "$JSON" -d '{
  "requirement":"Build a URL shortener; links expire after 30 days; redirects respond within 50 ms; analytics count unique visitors per day.",
  "reason":"Product owner clarified"}' | jq -r '"   requirementVersion=\(.requirementVersion) status=\(.status)"'
wait_for "$RUN" SUCCEEDED
curl -sf "$API/workflows/$RUN/tasks" | jq -r '.[] | select(.taskId=="requirements") | "   v\(.outputs.requirementVersion): ambiguityCount=\(.outputs.ambiguityCount), criteria=\(.outputs.criteriaCount)"'
events "$RUN" | grep -E 'RUN_|APPROVAL' || true

say "6. Brownfield defect: impact -> failing reproduction test -> bounded fix -> regression"
RUN=$(start BROWNFIELD AUTONOMOUS '[]' "Fix defect: expired short links still redirect at the exact expiry time"); wait_for "$RUN" SUCCEEDED
curl -sf "$API/workflows/$RUN/tasks" | jq -r '.[] |
  if .taskId=="impact" then "   impacted files: " + .outputs.impactedFiles
  elif .taskId=="reproduce" then "   reproduction:   " + .outputs.failingTest + " (" + .outputs.resultBeforeFix + " before fix)"
  elif .taskId=="implementation" then "   fix:            " + .outputs.scope + ", " + .outputs.filesChanged + " files"
  elif .taskId=="test" then "   after fix:      " + .outputs.testsPassed + " passed, reproduction passing=" + .outputs.reproductionTestPassing
  else empty end'

say "7. Reliability metrics"
curl -sf "$API/metrics" | jq .

say "Done. Swagger UI: $BASE/swagger-ui.html"
