# 6.4：本地支付编排与结果事务

日期：2026-09-16。基线：`238cb95`（6.1–6.3）。

状态：2026-09-17 已通过 IDEA MCP 直接运行下列三个 JUnit 测试类，共 28 项通过，0 失败、0 跳过，各类退出码均为 0；未在命令行另起 Maven/JDK。静态复核完成。
本记录不是第 6 项完整验收，不替代后续 `CHECKLIST-0-6.md`。

## 本次实现

- `EventPaymentService.create` 校验本人订单、可见 ASCII 幂等键、订单状态与数据库支付截止时间；金额、币种和订单号来自服务端快照。一个订单只允许一笔支付，同键重放不再次发起网关调用，换键或跨订单复用键冲突。
- 短事务提交支付意图与首次发送声明（合并为 `PROCESSING`，`attempts=1`）及历史，然后在无本地事务环境下调用独立网关，再用新事务落结果。拒绝在已有事务中调用编排入口，避免外层事务破坏提交边界。
- 确认时按订单 → 支付 → 预占 → 票档加锁；成功支付、订单 `PAID`、预占 `CONFIRMED`、`reserved → allocated`、历史记录原子提交。验证不可变关联、金额、币种、网关流水号、更新行数及库存守恒。重复成功结果不重复分配。
- 网关异常记为 `UNKNOWN`，不伪造失败；本地确认失败则回滚整个确认事务，已提交支付意图及网关结果保留。已成功/失败的支付不因迟到的超时报告降级。
- 过期扫描选中订单后若支付先提交，重新加锁看到 `PAID` 即无操作退出，不写关单失败重试；用户取消已支付订单仍拒绝。
- 关单先提交时，晚到成功只记录支付成功与 `MANUAL_REQUIRED / LATE_PAYMENT_COMPENSATION_NOT_IMPLEMENTED`，不改关闭订单、不碰库存、不生成退款。这只是分步实现的可见缺口，不是补偿完成，也没有人工处理接口。

## 静态复核

- 仅增加支付服务、测试及上述订单扫描分支；未修改 V6、网关、Outbox 或安全路由。
- 网关调用与本地确认之间不存在共享业务事务；结果历史写入失败会传播异常并回滚本地确认。
- 测试屏障位于真实 JDBC 操作之后，没有生产测试钩子或 `sleep` 竞态判定。
- H2 夹具去除 MySQL 字符集/存储引擎声明并拆分 V5 的多列 ALTER；不能替代真实 MySQL 迁移和锁行为验收。
- `git diff --check` 及新增文件空白检查通过；不代表编译、数据库兼容性或并发测试已通过。

## IDEA 实测记录（2026-09-17）

通过 IntelliJ IDEA 2026.1.3 的 MCP `execute_run_configuration` 按源码测试入口逐个运行。项目 SDK `ms-21` 映射到 Microsoft OpenJDK 21.0.7，未设置替代 JRE。Docker 服务端 29.7.2。

1. `com.eventplatform.payment.EventPaymentServiceTest`：H2，10 项通过，退出码 0。
2. `com.eventplatform.payment.EventPaymentServiceIT`：MySQL 8.4 测试容器，10 项通过，退出码 0；日志确认空库原样成功执行 6 个 Flyway 迁移，到达 v6。
3. `com.eventplatform.EventOrderServiceTest`：既有订单回归，8 项通过，退出码 0。

两个支付测试类共用断言：正常支付与重复结果；权限/键/状态/期限/外层事务拒绝；换键冲突；网关成功但响应丢失；PROCESSING/明确失败不分配；非法金额拒绝；历史写入失败整事务回滚；真实订单锁与候选选择屏障下支付先赢；首次网关调用暂停时并发同键重放；关单先赢后晚到成功不重新分配。

核验方式：MCP 返回各测试进程的 `exitCode=0`，再读取 `fullOutputPath` 完整日志，分别确认 10、10、8 条 `testFinished`，没有 `testFailed` 或 `testIgnored`。并非仅凭启动成功或截断预览判断。IDEA 直接运行不生成 Maven Failsafe 报告；本次没有运行整个默认测试集、`InfrastructureIT` 或 `SimulatedPaymentGatewayTest`，不将结果扩大到它们。

原始日志在本机 `%LOCALAPPDATA%\JetBrains\IntelliJIdea2026.1\tmp`，属于 IDEA 临时文件，可能随清理丢失，未纳入版本控制。文件名及 SHA-256：

- `ij_run__EventPaymentServiceTest_15330330717902176100.log`：`c400a140969f3b2df350539a712ba36f3b214c33b32a07e2893a3a32d3bf5591`
- `ij_run__EventPaymentServiceIT_15518565908260868653.log`：`ed778625f0d0abee833cf80f489082864ad16c5f1f5237b50ac66a25575f0215`
- `ij_run__EventOrderServiceTest_1333294087391859527.log`：`9994727264a2f31ac02c462ac8ed15243dcec02a1bbc6ce9b91e83b86ab5b4c0`

非失败警告保留：Flyway 提示 MySQL 8.4 超出其已验证支持范围；历史迁移中 decimal/floating-point UNSIGNED 存在弃用提示；Mockito/Byte Buddy 动态 agent 加载和 JVM CDS 有警告。本次实际迁移与断言通过，不代表这些兼容性提示已解决。

## 后续边界

6.5 才实现回调与查询恢复/扫描；6.6 才实现唯一补偿退款及人工重试；6.7 才开放支付 API。尚无自动恢复消费者，`AUTO` 与 `next_attempt_at` 仅保存未来恢复依据；不要将本切片用于真实收款。第 6 项全面竞争、重启、退款及防重验收留给 6.8，完整文档验收留给 6.9。本次不提交、不推送。
