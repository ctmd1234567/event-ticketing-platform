# Testing and CI

## Suites

The default Maven suite exercises unit and Spring web/security behavior, including catalog validation, order idempotency, payment/refund outcomes, notification authorization, and simulated gateway contracts. Integration tests use isolated Testcontainers MySQL 8.4 and RabbitMQ 4.1. `EventOrderLifecycleIT` checks expiry and reservation conservation; `PaymentBoundaryIT` and `EventPaymentServiceIT` cover payment/close races, callbacks, response loss, UNKNOWN recovery, compensation and refunds; `EventNotificationIT` covers broker failure, duplicate delivery, poison messages and redrive. Concurrency tests use latches, JDBC lock hooks, and persisted business invariants instead of timing guesses.

```bash
mvn test
mvn -Pinfrastructure verify
```

The [CI workflow](../../.github/workflows/ci.yml) runs the default and infrastructure commands in separate jobs. [Report gate](../../scripts/check-test-reports.py) checks required XML suites and failure counts. Docker must be available for Testcontainers. The local profile and demonstration use development credentials and must remain local.

## Recorded local result

On 2026-09-25 this repository state was checked with Windows Java 21.0.7 and Maven 3.9.16: `mvn -Pinfrastructure verify` exited 0 with 65 Surefire and 83 Failsafe tests. Together the infrastructure run reports **148 tests, zero failures, zero errors, zero skipped**. The [local Maven report summary](results/local-maven-reports-20260925.txt) retains suite details. Hosted CI is a separate run and is not claimed here.

The [order baseline](results/event-order-creation-baseline-20260919.md) additionally audited 100 orders and 100 reservations from 200 authenticated attempts against capacity 100, with no oversell or technical failures. That bounded run covers order creation only.
