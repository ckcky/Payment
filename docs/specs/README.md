# Specs 索引（按阶段分组）

> 本目录按**阶段（Stage）**组织 Feature 文档。阶段 = 以目标为导向的一组相关 Feature；每个阶段一个 slug 子目录。
> 各 Feature 文档遵循 **Spec Kit 四件套**：`spec.md` / `plan.md` / `tasks.md` / `acceptance.md`。
>
> **状态口径**：Feature 生命周期统一为 8 态——`Draft` / `In Review` / `Approved` / `In Development` / `Implemented` / `Deprecated` / `Superseded` / `Not Implemented`（详见 [standards/documentation-governance.md](../standards/documentation-governance.md) §5.3）。本索引的状态描述 MUST 与各 Feature 的 `spec.md` 头部一致。
> 明确属于 design-only 的 Feature（如 UI 设计规范类）在 `spec.md` 头部声明 `delivery_mode: design-only`，不强制四件套齐全。

## 阶段分层约定

- **阶段（Stage）**：一组目标一致的 Feature 的归组层，目录名 `stage-XX-<slug>`。
- **stage-design（阶段总目标书）**：该阶段目标的来源文档。**新阶段**在启动时可选写 `docs/specs/<stage>/stage-design.md`。
- **归档规则**：阶段内全部 Feature 交付并合入 master 后，阶段总目标书归档到 `docs/archive/design/<YYYY-MM-DD>-<stage-slug>/`（复用审计归档的做法），不再作为有效计划（以 `docs/architecture/roadmap.md` 为准）。
- **索引维护**：新增 Feature / 切换阶段时，更新本索引与 `roadmap.md`。

## stage-01-core-mvp — 核心主链打通 + 资金闭环 + 治理基线

> 核心业务主链（merchant→catalog→order→payment→fulfillment→entitlement）+ 退款/对账/结算/Ledger 资金闭环 + 风控与分布式演进门禁。

- [001-core-business-model](stage-01-core-mvp/001-core-business-model/)
- [002-payment-order-callback](stage-01-core-mvp/002-payment-order-callback/)
- [003-payment-reliability](stage-01-core-mvp/003-payment-reliability/)
- [004-ledger](stage-01-core-mvp/004-ledger/)
- [005-refund](stage-01-core-mvp/005-refund/)
- [006-reconciliation](stage-01-core-mvp/006-reconciliation/)
- [007-settlement](stage-01-core-mvp/007-settlement/)
- [009-risk-security](stage-01-core-mvp/009-risk-security/)
- [010-distributed-evolution](stage-01-core-mvp/010-distributed-evolution/)

## stage-02-demo-idempotency-seckill — 演示 / 幂等 / 库存 / 秒杀缓存

> 本阶段总目标设计书：**[next-stage-design.md](../archive/design/2026-09-19-next-stage-011-014/next-stage-design.md)（已归档，2026-09-19）**——011~014 已全部交付。

- [011-demo-showcase](stage-02-demo-idempotency-seckill/011-demo-showcase/)
- [012-entry-idempotency](stage-02-demo-idempotency-seckill/012-entry-idempotency/)
- [013-inventory-reservation](stage-02-demo-idempotency-seckill/013-inventory-reservation/)
- [014-seckill-and-cache](stage-02-demo-idempotency-seckill/014-seckill-and-cache/)

## stage-03-evolution-consolidation — 演化与收口

> 多支付单模型 / 订单驱动编排与退款 / 审计四核对 / schema 规范化 / 演示 UI 设计系统 / 访问日志 / 全链路自动化测试 / 审计运维收尾 / 雪花单号 / 本地全栈容器化。

- [015-multi-channel-payment](stage-03-evolution-consolidation/015-multi-channel-payment/)
- [016-order-payment-orchestration](stage-03-evolution-consolidation/016-order-payment-orchestration/)
- [017-accounting-audit](stage-03-evolution-consolidation/017-accounting-audit/)
- [018-schema-normalization-item-fulfillment](stage-03-evolution-consolidation/018-schema-normalization-item-fulfillment/)
- [019-order-driven-refund](stage-03-evolution-consolidation/019-order-driven-refund/)
- [020-demo-ui-design-system](stage-03-evolution-consolidation/020-demo-ui-design-system/)
- [021-unified-access-logging](stage-03-evolution-consolidation/021-unified-access-logging/)
- [022-full-chain-automated-testing](stage-03-evolution-consolidation/022-full-chain-automated-testing/)
- [023-audit-ops-remediation](stage-03-evolution-consolidation/023-audit-ops-remediation/)
- [024-demo-ui-apple-redesign](stage-03-evolution-consolidation/024-demo-ui-apple-redesign/)
- [025-snowflake-business-no](stage-03-evolution-consolidation/025-snowflake-business-no/)
- [026-containerized-local-stack](stage-03-evolution-consolidation/026-containerized-local-stack/)

## stage-04-new-directions — 新能力方向

> 用户支付限额 / 渠道路由 / Redis 事务消息。

- [027-user-payment-limit](stage-04-new-directions/027-user-payment-limit/)
- [028-channel-routing](stage-04-new-directions/028-channel-routing/)
- [029-redis-transactional-mq](stage-04-new-directions/029-redis-transactional-mq/)

## stage-05-channel-and-finance-deepening — 渠道与资金纵深（030~039 已实现；040、041 为 `Draft`）

> 本阶段总目标设计书：**[stage-design.md](stage-05-channel-and-finance-deepening/stage-design.md)（Draft，2026-09-19）**——
> 现状评估 + 总体架构 + 渠道接入 / 账务总账 / 对账结算 / 可靠性 / 可观测性 / 测试策略六面目标态 + Feature 拆分建议。
>
> 正式审查（以代码为事实来源）：**[design-review.md](stage-05-channel-and-finance-deepening/design-review.md)（v1.1，2026-09-19）**——
> 14 节审查（领域模型 / 两个状态机 / 渠道架构 / 回调 / 账本 / 对账 / 结算 / 可靠性 11 类故障走查）+
> **24 项设计冲突**（1 项 🔴 阻断、16 项 🟠、4 项 🟡、10 项 ⚪）+ 必需变更分级 + **21 项人类决策** + 自 `030` 起的 Feature 实施矩阵。
> **v1.1 修订**：**C-11 由 🟠 降级为 ⚪ 验证通过**——「重复支付自动退款」**不是缺失能力**，而是既有能力
> （`TransactionApplicationService.surplusRefund`，见 §11 的 C-11 专项验证表 10 点）；**不新增「禁止多 Payment SUCCESS」、
> 不要求「换渠道前关闭旧 Payment」**。验证过程中新发现 7 项真实缺陷（C-18~C-24），其中 4 项 🟠 集中在
> **自动退款的恢复路径不可自愈**（C-18/C-19）与**对账/结算事实缺期间与商户维度**（C-20）。
> 结论：**stage-design 方向通过，需修订后生效**；进入实现前须先完成「文档收口 + 前置一致性收口（B1~B7）」两个门。

- [030-channel-contract-sandbox-callback](stage-05-channel-and-finance-deepening/030-channel-contract-sandbox-callback/) —— 🟢 **Implemented**（合并点 `3a39a7a`；[tasks.md](stage-05-channel-and-finance-deepening/030-channel-contract-sandbox-callback/tasks.md) 载 142/142 完成，[acceptance.md](stage-05-channel-and-finance-deepening/030-channel-contract-sandbox-callback/acceptance.md) 载已合入 master `e348090`；ADR-0075 / ADR-0076 均 Accepted → Implemented）。**旁证**：`origin/master` 上已合并 5 个 030 分支 PR（#5~#9），统一渠道契约代码落在 `payment-service/src/main/java/com/payment/payment/application/channel/`（`PaymentChannel` / `ChannelRegistry` / `ChannelRouter` / `AlipayGateway` / `ChannelResult` 等）。⚠️ 本编号占用 `030`（原 stage-04 的 `030-channel-contract-dye-alipay-sandbox` 已于 2026-09-19 删除，设计被本 Feature 全量吸收，见 [spec §0.3](stage-05-channel-and-finance-deepening/030-channel-contract-sandbox-callback/spec.md)）。
- [031-ledger-accounting-foundation](stage-05-channel-and-finance-deepening/031-ledger-accounting-foundation/) —— 🟢 **已实现（2026-09-21，spec + Plan/Tasks/Acceptance 三件套齐）**：Accounting Event + Posting Rule + 两级科目 + 余额投影/期间；载体决策 [ADR-0077~0079](../adr/0077-ledger-accounting-foundation-decisions.md)（**Accepted**，负责人 D-1~D-7 按推荐方案批准）。⚠️ 编号勘误：stage-design §9.2 旧名 `032-ledger-account-view` 作废，后续 Feature 顺移（032=对账、033=测试、034=可靠性、035=可观测，与 design-review §12 对齐），已随本条回写。
- [032-reconciliation-real-statement](stage-05-channel-and-finance-deepening/032-reconciliation-real-statement/) —— 🟢 **已实现（2026-09-21，spec + Plan/Tasks/Acceptance 三件套齐）**：渠道账单成为导入对象（`statement_imports`/`statement_lines`，**删除 `sample.csv` 静默回退**）+ 匹配键升级 `(merchantId, referenceType, reference)` 三级降级 + 差异拆独立行表并复用四核对 5 态 + 032 为 `CHANNEL_SETTLEMENT`/`CHANNEL_FEE` 唯一产生方 + 结算口径先于差异策略化；关闭 C-13/C-20/C-22；决策 [ADR-0080](../adr/0080-reconciliation-statement-and-fund-facts.md)（**Accepted，2026-09-21 负责人按 spec 推荐方案批准 H-032-1~6**）。T24b（依赖 034 C-19 的记账同事务回归）已随 2026-09-21 合并 master(034) 后全量回归与 demo 实测收口。
- [033-test-infrastructure](stage-05-channel-and-finance-deepening/033-test-infrastructure/) —— 🟢 **已实现（2026-09-21，spec + Plan/Tasks/Acceptance 三件套齐）**：Testcontainers 放宽至**仅测试作用域**（`deployment/test-infra` 共享基座：单例 MySQL 容器 + 类内独占库 + DDL 单一来源，只切幂等竞争/并发累加/并发唯一三类）+ schema 双路径重放成 CI 门禁（首基线 `baseline/033.sql`）+ 迁移可重放条文与 lint（016 方言修复）+ 运行时 RPC 边允许清单（15 条 Feign 边）；决策 [ADR-0081](../adr/0081-test-carrier-and-schema-replayability.md)（**Accepted，2026-09-21 负责人按 spec 推荐方案批准 H-033-1~5；触及 Constitution §Engineering.3 已同步**）。既有 H2 用例与桩零删除；业务用例本体归 031/032/payment 域。
- [034-reliability-hardening](stage-05-channel-and-finance-deepening/034-reliability-hardening/) —— 🟢 **已实现（2026-09-21，spec + Plan/Tasks/Acceptance 三件套齐）**：恢复三不变式 R-1/R-2/R-3 + 失败分类 X-1~X-17 + `pending_postings` 调用方台账（031 §12 的首次实现归属）+ C-19 后置动作同事务收口 + refund 侧对称有界收敛 + DLQ 可读可 replay + **移除** Resilience4j；决策 [ADR-0082](../adr/0082-failure-recovery-ownership-and-compensation.md)（**Accepted**，负责人 H-034-1~5 按推荐方案批准）。
- [035-observability-slo](stage-05-channel-and-finance-deepening/035-observability-slo/) —— 🟢 **已实现（2026-09-22，spec + Plan/Tasks/Acceptance 三件套齐）**：指标目录（runbook §5 跨 Feature 唯一登记处）+ 高基数政策 HC-1~HC-4 落地为 `MetricsCardinalityTest` 静态门禁 + `MetricsAssert` 运行期遍历断言 + 4 个 SLO 以 Recording Rule 表达（双窗 burn rate，目标 ≠ 实测，slo-report.md 固化）+ 告警扩容至 24 条五要素齐全（既有零删改）+ 密钥/完整报文不入日志（`/internal/channels/**` 路径排除 + uri 归一化，不推翻 ADR-0027）+ **否决引入 APM**（依赖清单变化 = 0）；决策 [ADR-0083](../adr/0083-observability-baseline-slo-and-cardinality.md)（**Accepted，2026-09-21 负责人按 spec 推荐方案批准 H-035-1~4**）。
- [036-channel-plugin-microkernel](stage-05-channel-and-finance-deepening/036-channel-plugin-microkernel/) —— 🟢 **已实现（代码 2026-09-23 合入，Spec 四件套 2026-09-25 回溯补齐）**：Channel 域「微内核 + 插件化」——内核 `application/channel/spi/`（`ChannelPlugin` / `ChannelPluginFactory` / `ChannelPluginDescriptor` / `ParsedCallback` / `ChannelCallbackEnvelope` / `AbstractChannelPlugin` 模板方法，入口 `final`）+ 通用回调端点 `/internal/channels/{channelCode}/callback`（报文翻译由插件承担）+ Stripe 沙箱插件五件套 + ArchUnit 两条门禁 + 账本 STRIPE 渠道账户（id 15/16）。⚠️ **回溯性 Spec**：代码先行（`3b9ca41` / `b73f960`）、文档后补，spec 正文按代码实证写。遗留 5 条技术债分别由 037/038/039 承接。
- [037-channel-gateway-boundary](stage-05-channel-and-finance-deepening/037-channel-gateway-boundary/) —— 🟢 **已实现（2026-09-25 合入 master `23dae45`）**：渠道网关做成「进程内微服务」——`ChannelGateway` 门面收口 6 处 `resolve()` + 渠道自建 `channelNo`（`BusinessNoType.CHANNEL("CH")`）+ `payment_attempts.channel_no`（`NOT NULL` + UNIQUE，含持久层映射）+ common-dto 15 个契约 record + 回调分层（`PaymentNotifyPort` 定义权归 Payment）+ 删除 `AlipayNotifyController` 专属端点（**破坏性变更**，部署侧 `PAYMENT_CHANNEL_NOTIFY_URL` 四处已同步）。⚠️ 其**前身 WIP 分支已删除**（持久层漏映射 `channel_no` 导致禁止合入），恢复锚点为本地 tag `archive/037-wip-t1t3`。
- [038-payment-service-package-boundary](stage-05-channel-and-finance-deepening/038-payment-service-package-boundary/) —— 🟢 **已实现（2026-09-25，spec + Plan/Tasks/Acceptance 四件套齐）**：payment-service 一级包由「payment / refund / posting」收敛为「**payment（资金动作域）/ channelgateway（渠道网关域）/ posting（跨切面）**」——消灭 `com.payment.refund` 顶层包（32 主 + 10 测试迁入 `com.payment.payment.**`）+ 40 个渠道件 100% 收拢 `com.payment.channelgateway.**` + 退款内部端点收口为 `/internal/payments/refunds/**`（⚠️ **破坏性变更**，6 文件 8 处调用方已同步）；`ServiceBoundaryTest` 新增 4 条包级门禁（含 1 条**覆盖修补**——渠道件搬出 `com.payment.payment..` 后会脱离既有跨服务门禁，且全部带阳性对照防空转），既有 4 处反向依赖**逐条 FQN 白名单化**为技术债 TD-4 / TD-5（收口归 037，**未静默放宽规则**）。**本 Feature 未新增 ADR**（纯结构重构，行为零变化）。
- [039-wechat-pay-channel-plugin](stage-05-channel-and-finance-deepening/039-wechat-pay-channel-plugin/) —— 🟢 **已实现（2026-09-25 合入 master `ca37d3b`）**：微信支付渠道插件（与 Stripe 同构的六件套：Plugin / Factory / Gateway / SdkGateway / Properties / Signer）+ SDK `com.github.wechatpay-apiv3:wechatpay-java:0.2.17` + V3 签名金标准 + 回调验签解密 + 本地仿真桩。⚠️ **微信沙箱实测不可用**（V3 沙箱 404 / V2 沙箱仅支持付款码支付 / V3 正式需真实证书），故以「签名金标准单测 + 回调往返单测 + 本地仿真桩 + 活栈双发对照」替代联调；密钥全走 `PAYMENT_WECHAT_*` env；旧 `WechatChannelAdapter` 已删（避免同 `channelCode` 双注册）。**本 Feature 未新增 ADR**。
- [040-payment-api-surface-consolidation](stage-05-channel-and-finance-deepening/040-payment-api-surface-consolidation/) —— 🔵 **Draft（2026-09-26 立项，待实现）**：payment-service API 面收口——① 查单支持 `paymentNo` / `transactionId` 双单号（交叉校验，两个都传须指向同一实体；**删除违反 ADR-0063 的数值 id 分支**）；② 新增通用退款入口 `POST /internal/payments/refunds`（暴露既有 `RefundApplicationService.createRefund`，当前「应用层有、HTTP 层没有」）；③ 删除两个零调用端点 `query-amount` / `refund-attempt`（⚠️ **保留 `PaymentRefundService` 类**，被 `LocalPaymentRefundGateway` 依赖）；④ 运维端点 `GET /internal/payments/unknown` 加开关（**默认开启**，是 runbook 与 5 条告警规则的排障入口）；⑤ **否决**把状态收敛塞进 GET（安全方法不得有副作用），改用响应只读提示字段 `resolvable` / `suggestedAction`。
- [041-payment-service-governance](stage-05-channel-and-finance-deepening/041-payment-service-governance/) —— 🔵 **Draft（2026-09-26，待负责人审阅）**：payment-service 全域重构治理——Payment/Channel 对称四层、`ChannelGateway` 与 `PaymentResultPort` 双端口边界、渠道插件按供应方归档、一次性替换仓内 HTTP API、仅开发/测试环境清库重建 Schema、ArchUnit 强制边界与完整资金验收。实施前必须新增并批准对应 ADR；当前四件套是唯一执行依据。
- **[design-summary.md](stage-05-channel-and-finance-deepening/design-summary.md)** —— 🟡 **031~035 跨 Feature 设计收口（2026-09-21）**：统一术语 / 依赖图（含对 stage-design §9.2 的 4 处修正）/ 事实源与幂等键总表 / 恢复职责划分 / 观测与测试边界 / 禁止耦合清单 P-1~P-9 / 实现顺序六道门 / 待人类裁决汇总 / 本轮明确未做的事。

> ⚠️ **状态说明（2026-09-22 统一为 8 态生命周期）**：`030`~`035` **全部为 `Implemented`**（`030` 2026-09-20 合入 master；`031`~`034` 2026-09-21；`035` 2026-09-22；ADR-0075/0076/0077~0079/0080/0081/0082/0083 均 🟢 Accepted）——**stage-05 六个 Feature 全部落地，合计 27 项人类裁决全收口**。
> `stage-design.md` / `design-review.md` / `design-summary.md` 均为 **Proposed**（见各自头部 `Status`），**不是** L0 当前系统事实源、**不产生**任何已生效决策。
> 各 Feature 的正式状态以各自 `spec.md` 头部为准，本索引 MUST 与之保持一致（[documentation-governance.md](../standards/documentation-governance.md) §5.3）。
