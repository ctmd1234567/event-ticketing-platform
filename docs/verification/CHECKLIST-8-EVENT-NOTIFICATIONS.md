# 第 8 项：Event 站内通知 Outbox 与 RabbitMQ

基线：`0cd422e34f3c5a8f67c0d2ab25c0bd566175e597` 上的第 8 项改动，2026-09-23。范围止于私人清单第 8 项；第 9 项 DLQ、故障重驱与停机恢复预算尚未验收。

## 实现边界

- `EventOrderService.close` 真正完成 `PENDING_PAYMENT → CLOSED`、释放库存时，在同一个 MySQL 事务插入 `ORDER_CLOSED:<orderId>`。支付确认的 `allocate` 真正完成 `PENDING_PAYMENT → PAID` 时，在相同事务插入 `ORDER_PAID:<orderId>`。回滚不会留下事件；重复关单、重复支付不会产生第二条事件。
- V7 新增 `et_outbox_event` 和 `et_notification`，保留 V1～V6 原文件。事件类型、订单 ID、用户 ID 与 JSON payload 持久化；`event_id` 与 `(event_type, aggregate_id)` 均唯一。消费者只写站内通知，绝不创建 Event 订单或动库存。第 12 项移除了旧 Voucher Listener 和 Rabbit 拓扑的运行代码，V10 将旧 `tb_outbox_event` 改名归档。
- 默认启用 Event 通知运行时。`EventNotificationPublisher` 在一次扫描中逐条原子领取 `PENDING` 或租约到期的 `PROCESSING`，避免等待前一条 Confirm 时让尚未发送的事件过租约。业务 `event_id` 稳定，每次发送使用新的 Confirm 关联 ID；等待 correlated Confirm，并拒绝 mandatory Return、NACK、超时和未知结果。扫描发送前声明预期的持久 exchange、queue、binding；旧 Broker 上的拓扑参数冲突会失败，不把 ACK 当成预期队列已收到消息。
- 失败按尝试次数退避，最多 8 次，之后停在 `MANUAL_REQUIRED`。领取后进程崩溃由 `lease_until` 回收；Broker 已接收但本地未标记 `PUBLISHED` 时可能重复发布。`et_notification.event_id` 唯一，消费者在通知事务提交后由容器 AUTO ACK。`PUBLISHED` 仅表示发布侧确认，不表示站内通知已写入。
- 登录用户可调用 `GET /api/v1/notifications` 查询自己的最近 100 条通知。单 Broker 使用 Compose `rabbit-data` 持久卷及持久化队列/消息；不承诺集群 HA。

## 运行与人工恢复

在未跟踪的 `.env` 中设置 `RABBITMQ_PASSWORD`，本地 Compose 暴露端口 5673 时设置 `RABBITMQ_PORT=5673`。`docker compose up -d` 现在包含 RabbitMQ；`EVENT_NOTIFICATIONS_ENABLED=false` 可停用发布/消费，交易仍在 MySQL 写入 Outbox 意图。`app.event-notifications.scan-enabled=false` 仅供隔离测试手动触发扫描。

排查 SQL：

```sql
SELECT event_id,event_type,publish_status,attempts,next_attempt_at,lease_until,last_error
FROM et_outbox_event
WHERE publish_status <> 'PUBLISHED'
ORDER BY created_at,event_id;
```

修复 Broker/路由后，针对已确认的事件由受控数据库操作重新激活，保留原 `event_id` 与原 payload；同一事件可能已到达 Broker，因此消费去重始终生效。第 9 项才建立受保护重驱入口和操作记录。

```sql
UPDATE et_outbox_event
SET publish_status='PENDING',attempts=0,next_attempt_at=CURRENT_TIMESTAMP,
    lease_token=NULL,lease_until=NULL,last_error=NULL
WHERE event_id=? AND publish_status='MANUAL_REQUIRED';
```

## 本次验证

使用 Windows IntelliJ IDEA 2026.1.3 / Java 21.0.7，经 IDEA MCP 从真实源码运行点执行。IDEA 日志路径均在 `C:\Users\ctmd12334567\AppData\Local\JetBrains\IntelliJIdea2026.1\tmp\`；这些结果不计入 Maven Surefire/Failsafe 报告。

- `build_project`：成功，0 问题。
- `EventNotificationIT`：初次运行 3 started / 3 finished / 0 failed，退出码 0，日志 `ij_run__EventNotificationIT_17796466510695292259.log`。隔离 MySQL 8.4 + RabbitMQ 4.1 真实容器；验证支付和关单各一次通知、用户隔离、重复消息和重复发布、预期 binding 恢复、注入的拓扑声明异常保留、mandatory Return、确认超时、尝试上限与原事件重新激活、过期租约回收，以及 Outbox 插入失败时关单与库存释放整体回滚。逐条领取与关联 ID 修正后的复验结果见下文。
- `EventOrderServiceTest`：8/8，退出码 0，日志 `ij_run__EventOrderServiceTest_5065852276131670289.log`。
- `EventPaymentServiceTest`：26/26，退出码 0，日志 `ij_run__EventPaymentServiceTest_17812852879122938361.log`。
- `EventInfrastructureIT`：3/3，退出码 0，日志 `ij_run__EventInfrastructureIT_13807975437544267631.log`；包含真实 MySQL 迁移 V1～V7 和 1000 请求/100 库存守恒。

- `EventNotificationControllerTest`：1/1，退出码 0；IDEA 返回的输出包含本次 started/finished 事件但未提供独立 `fullOutputPath`。
- 修正后 IDEA `build_project`：成功，0 问题。`EventNotificationIT`：4 started / 4 finished / 0 failed，退出码 0，日志 `ij_run__EventNotificationIT_9145482636565557116.log`；新增两条事件在同一扫描中分别领取、各自仅尝试一次且 Confirm 关联 ID 不重复。`SecurityRegressionTest`：4 started / 4 finished / 0 failed，退出码 0；新增 MockMvc 真实安全过滤链验证未认证请求为 401，登录用户请求只按其身份查询。此项不是外部 HTTP 服务实测。
- `EventOrderLifecycleIT`：2/2，退出码 0，日志 `ij_run__EventOrderLifecycleIT_11193504808552663799.log`。
- `PaymentBoundaryIT`：26/26，退出码 0，日志 `ij_run__PaymentBoundaryIT_6441945197735359343.log`。
- `LegacyMessagingIT`：1/1，退出码 0，日志 `ij_run__LegacyMessagingIT_15262558198727410699.log`，旧 Voucher 实验仍在独立 profile 运行。
- `docker compose config --services`：退出码 0，列出 `mysql`、`rabbitmq`、`redis`；未启动或更改现有 Compose 数据卷。

## 限制与下一项入口

本项没有执行完整的 Broker 停机 100 事件恢复预算，也未注入消费者提交前/提交后 ACK 前进程崩溃。消费者失败后的 DLQ、隔离、重驱审计与持续故障退避是第 9 项；当前队列未配置 DLQ，消费异常在容器默认不重排队设置下可能缺失通知，需要通过交易/Outbox 与通知表对账定位。`PUBLISHED` 不得作为最终通知完成证明。Broker 故障只注入了拓扑声明异常，未执行真实 Broker 停机。通知鉴权通过 MockMvc 安全过滤链验证，未启动应用做真实 HTTP 请求。
