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

> 用户支付限额 / 渠道路由 / Redis 事务消息 / 渠道契约染色与支付宝沙箱。

- [027-user-payment-limit](stage-04-new-directions/027-user-payment-limit/)
- [028-channel-routing](stage-04-new-directions/028-channel-routing/)
- [029-redis-transactional-mq](stage-04-new-directions/029-redis-transactional-mq/)
- [030-channel-contract-dye-alipay-sandbox](stage-04-new-directions/030-channel-contract-dye-alipay-sandbox/)