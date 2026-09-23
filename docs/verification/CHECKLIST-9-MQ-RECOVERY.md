# 第 9 项：Event Notification MQ 故障恢复与重驱

日期：2026-09-23。基线：`2阶段` 分支、`77de49a2340f39faa3505e4b362558690b33cfa5`；本项改动尚未提交。前置第 8 项见 [验收记录](CHECKLIST-8-EVENT-NOTIFICATIONS.md)。只修改 Event 通知副作用，第 10 项用户退款与对账未实施。

## 行为与边界

- V8 前向迁移新增 `et_notification_failure` 与 `et_notification_redrive`；V1～V6 均未修改。前者保留原消息体、eventId、失败分类和原因，后者记录操作人、原因、动作与结果。
- 新的持久拓扑为 `event-trading.notifications.v2` exchange / 主队列，以及 `event-trading.notifications.dlq.v2`。主队列失败拒绝后经 dead-letter 路由进入 DLQ。消息持久化；单节点 Broker 使用 Compose `rabbit-data`。V1 队列不自动删除或清空。
- 技术消费异常至多尝试 4 次，间隔按 1、2、4 秒退避，然后记为 `TECHNICAL` 并拒绝到 DLQ。无法解析、未知事件或非法 payload 分类为 `UNPROCESSABLE`，不进行无效技术重试，记录原因并隔离。正常业务拒绝在同步交易入口完成，不生成通知事件；已完成通知的重复投递视为幂等成功，由容器在数据库事务提交后 ACK。
- 发布侧维持 8 次上限、退避和租约回收。Confirm 前或 Confirm 后本地状态写入前失去进程时，租约到期重发原 eventId；`et_notification.event_id` 唯一约束保证重复投递只有一次通知效果。`PUBLISHED` 仍仅代表发布确认。
- `POST /api/v1/admin/notifications/{eventId}/redrive` 仅 ADMIN 可访问，要求非空原因。`MANUAL_REQUIRED` 发布事件原位重置为 `PENDING`；已 `PUBLISHED` 但缺失通知的事件按原 eventId 重新发往预期拓扑。重驱前拒绝无原 Outbox 行和已存在通知。发布确认后返回 `CONFIRMED`，实际效果仍需查询通知表。异常结果记为 `UNCERTAIN`，人工核对后才可再次操作。并发重驱用 `REDRIVING` 状态和 60 秒租约互斥；失去进程后可重新领取，重复发送由消费幂等兜底。

## 验证

环境：Windows IntelliJ IDEA 2026.1.3 / Java 21.0.7；隔离 Testcontainers MySQL 8.4 和 RabbitMQ 4.1。IDEA JUnit 输出不计入 Maven Surefire/Failsafe 报告。

- IDEA `build_project` 全量重建：成功、0 问题。
- `EventNotificationIT`：最终 12 started / 12 finished / 0 failed，退出码 0；日志 `C:\Users\ctmd12334567\AppData\Local\JetBrains\IntelliJIdea2026.1\tmp\ij_run__EventNotificationIT_4404504927199451210.log`。
- 真实 Broker 停机：在隔离 RabbitMQ 容器执行 `rabbitmqctl stop_app`，生成并关闭 100 个 Event 订单，持久化 100 条 Outbox 意图，停机期间通知数为 0。`start_app` 后在 120 秒预算内达到 100 个 `PUBLISHED`、100 个不同 eventId 的通知；最终用例耗时 8.010 秒（含停机、造数和恢复），不是吞吐或长期可用性测量。
- Publisher 两个确定性窗口：发送后、Confirm 获取前和 Confirm ACK 后、本地 `PUBLISHED` 更新前注入未捕获的进程失效模型；观察 `PROCESSING`，使租约到期并重发，两种情况均收敛到 `PUBLISHED`、attempts=2、每 eventId 一条通知。
- Consumer 两个窗口：在外层事务提交前注入异常，检查通知写入回滚；随后通过真实 Broker 投递，得到一次通知。提交后 ACK 丢失用重复 Broker 投递模拟，最终通知仍仅一条。注入模型没有实际杀死 JVM，也没有直接抓取 AMQP ACK 帧；证明的是对应持久化状态与幂等结果。
- 毒消息：payload 不匹配时记录 `UNPROCESSABLE`、原消息与原因，进入 DLQ；修正 payload 后管理员重驱原 eventId，审计行记录 actor/action/outcome，通知一次。技术故障：预置冲突通知行使持久化持续失败，经过有限退避进入 DLQ，移除冲突后重驱成功；技术故障用例耗时 7.317 秒，持续故障未形成热循环。两种故障都没有更改核心订单或库存。另三条用例验证旧队列升级后 `PUBLISHED` 但缺少通知的缺口、`MANUAL_REQUIRED` 发布事件，以及失联操作租约过期，都可按原 eventId 审计重驱；完成后拒绝重复人工操作，旧操作标记为 `UNCERTAIN`。
- `EventInfrastructureIT`：3/3，退出码 0，日志 `ij_run__EventInfrastructureIT_14650673469106888838.log`；验证隔离 MySQL 上 Flyway V1～V8 和 Event 基础设施。`SecurityRegressionTest`：5/5、退出码 0，日志 `ij_run__SecurityRegressionTest_195559854353056811.log`；新增未认证 401、非管理员 403、管理员可调用重驱的 MockMvc 断言。`LegacyMessagingIT`：1/1，退出码 0，日志 `ij_run__LegacyMessagingIT_8660964054362116485.log`，旧实验仍由 profile 隔离。

后续补强复验：IDEA `EventNotificationIT` 13 started / 13 finished / 0 failed，退出码 0，日志 `C:\\Users\\ctmd12334567\\AppData\\Local\\JetBrains\\IntelliJIdea2026.1\\tmp\\ij_run__EventNotificationIT_2695658547306119464.log`。新增两条前 64 字节相同的超长毒消息，通过 SHA-256 摘要形成不同的失败记录键，同时保留各自原始字节；重复投递用例在断言前同步执行了一次消费去重分支。100 事件实验仍由测试手动调用 `publisher.publish()`，验证的是 Broker 停机后可恢复处理，不是定时扫描器调度精度。Publisher/Consumer 崩溃窗口是故障注入与重复投递模型，没有实际杀死 JVM 或证明 Broker ACK 帧丢失。若重驱发布结果不确定且数据库同时不可用，操作行可能暂留 `STARTED`，需按租约与原事件/通知状态人工核对，不能把它视为已完成。

运行中曾发现并修正两项验证问题：最初直接停 Docker 容器后测试环境未恢复 Broker，改用实际 RabbitMQ 应用 `stop_app/start_app` 断开并恢复 AMQP 服务；新增 V8 后基础设施测试的迁移版本断言从 1～7 更新为 1～8。失败轮次未计入通过结果。

## 操作与迁移

升级前检查旧 `event-trading.notifications.v1` 队列中的待处理数、`PUBLISHED` 但未产生通知的 Outbox 行，以及 `MANUAL_REQUIRED`。V2 拓扑不会自动搬运旧队列的消息，也不自动删除旧队列；先停止旧发布者并确认旧消费者处理完成，或由管理员逐个核对缺失效果并按原 eventId 重驱。不要清空旧队列或直接把 `PUBLISHED` 当成效果完成。重驱后以 `et_notification` 查询最终业务效果，并保留 `et_notification_redrive` 记录。

排查示例：

```sql
SELECT o.event_id,o.publish_status,o.attempts,o.last_error,
       f.failure_kind,f.failure_reason,f.status AS failure_status,n.id AS notification_id
FROM et_outbox_event o
LEFT JOIN et_notification_failure f ON f.event_id=o.event_id
LEFT JOIN et_notification n ON n.event_id=o.event_id
WHERE o.publish_status='MANUAL_REQUIRED' OR f.status='FAILED'
   OR (o.publish_status='PUBLISHED' AND n.id IS NULL);

SELECT id,event_id,actor_id,reason,action,outcome,detail,created_at
FROM et_notification_redrive ORDER BY created_at DESC,id DESC LIMIT 100;
```

DLQ 不是零丢失保证：单节点或数据卷丢失、运维误删/清空、死信路由误配及失败记录数据库不可用都可能破坏隔离或可定位性。失败记录写入后与 Broker 死信转移也不是跨 MySQL/RabbitMQ 原子事务；必须核对原 Outbox、失败表、DLQ 和最终通知。无法确认的事件保留人工处理，不宣称自动完成。
