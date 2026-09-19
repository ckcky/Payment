# Specs 索引（按阶段分组）

> 本目录按**阶段（Stage）**组织 Feature 文档。阶段 = 以目标为导向的一组相关 Feature；每个阶段一个 slug 子目录。
> 各 Feature 文档遵循 Spec Kit 产物：`spec.md` / `plan.md` / `tasks.md` / `acceptance.md`。

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

## stage-05-channel-and-finance-deepening — 渠道与资金纵深（🟡 提案中，待负责人确认）

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

- [030-channel-contract-sandbox-callback](stage-05-channel-and-finance-deepening/030-channel-contract-sandbox-callback/) —— 🟢 **Spec v1.1 + Plan 三件已就绪，待开工**；ADR-0075 / ADR-0076 已转 Accepted。⚠️ 本编号占用 `030`（原 stage-04 的 `030-channel-contract-dye-alipay-sandbox` 已于 2026-09-19 删除，设计被本 Feature 全量吸收，见 [spec §0.3](stage-05-channel-and-finance-deepening/030-channel-contract-sandbox-callback/spec.md)）。

> ⚠️ **状态说明**：该阶段**其余 Feature 仍为提案**，尚未分配 Feature 编号、未同步 `roadmap.md`；阶段命名与
> **后续 Feature 编号重排**（是否顺移一位）属宪法 §Governance 人类决策边界，须负责人确认后本索引与 roadmap 才视为生效。
> `stage-design.md` / `design-review.md` 均为 **Draft / 提案**，**不是** L0 当前系统事实源，**不产生**任何已生效决策。