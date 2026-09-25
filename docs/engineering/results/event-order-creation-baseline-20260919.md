# Event order creation baseline

- UTC time: 2026-09-18T16:49:25Z
- Git: `fae89ef9cdb17e51dcbc0e8117d2b498d6384e1a` on `main`
- Machine: Linux 6.18.33.2-microsoft-standard-WSL2 x86_64 GNU/Linux; CPU=Intel(R) Core(TM) Ultra 9 275HX; logicalCPUs=24; memory=15Gi
- Infrastructure: mysql  Ver 8.4.11 for Linux on x86_64 (MySQL Community Server - GPL); Redis server v=7.4.11 sha=00000000:0 malloc=jemalloc-5.3.0 bits=64 build=fbf7a7d03c2e9219; Docker client=29.7.2 server=29.7.2; Compose 5.5.0
- Running Compose services: mysql,redis (expected: mysql,redis)
- Fixture: 200 isolated authenticated users, one published Event/TicketTier, capacity 100
- Load model: 200 total POST /api/v1/orders requests, 32 bounded concurrent workers, wall time 5s
- Results: success=100; businessConflicts=100; technicalFailures=0; droppedAttempts=0
- HTTP latency seconds (all order requests): p50=0.012996; p95=0.040840; p99=0.048521
- Database: capacity=100; available=0; reserved=100; allocated=0; orders=100; pendingOrders=100; buyers=100; reservations=100; reservedReservations=100
- Checks: oversold=0; inventoryConserved=1; inventoryNonnegative=1; reservationOrderMatch=1; noDuplicateEffectiveOrder=1; noDuplicateReservation=1; exactExpectedResult=1
- Legacy-path isolation: before=34092	34092	34092; after=34092	34092	34092; unchanged=1

This measures authenticated Event order creation only. It is a bounded local baseline, not a capacity limit, payment benchmark, production SLA, or soak test.
