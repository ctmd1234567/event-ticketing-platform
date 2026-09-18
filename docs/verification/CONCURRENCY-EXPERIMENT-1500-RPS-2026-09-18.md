# Concurrency Experiment 1500 RPS Reverification

Date: 2026-09-18

Candidate: `7b97c0f` plus the uncommitted stages D/E/F cleanup diff

Result: the warmed 1500 target-RPS run passed every predefined strict threshold.

## Boundary

This measurement covers the optional `legacy-experiment` Voucher ingress and
asynchronous completion path:

```text
authenticated POST /voucher-order/seckill/{voucherId}
  -> Redis admission control
  -> MySQL request, bucketed inventory and Outbox
  -> RabbitMQ
  -> idempotent final-order consumer
```

It does not measure Event order creation, payment, compensation, a production
deployment, real funds, multi-instance scaling or sustained/soak capacity. The
test writes only isolated synthetic voucher IDs and synthetic sessions to the
local development MySQL/Redis/RabbitMQ stack.

## Environment and configuration

- Windows 11 host; Docker Desktop 29.7.2 on WSL2 Linux.
- Docker allocation visible to the engine: 24 CPUs and 16,466,493,440 bytes of memory.
- Application: current packaged JAR, compiled by Microsoft OpenJDK 21.0.7 and
  run in `eclipse-temurin:21-jre` (Java 21.0.12).
- Dependencies: MySQL 8.4, Redis 7.4 and RabbitMQ 4.1.
- Load generator: Windows k6 2.2.0.
- One application instance, one isolated voucher, 16 MySQL stock buckets.
- Hikari maximum/minimum: 32/16; fair in-flight permits: 24; admission wait: 100 ms.
- Experiment-only voucher/global rate limits: 5000/s and 5000/s.
- Outbox: enabled, 50 ms interval, batch size 500; Rabbit consumer active.
- Workload: 1500 scheduled iterations/s for 10 seconds, distinct authenticated
  synthetic users, `preAllocatedVUs=1500`, `maxVUs=7500`.
- Strict thresholds fixed before execution: HTTP P95 < 1 second and zero HTTP
  failures, controlled 429s, sold-out responses, unexpected responses or
  dropped iterations.

The Windows Java process could compile/package the candidate but could not start
Tomcat because the current Windows network stack returned
`Unable to establish loopback connection`. The same JAR was therefore run in
the Linux Java 21 container. This environment difference is part of the result.

## Results

### Cold run

The first run immediately after container startup failed the strict criteria:

- scheduled target: 15,000; requests executed: 14,923;
- accepted: 14,517; controlled HTTP 429: 406;
- dropped iterations: 78; unexpected and sold-out responses: 0;
- HTTP P95: 1068.02 ms; HTTP failures: 406/14,923;
- after drain: 14,517 request rows, 14,517 unique final orders, zero duplicate
  users, 14,517 completed Outbox rows, and 1,583 bucketed stock remaining.

This run is retained as cold-start evidence and is not used for the passing
headline.

### Warm run

After the cold run fully drained, the same isolated fixture and rate-limit keys
were reset, while the JVM, connection pools and consumers remained warm.

- requests and iterations: 15,001, measured request rate 1497.61/s;
- accepted: 15,001; controlled 429, sold-out and unexpected responses: 0;
- HTTP failures: 0; dropped iterations: 0;
- HTTP latency: average 32.14 ms, median 17.96 ms, P90 61.23 ms,
  P95 106.51 ms, maximum 288.44 ms;
- final database state: 15,001 request rows in `COMPLETED`, 15,001 final
  orders, 15,001 distinct users, zero duplicate users;
- inventory: 16 buckets, 1,099 remaining from 16,100;
- Outbox: 15,001 completed, zero pending, zero recorded errors;
- Rabbit queues `event-trading.orders.v1` and
  `event-trading.orders.v1.failed`: zero ready and zero unacknowledged;
- public Event endpoint remained HTTP 200 after the run.

The warm run therefore passed every predefined strict threshold. This is a
reproduction at one specified workload, not a new upper-bound search.

## Raw evidence

- [Cold k6 summary](results/concurrency-experiment-1500-rps-cold-20260918.json),
  SHA-256 `f17ef686b811e97ec6bc764329cd7e56972dc207ce7648f29edb8a635d004a71`.
- [Warm k6 summary](results/concurrency-experiment-1500-rps-warm-20260918.json),
  SHA-256 `b1989044dee27235478e1dd7a6120957676511f20ac606e8f276c1c175241ce3`.

The k6 JSON `thresholds` booleans use k6 summary-export semantics; the metric
values and the process exit status were evaluated against the declared
threshold expressions above.

## Limitations

No CPU, memory, GC or database wait profile was captured during the 10-second
window. No 10–30 minute soak, fault injection, separate load-generator host,
multi-instance application, Event payment path or production durability/network
configuration was tested. These results must not be generalized beyond this
local, warmed, single-instance engineering experiment.
