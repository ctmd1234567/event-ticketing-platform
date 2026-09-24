# 第 11 项：Event SQL、负载和依赖故障验收

状态：2026-09-25 本机有界验收已执行。以下结论只适用于记录的环境、数据和短负载窗口，不是容量上限或生产 SLA。

## 范围与数据保护

- 只测默认 Event `POST /api/v1/orders`，不启用 `legacy-experiment`，不运行 Voucher k6 脚本。核心交易仍由 `EventOrderService` 同步提交 MySQL。
- 使用 `loadtest/seed-event-v2.sql` 为每个场景建立新的 Event、场次和三个票档。脚本不删除现有数据；每轮使用不同 `@scenario_base`，并记录它。三档初始容量相同。
- 使用 `loadtest/seed-event-trading-baseline-tokens.lua` 建立最多 1000 个隔离测试会话。每个请求使用不同用户和幂等键；同一场景下业务拒绝应来自售罄或过载，不应被重复购买规则污染。
- 停止应用前先检查场景的订单、库存、支付与通知状态；不清空 MySQL、Redis、队列或数据卷。`loadtest/audit-event-v2.sql` 只读核对每档库存守恒、预占对应和购买唯一性。V10 后旧实验计数读取 `archive_legacy_*` 表；本次六轮在 V10 前测量，原始审计文件中的标签和表名为旧名。

## 固定测量入口

在 Docker 可用且应用已启动的本机终端，确认 `/actuator/health` 为 `UP`，确认默认 profile 和依赖版本。对每个场景，向 MySQL 执行 `SET @scenario_base=<唯一整数>; SET @capacity=300;` 后接 `loadtest/seed-event-v2.sql`。以相同参数运行审计 SQL。先将现有 Lua 文件作为 `EVAL <script> 0 1000` 传给该项目的 Redis，确认返回 1000；k6 的到达率执行器在时间边界可能比名义速率多启动少量迭代，不能只按 `rate × seconds` 建会话。每场景 `LABEL` 唯一，原始 summary 留在 `docs/verification/results/`。

```bash
# Windows k6 从 Windows 127.0.0.1 访问 IDEA 启动的应用。
K6_BIN='/mnt/c/Program Files/k6/k6.exe'
K6_SCRIPT_WIN="$(wslpath -w "$PWD/loadtest/event-v2-k6.js")"
RESULT_WIN="$(wslpath -w "$PWD/docs/verification/results/hot-normal.json")"
"$K6_BIN" run -e BASE_URL=http://127.0.0.1:8081 -e TIER_IDS=BASE_PLUS_3 \
  -e LABEL=hot-normal -e RATE=20 -e DURATION_SECONDS=5 \
  --summary-export "$RESULT_WIN" "$K6_SCRIPT_WIN"
```

其余五轮复用同一命令，每轮更新 `TIER_IDS`、`LABEL`、summary 文件和以下参数：单热点 Stress 用 `RATE=80,DURATION_SECONDS=5`；单热点 Spike 用 `MODE=spike,RATE=20,SPIKE_RATE=160`；多票档 Normal 用三个票档 ID 和 `RATE=30,DURATION_SECONDS=5`；多票档 Stress 用三个 ID 和 `RATE=120,DURATION_SECONDS=5`；多票档 Spike 用三个 ID 和 `MODE=spike,RATE=30,SPIKE_RATE=200`。Spike 三个阶段各两秒，按 k6 的 ramping arrival rate 从起始速率升至目标、保持、再降回起始速率。每轮 `ALLOCATED_VUS` 默认 64，记录 `dropped_iterations`，不把丢弃误算成 HTTP 失败。`BASE_PLUS_*` 是说明占位符，运行前替换为本轮实际票档 ID。

脚本把 HTTP 200 且有 `PENDING_PAYMENT` 订单记为 `event_orders_accepted`，409/429 记为业务拒绝，503 记为依赖不可用，其余记为技术失败。内置 `http_req_failed` 会把预期的 409 计入失败，不能直接当成技术失败。每轮通过 `audit-event-v2.sql` 比对 HTTP 受理数和持久化订单、预占及三档库存，不能只读 HTTP 总量。k6 当前这条 Windows 路径已经用独立 3 请求夹具证明真实写入和库存守恒；第一次 2 用户探针因 k6 额外启动第 3 次迭代，产生 1 个未认证技术失败，已保留在 `target/`，不纳入六轮结果。

## SQL 与锁观察

在已填充的隔离库运行 `loadtest/observe-event-v2.sql`，保留 MySQL 原始输出、行数和版本。它检查幂等读取和过期扫描的 `EXPLAIN ANALYZE`、库存主键加锁查询的 `EXPLAIN`，以及 InnoDB 行锁等待状态。对热点和多票档场景分别在负载前后采样 `Innodb_row_lock%` 与 `performance_schema.data_lock_waits`；记录采样窗口、吞吐、事务和 HTTP 延迟、更新影响行数及死锁。`EXPLAIN` 不等于并发锁实验，只有实测锁等待成为主要限制时才考虑 Event 库存分桶。

## 故障与恢复

- Redis 保存登录会话及验证码/登录限流；它不是库存事实源。真实 Redis 停机期间，带测试令牌的请求应返回 503，不应绕过认证或产生新订单；恢复后重新请求，再核对数据库终态。记录停止和恢复时刻、请求状态以及 Redis/应用日志。
- 通过隔离 Testcontainers 的 Event 生命周期、支付边界和通知测试，核对重启后过期订单、支付 `UNKNOWN`、发布租约的持久恢复；进程级重启若未实际执行，单独注明，不能把故障注入测试写成真实进程演练。
- `/actuator/prometheus` 的 JVM、Hikari、HTTP 指标与 `event_orders_persisted`、`event_notifications_outbox_pending`、`event_notifications_outbox_oldest_seconds` 一起采样。业务 ID 只进日志，不作指标标签；`X-Request-Id` 与日志 MDC 对应。新的 Event gauge 为 `-1` 表示上次数据库刷新失败，不能当成真实负积压。
- RabbitMQ 队列长度及 DLQ 状态以 Broker 管理端观测；Outbox `PUBLISHED` 只证明发布确认，通知最终效果以 `et_notification` 为准。

## 2026-09-25 实测与判定

- 基准代码为本地 `855550e`，运行包含未提交的 `loadtest/event-v2-k6.js`；Java 21.0.7、Spring Boot 3.5.16、MySQL 8.4、Redis 7.4、RabbitMQ 4.1、Docker Desktop Engine 29.7.2、k6 2.2.0。Windows IDEA 2026.1.3 启动应用，容器运行依赖；Tomcat 最多 300 线程、Hikari 最多 32 连接、Redis pool 最多 30。Docker 向 Testcontainers 报告总内存 15703 MB；未采集独立 CPU 利用率曲线。
- 每轮新建 1 个 Event、1 个场次、3 个票档，每档容量 300，1000 个隔离会话可用。单热点只打第 1 档；多票档轮流打 3 档。Normal/Stress 为 5 秒固定到达率，Spike 为 6 秒的升高、保持、下降。原始 JSON 和每轮 SQL 审计在 [results](results/) 中，文件名 `event-v2-<场景>-20260925.json` 与 `event-v2-<场景>-audit-20260925.txt`。
- 单热点 Normal：base `9900121000`，20/s，100 HTTP、100 写入、0 拒绝、0 技术失败、0 丢弃，p95 16.17 ms。Stress：base `9900122000`，80/s，400 HTTP、300 写入、100 售罄拒绝、0 技术失败、0 丢弃，p95 16.07 ms。Spike：base `9900123000`，20→160→20/s，679 HTTP、300 写入、379 售罄拒绝、0 技术失败、0 丢弃，p95 10.37 ms。
- 多票档 Normal：base `9900124000`，30/s，151 HTTP、151 写入、0 拒绝/技术失败/丢弃，p95 11.81 ms。Stress：base `9900125000`，120/s，601 HTTP、601 写入、0 拒绝/技术失败/丢弃，p95 10.83 ms。Spike：base `9900126000`，30→200→30/s，859 HTTP、859 写入、0 拒绝/技术失败/丢弃，p95 245.20 ms；同配置新夹具 `9900129000` 复测仍为 859 写入、p95 183.15 ms。全部审计显示订单数=预占数、三档库存守恒且非负、同档购买用户唯一；旧实验表计数保持 34092。
- Event 订单在 HTTP 成功前已同步持久化，负载请求无支付或关单，因此这些样本无异步通知完成延迟。`http_req_failed` 把预期的售罄 409 计为失败；技术失败以脚本专门计数为准。k6 `dropped_iterations` 未出现，按 0 记录。负载只持续 5～6 秒，未测长时间稳定性或容量边界。
- [SQL 原始输出](results/event-v2-sql-observe-20260925.txt)：采样时 `et_order` 约 2426 行；幂等读取 `EXPLAIN ANALYZE` 约 1.75 ms；过期扫描使用 `ix_event_order_status_deadline`，该时刻无到期行，实际约 0.014 ms；票档锁定读取按主键定位 1 行。150/s、5 秒等速诊断各成功写入 750 单：单热点全局 InnoDB 行锁等待增 153 次、累计等待时间增 601 ms；多票档增 224 次、969 ms；两轮死锁计数均为 0。原始前后采样分别在 `event-v2-hot-lock-*` 与 `event-v2-multi-lock-*`。计数是实例级、窗口内还可能有后台工作；不能归因到单条 SQL。多票档 Spike 的 p95 升高是真实限制，但单热点票档行锁不是已证明的主瓶颈。
- 为补采事务时间，另做 150/s、5 秒等速轮次，前后读取 `performance_schema.events_transactions_summary_global_by_event_name`、InnoDB 行锁计数及每轮审计；原始文件为 `event-v2-hot-txn-*`、`event-v2-multi-txn-*`、`event-v2-hot-clean-txn-*`。第一次单热点轮次写入 751 单、HTTP p95 426.37 ms；窗口内全局事务数增 1792、总耗时增 61428.49 ms（平均 34.28 ms），行锁等待增 454 次、57228 ms。多票档新夹具 751 单的 p95 66.31 ms，全局事务平均 4.89 ms、行锁等待增 267 次/4088 ms；再次单热点新夹具 751 单的 p95 19.70 ms，全局事务平均 2.63 ms、行锁等待增 94 次/603 ms。三轮均无技术失败或丢弃，且库存审计守恒。高延迟单热点窗口确有较高实例级行锁等待，但前一轮单热点同速 p95 16.76 ms/行锁 601 ms、后一轮 p95 19.70 ms/行锁 603 ms，尚不能证明库存单行锁是稳定的主瓶颈。旧负载订单约 4670 单确已到期关闭，但 `updated_at` 为 16:29～16:39 UTC；异常单热点轮次新单 `created_at` 为 16:58 UTC，**两者不重叠**，不能把异常归因于到期补扫。该轮应用日志显示 DispatcherServlet 在第一批请求进入时初始化，属于冷请求窗口；它可能增加瞬时并发，但现有采样不能定量归因。Performance Schema 计数包含后台事务，不能当成单笔 Event 事务耗时；窗口前后差值只描述当时数据库负载。 这三轮 2253 个测试订单之后由正常过期扫描全部关单，发布确认及站内通知各 2253 条，五档库存恢复且守恒；见 [最终审计](results/event-v2-txn-fixtures-final-20260925.txt)。
- 因全部可售请求写入成功、无技术失败或丢弃，且多票档等速负载的 p95 为 20.30 ms，本轮不添加 Redis 库存预扣、多级限流、公平信号量、DB in-flight 保护或 Event 库存分桶；没有可验证的准入收益可做前后对比。后续若需解决 200/s 突发延迟，应先采同窗口 CPU、Hikari 等待和 SQL 事务耗时，再选保护阈值。旧 Voucher 1500 RPS 不作 Event 阈值。
- [Redis 故障原始输出](results/event-v2-redis-recovery-20260925.txt)：使用 base `9900130000`，停机期间同一测试令牌请求返回 503，正文为认证服务暂不可用；重启 Redis 后返回 200。 [SQL 审计](results/event-v2-redis-recovery-audit-20260925.txt) 只有 1 单/1 预占，容量 10→可用 9/预占 1。第一次探针用 Windows curl 写 WSL `/tmp` 失败，退出保护已重启 Redis；第二次完整演练才作为结论。Redis 仅持有会话与验证码/认证限流，不承载库存事实。
- IDEA JUnit 本次执行：`RequestIdFilterTest` 1/1、`EventOrderServiceTest` 8/8、`PaymentBoundaryIT` 32/32、`EventNotificationIT` 14/14、旧链路退出前的 `EventInfrastructureIT` 3/3；退出后 V10 的 `EventInfrastructureIT` 2/2 和 `RequestLimitsTest` 1/1 均退出码 0。IDEA 结果不冒充 Maven Surefire/Failsafe 报告。
- [实际进程重启记录](results/event-v2-process-restart-20260925.txt)：测试订单 `2103159021258973186` 停机时设为到期，重启后 `CLOSED`，票档 `9900130003` 可用 10、预占 0；V10 应用成功，归档旧订单 34092 行。对同一测试事件注入过期 `PROCESSING` 发布租约，再次重启后 `PUBLISHED`、attempts 1→2，站内通知仍 1 条。另以新测试订单 `2103162041812213762` 和持久网关结果注入 `UNKNOWN/AUTO` 支付 `9900131009`；重启后扫描恢复为 `SUCCEEDED/NONE`、attempts 1→2，订单 `PAID`、库存可用 9/预占 0/已分配 1、通知 1 条，[V10 后 SQL 审计](results/event-v2-unknown-restart-audit-20260925.txt)守恒。后两项为明确 SQL 故障注入加真实 JVM 重启，不宣称由自然崩溃触发。
- [应用指标采样](results/event-v2-metrics-20260925.txt)：Event 成功写入总数、Outbox pending/oldest、Hikari active/pending、JVM 内存、HTTP 200/401/409/503 均可读；[RabbitMQ 队列采样](results/event-v2-rabbit-queues-20260925.txt) 显示 Event 通知主队列及 DLQ 均为 0。Broker 上仍有旧实验的空队列，未清除历史队列或数据。[应用日志样本](results/event-v2-request-correlation-20260925.txt) 同行包含 `requestId` 和创建的 orderId；业务 ID 未加入指标标签。
