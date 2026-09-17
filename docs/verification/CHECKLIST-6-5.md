# 6.5：支付回调与查询恢复

日期：2026-09-17。基线：`762b243`（已推送的 6.4）。

状态：源码、静态复核和 IDEA 定向测试完成。本记录仅验收 6.5，不代表第 6 项整体完成。

## 本次实现

- `PaymentCallbackService` 是内部服务边界，不新增 HTTP 路由（支付 API 和 security 放在 6.7）。它使用 HMAC-SHA256 校验 `timestamp + "." + rawBody`，常量时间比较签名，并检查默认 300 秒的新鲜度；未配置 `SIM_GATEWAY_CALLBACK_SECRET` 时拒绝接收，仓库没有可用默认密钥。
- 经认证的回调先在独立 `REQUIRES_NEW` 短事务写入 `et_payment_callback` receipt；同一 `(provider,event_id)` 与相同 payload 重放复用 receipt，变更 payload 返回冲突。未知本地支付被保存为 `UNMATCHED/MANUAL_REQUIRED`，不丢弃；退款回调被持久化拒绝为 `REFUND_CALLBACK_NOT_IMPLEMENTED`，不提前实现 6.6。
- 支付回调核对支付号、网关流水号、订单号、金额和币种。支付结果、订单/库存变化、支付历史与 receipt `APPLIED` 在同一事务完成；重复已应用 receipt 不再分配库存。业务事务崩溃后 `RECEIVED` receipt 保持可恢复。
- `PaymentRecoveryScanner` 在启动和每秒扫描时，从持久化 `PROCESSING/UNKNOWN` 支付中租约领取、先查询独立网关、再应用可信结果。网关找不到记录或调用错误只保留/转为 `UNKNOWN` 并退避，绝不伪造 `FAILED` 或重新发起扣款；5 次总尝试后转 `MANUAL_REQUIRED`。同一 scanner 也重试已提交但未应用的 callback receipt。

## 验证

使用 IDEA 项目 SDK `ms-21`（Microsoft OpenJDK 21.0.7）运行：

- `com.eventplatform.payment.EventPaymentServiceTest`：最终复跑 14 个 `testFinished`，无 `testFailed`、无 `testIgnored`。
- `com.eventplatform.payment.EventPaymentServiceIT`：MySQL 8.4 Testcontainers，最终复跑 14 个 `testFinished`，无 `testFailed`、无 `testIgnored`；日志确认 Flyway V1–V6 在空库成功执行。

新增断言覆盖：无效签名不落 receipt；签名回调的 receipt 重放与 payload 冲突；网关成功、客户端响应丢失后由查询恢复；领取租约与尝试耗尽人工交接；receipt 已提交但业务事务回滚后的恢复应用；两个 scanner 通过真实 JDBC 屏障同时选中相同支付后只能产生一个 lease。既有 6.4 支付先赢、关闭先赢不重新分配、幂等和库存守恒断言继续通过。

本轮在命令行没有启动 Maven/JDK。Flyway 对 MySQL 8.4 的“未验证支持版本”、旧迁移 `UNSIGNED` 弃用和 Mockito 动态 agent 的警告仍存在，但无测试失败；它们不被记为已解决。最终 IDEA 临时日志：H2 `ij_run__EventPaymentServiceTest_8633694610747301953.log`，SHA-256 `a0713b45544721c9b1635b1b50c1a2e22de44fa43e88818859efd0938b64b1c1`；MySQL `ij_run__EventPaymentServiceIT_18214388647188363424.log`，SHA-256 `d20697af468bca9164ae7f9317d8278703c43c9692453cc96eb022b5a98dc521`。

## 明确未做

- 没有 `POST /api/v1/payment-callbacks/simulated` 或支付查询/刷新 HTTP 路由，留给 6.7。
- 没有退款 callback 应用、退款查询或唯一补偿退款，留给 6.6。
- 没有全量支付边界的真实 MySQL 竞争/重启/退款验收，留给 6.8；没有 `CHECKLIST-0-6.md`，留给 6.9。
