# Event Trading 业务收敛与工程资产保护清单

日期：2026-09-17

用途：供作者与后续执行模型分阶段重构使用，不作为对外 README 导航。

## 执行目标与边界

最终主分支以 Event Trading 的清晰度为优先。保护工程资产，指保留其实现依据、测试、实验、设计决策和可追溯版本，不要求所有旧 Voucher 源码永久存在于最新版主工程。

旧实现仅在尚未完成迁移、仍有独立验证价值或仍是唯一可运行证据时暂留。不得为了代码整洁丢失工程成果，也不得以可能复用为由长期维护第二套产品。

- 私人 DEVELOPMENT-CHECKLIST.md 冻结阶段定义；以真实代码判断实现状态。发现冲突应记录，不能自行扩需求。
- 当前 0～6 已有对应验收；第 7 项尚未正式完成。本清理先于第 7 项，不提前实现 V2。
- Event 核心订单仍在 MySQL 本地事务中同步创建，不能改为 RabbitMQ 消费者创建。
- 不重设计已验收的库存守恒、幂等、购买限制、关单、UNKNOWN、支付关单竞争和晚到支付补偿。
- Flyway V1～V6 不改、不删；不重建开发库，不清空 Redis、队列或数据卷。
- 不改公共响应格式，不进行无必要的全仓改名、分层或多模块改造。
- 旧测试结果仅代表原验收状态；清理后结果单独记录，不能沿用旧测试数量或性能结论。
- 一次执行一个阶段，完成该阶段的改动、验证和记录后停下，等待作者指定下一阶段。
- 不自动提交、推送或修改远程。新增规划文件不代表授权一次性实施全部阶段。

## 已核实基线：执行前重新检查

- 分支 main；审计时 HEAD 为 ee751827980ef9c5be796becbcf95cc40216edd2。
- 未跟踪工作必须保护：scripts/demo-v1.sh，以及 loadtest/audit-event-trading-baseline.sql、event-trading-baseline.sh、seed-event-trading-baseline-tokens.lua、seed-event-trading-baseline.sql。不得顺手删除、覆盖或提交。
- 桌面清单与项目清单阶段定义一致；项目副本已勾选第 6 项，桌面副本未勾选。勾选状态不替代代码和验收证据。
- 本地对象库可读取 cd293f3（com.hmdp 早期快照），但审计时没有分支或标签指向它。它不是已证实的完整上游仓库。
- 4613e0b：当前项目初始发布快照，已包含 MySQL 交易事实源、持久请求、Outbox 和可靠性测试，不是未改造的教程快照。
- 5c880d3：短事务、事务外幂等读取、多级 Lua 限流、批量发布及 k6 实验。
- 5479ae8：MySQL 库存分桶、公平信号量、批量消费、批量租约、令牌桶、业务指标及会话 Lua 优化。
- 50c547b 至 ee75182：Event 领域、订单生命周期与模拟支付边界。

## 工程资产去向原则

- StockBuckets：保留实现与并发实验。Event 有热点锁竞争证据时再评估迁移，不能直接替换 available/reserved/allocated。
- RequestLimits、AuthCodes.limitAll、rate-limits.lua：保留多级准入算法及验证；旧业务 key 组装与通用机制分开。Event 是否接入另行决策。
- OrderPerformance：保留公平信号量、有界等待、拒绝和许可释放机制；可按需提炼，不能直接改变 Event 当前行为。它是单实例并发保护，不是全局数据库连接上限。
- OrderTransactions：保留短事务、幂等竞争、回滚与批量消费依据；旧异步成单业务最终退出正式产品。
- OutboxPublisher、QueueConfig、SeckillVoucherListener：V2 吸收租约、重试、Confirm/Return、批处理和提交后 ACK 经验；不能照搬消费者创建订单、回写 Outbox completed 的业务语义。
- OutboxMetrics、旧 order 指标：保留设计和实验，不能重新命名后冒充 Event 指标。
- k6、SQL/Lua 种子、并发测试、实验记录：保留版本、条件、局限和复现方法；旧 415/1500 RPS 不代表 Event 性能。原始结果缺失处如实标记。
- TokenFilter、auth-session.lua、验证码和身份安全：正式产品继续使用。

## A. 固定资产与证据——任何删除之前

推荐执行模型：GPT-5.6 Sol Medium。

- [x] 重新确认分支、HEAD、未提交工作、私人清单和适用 AGENTS.md。
- [x] 保护 cd293f3 及其可读取祖先的可追溯性；采用本地引用或备份，不改写历史、不推送引用。记录具体方式。
- [x] 在 docs 中建立工程资产记录，列出每项来源版本、当前实现、测试、实验、设计取舍和目标去向。
- [x] 为资产选择正式保留、待迁移、独立实验或历史归档；注明旧实现的暂留理由与退出条件。
- [x] 区分已保存的原始结果、历史摘要和未验证结论。
- [x] 记录 V1～V6 校验值及未跟踪工作清单，供后续核对。

阶段 A 完成记录（2026-09-18）：

- 复核时 main、HEAD 与本地 origin/main 均为 ee751827980ef9c5be796becbcf95cc40216edd2；开始记录前无已跟踪修改。
- 本地引用 refs/archive/engineering-assets/hmdp-cd293f3 指向 cd293f343395f277a743d05a8fdeba74f5779885，保护其 3 个可读提交；未创建或推送远程引用。
- 资产来源、分类、实现/测试/实验、去向、退出条件、证据缺口、V1～V6 SHA-256 与未跟踪工作见 docs/verification/REFACTORING-STAGE-A-ASSET-REGISTER.md。
- 本阶段只做 Git/文件只读检查、建立本地引用和文档记录；未运行测试、迁移、脚本或压测，未进入阶段 B、第 7 项或 V2。

完成条件：每项资产可定位；历史对象得到保护；本阶段不改 Java、配置、迁移、测试行为或业务数据。以只读检查验证记录，不运行压测。

## B. 拆开测试与运行依赖——裁剪业务之前

推荐执行模型：GPT-5.6 Sol High。

- [ ] 拆分 InfrastructureIT：Event 验收和旧消息实验各自独立；同步拆分 src/test/resources/db/infrastructure-seed.sql。
- [ ] 将 VoucherOrderControllerTest 按实际用途改名，保留 logoutRevokesRedisToken 断言。
- [ ] 拆分 Java21CompatibilityTest 中的旧商户缓存、分页依赖和通用配置验证。
- [ ] 明确 OrderTransactionsTest、OutboxPublisherTest、RequestLimitsTest 的保留或迁移位置，不因文件名删除断言。
- [ ] 明确默认验证和工程实验验证入口，仍排除会接触开发数据的 manual 测试。

完成条件：原有断言有去向；Event 的 1000 请求竞争 100 库存、旧消息幂等和事务回滚验证保留；夹具使用隔离资源。

必须验证：拆分后的集成测试、OrderTransactionsTest、OutboxPublisherTest、身份和配置测试。不以删除断言或降低标准解决失败。

## C. 隔离旧性能链路——第 7 项前

推荐执行模型：GPT-5.6 Sol High；仅复杂边界难以判断时使用 GPT-6 审查。

- [ ] 设置显式工程实验 profile 或等价开关，默认应用仅启用 Event 产品。
- [ ] 一起隔离旧 Controller、事务组件、Publisher、Listener、QueueConfig 和 Metrics；不能只设置 app.outbox.enabled=false。
- [ ] 核查 Rabbit 自动配置、健康检查和 Bean 注入，避免默认启动仍依赖旧 Broker。
- [ ] 保留关单和支付恢复调度；不得关闭全局 Scheduling 来停旧 Outbox。
- [ ] 明确现有 k6 的复现路径：暂留实验专用最小 HTTP 适配层，或采用固定历史版本在隔离环境复现。删除原入口前验证所选路径。
- [ ] 旧实验只维护必要可运行性，不新增 Voucher 业务，不作为正式产品接口。
- [ ] 同步整理 application.yaml、compose.yaml、pom.xml，保留必要依赖，不操作现存数据或队列。

完成条件：默认无旧业务处理器、消费者、Outbox 扫描和旧业务指标；Event 在无旧 Broker 的隔离环境中正常启动；工程资产仍有有效验证入口。

必须验证：默认/实验两种上下文、旧消息集成测试、Event 基础设施和生命周期测试。只做必要功能验证，不擅自找容量上限。

## D. 删除无关业务并退役无用包装——B/C 完成后、第 7 项前

推荐执行模型：GPT-5.6 Sol Medium；共享身份和 Security 变更使用 Sol High 检查。

- [ ] 删除 controller 中 Shop、ShopType、Blog、Follow、博客 Upload 入口及对应 service、service/impl、mapper、entity 文件。
- [ ] 从 UserController、IUserService、UserServiceImpl 删除签到、签到统计和旧社交资料，保留验证码、登录、logout、当前身份。
- [ ] 删除无剩余用途的 UserInfo、ScrollResult、CacheClient、RedisData、ImageStorage 和专用测试；历史修复经验保留在文档。
- [ ] 清理 RedisConstants、SystemConstants 中旧功能常量，保留 Token key、用户创建等仍用内容。
- [ ] 同步修改 SecurityConfig、SecurityRegressionTest、上传配置及 WebExceptionAdvice 的上传专用处理。
- [ ] MybatisConfig 保留 UserMapper 扫描；分页插件及 POM 依赖只在剩余用途查清后处理。MyBatis-Plus、Hutool、Redis 不可整项盲删。
- [ ] 对 Voucher/VoucherOrder Controller、Service、Mapper、Entity、VoucherMapper.xml 逐组评估，无剩余用途的删除。
- [ ] 删除 VoucherServiceImpl 前确认分桶初始化已有有效实验夹具或适配层承接。
- [ ] 对暂留分桶、限流、信号量、消息和指标代码落实迁移/实验/归档位置；去向完成后移除旧适配，而不是全部永久留在正式业务包。
- [ ] 不删除数据库表；旧表及迁移与 Java 文件删除分开处理。

完成条件：没有悬空依赖；默认业务只剩 Event；每份暂留旧源码均有保留理由与退出条件；算法和验证资产未因删除包装而丢失。

必须验证：AuthCodesTest、logout、安全与权限、配置绑定、完整上下文，以及受影响的 Event 和工程实验测试。

## E. 统一文档与清理验收——代码形态稳定后

推荐执行模型：GPT-5.6 Sol Medium；事实冲突由 Sol High 复核。

- [ ] 整理 README.md / README.en.md，以 Event 业务、运行方式、设计亮点和证据为主线。
- [ ] 旧库存桶、异步成单、415/1500 RPS 进入明确的历史工程实验说明，注明来源版本和测量边界。
- [ ] PROJECT-REQUIREMENTS.md 对齐私人清单 V1/V2/V3，避免将 SSE、完整告警、备份演练、60min soak 全部作为当前必做项。
- [ ] SECURITY-FIXES.md 保留历史修复依据，修正或标注支付未实现、不会自动迁移等失效描述。
- [ ] CHECKLIST-0-6.md、PAYMENT-BOUNDARY-DESIGN.md 补充提交状态，不覆盖原始验收条件和结果。
- [ ] API-CONTRACT.md 对齐真实响应与幂等规则，不为迁就文档修改已验收接口。
- [ ] Postman 移除当前商户入口；旧 loadtest 整理为历史/实验材料。完整 Event 请求集合留在第 7 项。
- [ ] 新增本轮清理验收记录，绑定实际版本或候选 diff；核对 V1～V6 校验值与受保护文件。

完成条件：当前产品、历史成果和未来计划分明；文件链接及运行说明有效；测试结论对应真实版本。

必须验证：最终代码状态的默认测试、Event 集成、EventOrderLifecycleIT、PaymentBoundaryIT 及必要实验回归。纯文档修改不重复执行已通过的相同代码验证。

## F. 第 7 项进入条件——清理验收通过后，另行授权

- [ ] 默认产品只包含 Identity/Security、Event Catalog、同步 Order/Inventory、Payment/Compensation 和必要基础设施。
- [ ] 0～6 业务规则和事务边界未变；V1～V6 未变。
- [ ] 工程成果有可追溯且适当可复现的保留位置，不需要长期维护两套对外交易业务。
- [ ] 再正式处理 Event Demo、请求集合、联合验收和真实写链路基线；先检查已有未跟踪材料，不覆盖或假定它们已通过。
- [ ] 完善 README 的快速体验、展示和 GitHub 可读性；不得夸大能力或借用旧性能。

本阶段属于私人清单第 7 项，不因批准清理 A～E 自动获得实施授权。

## G. V2 吸收与最终退出——第 8～12 项期间，另行授权

- [ ] 第 8～9 项吸收 Outbox lease/retry、Confirm/Return、幂等消费、提交后 ACK 经验，用于 Event Notification。
- [ ] 业务事实与 Outbox intent 同事务，核心订单已经提交；消费者不创建核心订单。
- [ ] 重新定义 Event payload、发布状态、消费去重与故障恢复；不照搬旧 completed 语义及无限补发行为。
- [ ] 批量发布/消费按吞吐收益和失败边界决定，重新验证整批失败、毒消息与重投。
- [ ] 限流、信号量按 Event 的准入和过载需要迁移，不机械增加主链复杂度。
- [ ] 第 11 项依据实际 Event 锁等待与负载证据决定是否分桶；不迁移也可保留为独立实验。
- [ ] 每项迁移完成后核对实现、测试、实验和设计记录的接续，再删失去用途的旧适配代码。
- [ ] 旧表在运行及实验依赖解除后，以新 forward migration 单独规划；不回写 V1～V6。
- [ ] 收尾时不再以以后可能复用为由保留旧业务源码。

推荐模型：Sol High 分项实施；GPT-6 仅用于复杂事务/消息失败边界审查；明确的文档和夹具整理用 Sol Medium。

## 每阶段交付要求

1. 改动范围与实际 diff；说明资产去向，不只统计删了多少行。
2. 执行的验证、结果、失败/未验证项和对应版本。可用 IDEA 运行时给出准确测试类，不改 JDK/Maven 持久配置。
3. 本阶段清单勾选只依据实际完成情况；不得提前勾选下一阶段。
4. 核对未跟踪材料及 V1～V6 未被误改。
5. 给出剩余风险和下一阶段入口，然后停止；不提交、不推送。

统一删除门槛：依赖已解除，工程资产已有明确去向，必要验证通过，历史可追溯。
