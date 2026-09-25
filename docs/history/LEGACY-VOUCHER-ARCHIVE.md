# Voucher experiment archive

The current application implements Event orders through a synchronous MySQL transaction. RabbitMQ handles Event notification side effects. The older Voucher experiment used a different business model and is no longer part of the application build.

The complete Voucher source, `legacy-experiment` profile, tests, and bootstrap SQL remain in repository history at commit `103d1da`. Reproduce that experiment from an isolated checkout of that commit, using its original Compose setup and `loadtest/order-capacity.js`. Do not point those historical scripts at a current Event database. The [1,500 RPS experiment record](../verification/CONCURRENCY-EXPERIMENT-1500-RPS-2026-09-18.md) states the workload, failed cold-start attempt, results, and limits. Those numbers do not describe Event throughput.

Flyway V10 renames the six Voucher tables to `archive_legacy_*`, preserving rows. It leaves `tb_user`, all `et_*` tables, and Flyway V1–V9 intact. Historical scripts should be run only against an isolated database; some include destructive bootstrap statements. Event notification retains its AMQP dependency and RabbitMQ service.

The [Event notification record](../verification/CHECKLIST-8-EVENT-NOTIFICATIONS.md), [recovery record](../verification/CHECKLIST-9-MQ-RECOVERY.md), and [Event SQL and load evidence](../verification/CHECKLIST-11-SQL-LOAD-DEPENDENCIES.md) show which engineering mechanisms continued in the product. The Voucher consumer's asynchronous order creation and stock buckets were not transferred into Event ordering.
