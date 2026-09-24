# 第 10 项：用户全额退款与针对性对账

日期：2026-09-23。开发起点为 `10` 分支的 `920b8ca`；该提交同时是当前本地 `phase-2` 和 `origin/phase-2` 的指向。工作区原有的第 9 项重驱补强保持未提交，本项改动也未提交。

## 行为

- `POST /api/v1/payments/{paymentId}/refunds` 限支付所属用户。仅成功支付、`PAID`/`FULFILLED` 订单、`CONFIRMED` 预占可创建全额退款。订单和唯一退款意图在本地事务内变为 `REFUNDING` / `REQUESTED`，事务外沿用 V1 的原退款号查询、提交、`UNKNOWN` 自动恢复和人工重试。重复用户请求返回同一退款；晚到支付补偿仍是 `LATE_PAYMENT`，不能借用户入口重复退款。
- 网关成功结果与本地退款成功、订单 `REFUNDED` 同事务。确认后的 `allocated` 和预占状态不改变，单笔退款金额等于实付金额；一笔支付由唯一约束限制为一条退款。失败或未知结果保持可查询，不能直接当作成功。
- 新增 V9 前向迁移只扩展 `et_refund.reason` 的检查约束；V1～V8 不回写。模拟网关自身也按支付号限制一笔全额退款。
- `GET /api/v1/admin/reconciliation` 在一个 MySQL `REPEATABLE READ` 只读事务中返回有限批次的四类异常：已关闭但预占未释放、支付/退款/订单冲突、超过 15 分钟的 `UNKNOWN`、超过 10 分钟未处理或缺失通知效果的 Outbox。超时阈值只用于分诊；合法 `CLOSED + SUCCEEDED + LATE_PAYMENT` 补偿中间态不报错。每类最多 100 条，各组可用 `afterOrderId`、`afterPaymentId`、`afterUnknownId` + `afterUnknownKind`、`afterEventId` 独立续查。每次请求各有快照；要跨页做完整审计，需停流或重复整轮核对。
- `POST /api/v1/admin/reconciliation/payments/{paymentId}/late-refund` 仅修复 `CLOSED + SUCCEEDED + RELEASED 预占 + 无退款`。先按订单、支付、预占顺序加锁，条件插入一条 `LATE_PAYMENT` 意图，并记录管理员 actor；重复请求返回同一 ID，不在事务内调用网关。其他异常由管理员按原始支付、退款、库存及通知记录核对，不能据快照自动更改状态。新意图交给既有退款恢复扫描器。

## 验证

- IDEA `EventPaymentServiceTest`：32 started / 32 finished / 0 failed，退出码 0。最终复验日志 `C:\Users\ctmd12334567\AppData\Local\JetBrains\IntelliJIdea2026.1\tmp\ij_run__EventPaymentServiceTest_2613936170490680026.log`。新增用例覆盖用户退款幂等、库存不回补、丢失成功响应后的 `UNKNOWN` 查询恢复、所有权、异常注入检测、合法补偿中间态、安全修复及管理员 actor 记录、游标续查，以及支付先取得订单锁时修复不得覆盖新状态。
- IDEA `PaymentBoundaryIT`：32 started / 32 finished / 0 failed，退出码 0；真实隔离 MySQL 8.4 与 Flyway V1～V9。最终复验日志 `C:\Users\ctmd12334567\AppData\Local\JetBrains\IntelliJIdea2026.1\tmp\ij_run__PaymentBoundaryIT_1393420932390856055.log`。
- IDEA `EventInfrastructureIT`：3 started / 3 finished / 0 failed，退出码 0；日志 `C:\Users\ctmd12334567\AppData\Local\JetBrains\IntelliJIdea2026.1\tmp\ij_run__EventInfrastructureIT_10060800666729387939.log`。其中迁移日志记录 V9 已实际应用，另两项保护旧实验隔离和 1000 请求竞争库存。
- IDEA `SecurityRegressionTest`：6 started / 6 finished / 0 failed，退出码 0；日志 `C:\Users\ctmd12334567\AppData\Local\JetBrains\IntelliJIdea2026.1\tmp\ij_run__SecurityRegressionTest_14797695651472043028.log`。`PaymentControllerTest`：1 started / 1 finished / 0 failed，退出码 0，验证退款入口使用认证用户身份。后者输出由 IDEA MCP 返回，本次未保存单独日志路径。
- 上述为 IDEA JUnit 结果，不等于 Maven Surefire/Failsafe 报告。五组共 74/74 通过。MySQL 首次复验出现 1 条失败：测试断言将其他用例注入的异常行计入全局数量；改为按本用例业务 ID 断言并恢复注入状态后，两套 32 项测试重新运行通过。失败轮次未计入通过结果。

## 边界

未实现通用对账框架或自动修复库存、网关矛盾和通知效果。模拟支付网关是独立持久化的测试提供方；真实第三方退款时效、结算和资金对账尚无证据。第 9 项遗留的未提交补强在当前工作区中，不归入第 10 项验收结论。
