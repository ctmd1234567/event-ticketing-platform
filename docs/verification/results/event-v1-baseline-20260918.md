# Event trading HTTP baseline

- UTC time: 2026-09-18T15:38:34Z
- Git: `612596aad8258b04109f5eb4715e8240e7cb1be3` on `main`; uncommitted paths: 12
- Machine: Linux 6.18.33.2-microsoft-standard-WSL2 x86_64 GNU/Linux; CPU=Intel(R) Core(TM) Ultra 9 275HX; logicalCPUs=24; memory=15Gi
- Versions: Java openjdk version "21.0.7" 2025-04-15 LTS; mysql  Ver 8.4.11 for Linux on x86_64 (MySQL Community Server - GPL); Redis server v=7.4.11 sha=00000000:0 malloc=jemalloc-5.3.0 bits=64 build=fbf7a7d03c2e9219; Docker client=29.7.2 server=29.7.2; Compose 5.5.0; k6 k6.exe v2.2.0 (commit/00a9a1b7f5, go1.26.5, windows/amd64) (version recorded; bounded-worker driver is curl)
- Running Compose services: mysql,redis (expected: mysql,redis; RabbitMQ is not part of this run)
- Data preparation: scoped reseed of Event/session/tier IDs 9900070001-9900070003 plus 120 isolated Redis sessions; no database, Redis, queue, or volume reset
- Workload: 120 complete-trade attempts, 12 bounded concurrent workers, one isolated tier with capacity 200, wall time 9s
- Path measured: authenticated HTTP order creation -> simulated payment -> owned order query
- HTTP requests: 360; order requests accepted: 120; payment responses succeeded: 120
- Final correct writes (order PAID): 120; business rejection: 0; technical failures: 0; dropped iterations: 0
- Mean HTTP seconds: order=0.018684, payment=0.035716, final-query=0.007626
- Database: capacity=200, available=80, reserved=0, allocated=120, orders=120, paid=120, payments=120, succeededPayments=120, providerChargeEffects=120, refunds=0
- Checks: inventoryConserved=1, inventoryNonnegative=1, reservationOrderMatch=1, noDuplicateIdempotencyResult=1, exactExpectedResult=1
- Legacy-path isolation: before=34092	34092	34092; after=34092	34092	34092; unchanged=1

## Worktree at start

```text
## main...origin/main
 M README.en.md
 M README.md
 M REFACTORING-CHECKLIST.md
 M postman/environments/Local.environment.yaml
?? docs/verification/CHECKLIST-0-7.md
?? docs/verification/results/event-v1-baseline-20260918.md
?? loadtest/audit-event-trading-baseline.sql
?? loadtest/event-trading-baseline.sh
?? loadtest/seed-event-trading-baseline-tokens.lua
?? loadtest/seed-event-trading-baseline.sql
?? postman/collections/Event-V1.postman_collection.json
?? scripts/demo-v1.sh
```

## Worktree at end

```text
## main...origin/main
 M README.en.md
 M README.md
 M REFACTORING-CHECKLIST.md
 M postman/environments/Local.environment.yaml
?? docs/verification/CHECKLIST-0-7.md
?? docs/verification/results/event-v1-baseline-20260918.md
?? loadtest/audit-event-trading-baseline.sql
?? loadtest/event-trading-baseline.sh
?? loadtest/seed-event-trading-baseline-tokens.lua
?? loadtest/seed-event-trading-baseline.sql
?? postman/collections/Event-V1.postman_collection.json
?? scripts/demo-v1.sh
```

HTTP success means the command returned 200 with the expected response payload. Final business success additionally requires a PAID order and the database checks above. This is a moderate local baseline, not a capacity limit, SLA, soak test, or real-funds benchmark.
