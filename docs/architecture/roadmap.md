# PaymentArch 项目 Roadmap

**版本**：0.1

**日期**：2026-08-26

**当前原则**：以业务能力为 Feature 边界；服务可以独立部署，但当前可以运行在同一台服务器和同一个物理数据库上。跨服务统一使用同步 HTTP/RPC，数据库按服务使用独立 Schema。

## Current Status

- **当前阶段**：主链 MVP 已交付——`001-core-business-model` 已通过验收（`docs/specs/001-core-business-model/acceptance.md`），端到端 merchant→catalog→order→payment→fulfillment→entitlement 可跑通；退款/对账/结算服务已落地并接入指标（详见 `docs/architecture/systems/`）。
- **当前 Feature**：`003-payment-reliability` **已实现并通过验收**（`docs/specs/003-payment-reliability/acceptance.md`）：US1 超时→UNKNOWN、US2 主动查询收敛、US3 有限重试与耗尽、US5 可靠性指标与真实收敛时长均已落地，`mvn verify` 全量通过（payment-service 63 tests 全过）；US4 人工收敛按 ADR-0006 延后 Phase 9。实现期决策见 `docs/adr/0005-payment-reliability-impl-decisions.md`（ADR-0012~0015，Proposed 待确认）。+ 并行推进 `009 Observability Baseline`。
- **下一 Feature（已前置）**：`004-ledger` —— 由审计 D1（Constitution §II.3 与 Roadmap 延后 Ledger 的矛盾）驱动，负责人决策**前置实现 Ledger**，按 Spec Kit「文档先行」已产出 spec/plan/tasks/data-model/contracts/checklists/acceptance/quickstart，设计决策见 `docs/adr/0004-ledger-design-decisions.md`（ADR-0008~0011，待确认）。Constitution 已升至 v2.1.0 消除该矛盾。
- **Feature 状态**：001/**003** 有完整 Spec/Plan/Tasks/Acceptance 产物（003 已代码实现并验收）；004 有完整文档产物待实现；可观测埋点（metrics + 资金审计 + traceId 透传）已落地，可视化层进行中。
- **当前能力**：`mvnw verify` 通过；各服务暴露 `/actuator/health`、`/actuator/prometheus` 与 Swagger UI。
- **当前阻塞**：004-ledger 待 ADR-0008~0011 负责人确认后进入实现（按负责人指示，实现期未知决策按「最简方式」推进并补记 ADR 供决策）。

## Next Feature

- **下一个 Feature**：`004 Ledger`（**已前置**）——实现 `ledger-service` 复式记账，落地 Constitution §II.3「一切资金变动经 ledger-service」。
- **进入条件**：ADR-0008~0011 经负责人确认（Constitution §8 人类决策边界）；复用既有 003 可靠性与同步 RPC 底座。
- **为什么前置**：审计 D1 指出 Constitution §II.3（MUST 经 ledger-service）与 Roadmap（Ledger 延后 Phase 8）自相矛盾；负责人决策把 Ledger 前置，先「文档先行」消除矛盾，再实现资金账务底座，为退款/对账/结算提供更可信的事实来源。
- **注意**：原 Roadmap 顺序（003 Refund → 004 Reconciliation → ... → 006 Ledger）因本前置决策调整；后续 Feature 编号与阶段标签解耦（遵循 `003-payment-reliability` 既定约定：spec 物理目录采用顺序编号 `004-ledger`）。

## Feature Dependency Graph

```text
Phase 0 Foundation
        ↓
001 Core Business Model
        ↓
002 Payment Reliability
        ↓
003 Refund
        ↓
004 Reconciliation
        ↓
005 Settlement
        ↓
006 Ledger
        ↓
007 Risk / Security
        ↓
008 Distributed Evolution
```

可并行但不阻塞主链路的能力：

```text
001 Core Business Model ──┬── 009 Observability Baseline
                          └── 010 Delivery / CI-CD Baseline
```

009 和 010 可以在核心业务 Feature 的适当阶段并行，但不能取代主链路验收。

## Phase 0 — Foundation

### 目标

收口架构裁决、服务目录、端口、数据库 Schema 约定、Feature 路径和 Spec Kit 唯一开发入口，使项目具备可重复启动和继续开发的基础。

### 为什么现在做

当前已经有多个服务骨架，但文档、Plan 和源码曾经分别指向微服务和模块化单体。若不先收口，后续每个 Feature 都会产生目录和边界返工。

### 前置条件

已有根 POM、服务骨架和 Feature 001 文档；需要负责人确认总体架构方案。

### 包含的 Feature

- 当前架构基线和服务清单收口。
- Maven Wrapper、基础 CI、端口约定和单机运行说明。
- 独立 Schema 的命名和数据所有权约定。
- Spec Kit 路径和唯一开发入口收口。

### 不包含

不实现完整业务领域，不接入真实支付，不创建 Ledger，不引入 MQ、Kubernetes 或服务网格。

### 验收标准

- 所有文档对当前架构、Feature 路径和通信方式描述一致。
- 每个服务使用独立端口；单机可启动多个独立服务。
- 每个服务的 Schema 约定清楚，禁止跨服务直连数据。
- `mvnw validate` 或等价可重复构建入口可用。
- Spec Kit 可以创建并定位 `docs/specs/<feature>/`。

### 完成后获得的能力

项目负责人能明确知道当前架构、当前阶段和下一步；开发者可以在不猜测目录的情况下开始 Feature 实现。

### 进入下一阶段原因

方向和工具边界已经稳定，可以开始验证第一个真实业务闭环。

## Phase 1 — Commerce Core

### 目标

建立 Merchant、Product、SKU、Order、Transaction 的最小可运行能力，完成商品选择、价格快照、订单创建和订单查询。

### 为什么现在做

支付必须建立在明确的商业购买意图和订单金额之上，不能先做孤立的 Payment。

### 前置条件

Phase 0 完成；服务 RPC 和独立 Schema 约定有效。

### 包含的 Feature

- 商户最小资格和商品目录。
- SKU 可售性和价格快照。
- Order 与 Transaction 的 MVP 1:1 关系。
- 订单状态机和基本查询。

### 不包含

不包含 Payment、退款、权益、结算、复杂库存、促销、税费和多级商户。

### 验收标准

可创建可售 SKU 对应的订单；订单保存不可变价格快照；非法 SKU、金额和状态被拒绝；Order 与 Transaction 关系可查询。

### 完成后获得的能力

平台具备可被支付的真实商业订单。

### 进入下一阶段原因

有稳定的订单支付义务和可追溯金额，Payment 才有合法业务输入。

## Phase 2 — Payment Core

### 目标

实现 Payment、PaymentAttempt、Payment Channel Adapter 和 Mock Channel，完成支付意图、渠道调用、回调和 Payment 状态机。

### 为什么现在做

这是平台核心资金业务能力，也是 Feature 001 的主体。

### 前置条件

Phase 1 完成；Payment 状态机和幂等规则已确认。

### 包含的 Feature

- Payment 与 Transaction 的 MVP 1:1。
- Payment 与 PaymentAttempt 的 1:N。
- Channel Adapter + Mock Channel。
- 成功、失败和 UNKNOWN 支付结果。
- 同步 RPC 回调/查询边界。

### 不包含

不包含真实支付渠道、真实资金记账、Ledger、复杂路由、风控和多币种清分。

### 验收标准

支付意图可创建；重复请求幂等；渠道回调可重复处理；超时进入 UNKNOWN；查询或权威回调可收敛；Payment 成功不会被迟到失败覆盖。

### 完成后获得的能力

平台可以验证完整的支付主流程，但仍是模拟资金业务事实。

### 进入下一阶段原因

支付状态和渠道边界稳定，才能围绕失败、重试和未知状态做可靠性建设。

## Phase 3 — Payment Reliability

### 目标

强化支付超时、UNKNOWN 持续时间、重复回调、状态查询、重试和人工收敛能力。

### 为什么现在做

支付成功路径容易实现，真正决定正确性的是真实异常和不确定结果处理。

### 前置条件

Phase 2 的支付主流程通过；已有 Mock Channel 异常场景。

### 包含的 Feature

- UNKNOWN 查询和回调收敛。
- 重复、乱序、延迟回调。
- 有限重试和重试耗尽处理。
- 支付业务指标和审计信息。

### 不包含

不包含生产级渠道 SLA、多活、复杂风控和自动资金补偿。

### 验收标准

所有异常场景可重放；UNKNOWN 不被误判；同一成功事实只触发一次后续处理；指标能反映数量和持续时间。

### 完成后获得的能力

支付流程具备可解释、可恢复的异常处理能力。

### 进入下一阶段原因

支付结果可靠后，履约和权益才有可信的触发前提。

## Phase 4 — Fulfillment & Entitlement

### 目标

通过同步 RPC 在 Payment 成功后请求履约，履约完成后请求权益授予，保持各服务独立状态。

### 为什么现在做

支付成功不等于权益发放，必须验证商业交付链路的独立生命周期。

### 前置条件

Phase 2/3 完成；Fulfillment 和 Entitlement 服务可独立启动。

### 包含的 Feature

- 支付成功后的履约 RPC。
- 履约状态和失败恢复。
- 权益授予、查询、使用和失败处理。
- 重复 RPC 的幂等。

### 不包含

不包含复杂仓储物流、权益商城、订阅续费和完整退款回收政策。

### 验收标准

Payment 成功可以触发履约；履约失败不回写 Payment；履约完成后权益可用；重复调用不重复履约或授予。

### 完成后获得的能力

用户可以从已支付订单获得可查询、可消费的权益。

### 进入下一阶段原因

有明确的交付和消费权事实后，退款才可以判断后续影响。

## Phase 5 — Refund

### 目标

实现部分/全部退款、退款幂等、退款状态和退款后的履约/权益处理。

### 为什么现在做

退款同时影响 Payment、Order、Fulfillment 和 Entitlement，必须在主链路稳定后实现。

### 前置条件

Phase 2-4 完成；可退款金额和退款政策获得确认。

### 包含的 Feature

- 退款资格和可退款金额。
- Payment 内部退款尝试。
- 退款成功、失败和 UNKNOWN。
- 退款后的 Fulfillment/Entitlement RPC。

### 不包含

不包含复杂退款审批、已消费权益的统一回收政策、真实出款和 Ledger 冲正。

### 验收标准

部分/全部退款可追踪；累计金额不超限；重复退款幂等；未知退款不重复执行；后处理失败可独立追踪。

### 完成后获得的能力

平台可以安全验证退款业务闭环，但仍不执行真实资金退款。

### 进入下一阶段原因

已有完整支付和退款事实，才能进行有意义的渠道账单核对。

## Phase 6 — Reconciliation

### 目标

使用 Mock/预置渠道账单核对 Payment/Refund 事实，识别并处理差异。

### 为什么现在做

对账是发现支付未知、漏单、重复和金额差异的关键控制点。

### 前置条件

支付和退款事实稳定；渠道账单格式在 MVP 范围内明确。

### 包含的 Feature

- Mock/预置渠道账单导入。
- 一致、金额差异、状态差异、平台独有和渠道独有记录。
- 差异记录、处理状态和人工处理依据。

### 不包含

不包含真实渠道账单接入、自动调账、复杂会计处理和真实资金修正。

### 验收标准

差异可重复识别、可查询、可处理；原始 Payment/Refund 事实不被静默改写。

### 完成后获得的能力

平台可以验证资金业务事实与渠道事实的一致性。

### 进入下一阶段原因

只有已确认且差异可解释的数据，才可以进入结算计算。

## Phase 7 — Settlement

### 目标

基于已确认 Payment/Refund 事实生成商户周期结算批次和模拟结算结果。

### 为什么现在做

结算依赖支付、退款和对账结果，提前实现会制造大量不可靠假设。

### 前置条件

Phase 6 完成；商户结算资格和最小净额规则确认。

### 包含的 Feature

- 商户周期结算资格。
- 收入、退款和调整项的最小净额计算。
- 结算批次幂等和状态查询。
- UNKNOWN 和失败结果。

### 不包含

不真实出款、不接真实银行、不实现 Ledger、不实现多币种清分、税费和复杂分账。

### 验收标准

未确认事实不能结算；同一商户周期不重复生成批次；未知结果可查询；整个阶段没有真实出款。

### 完成后获得的能力

平台具备可验证的基础结算业务模型，但不具备资金执行能力。

### 进入下一阶段原因

结算边界和资金业务事实已稳定，才值得建立正式账务模型。

## Phase 8 — Ledger

### 目标

建立可追溯的复式账务事实、科目和记账规则。

### 为什么现在做

真实资金动作必须有 Ledger 支撑，不能用 Payment、Refund 或 Settlement 状态替代账务事实。

### 前置条件

前面各阶段的支付、退款、对账和结算业务事实已经稳定；Ledger 方案获得负责人确认。

### 包含的 Feature

- 科目、分录、借贷平衡和业务引用。
- Payment、Refund、Settlement 与 Ledger 的记账边界。
- 记账幂等和审计追踪。

### 不包含

不包含复杂会计准则、多币种清分和全面财务总账。

### 验收标准

每个真实资金事实都能追溯到平衡分录；重复记账不产生重复分录；无法确认的外部结果不能直接记账。

### 完成后获得的能力

平台首次具备正式资金账务基础。

### 进入下一阶段原因

账务事实稳定后，才能安全评估真实渠道、风控和生产安全要求。

## Phase 9 — Risk / Security

### 目标

建立与业务风险匹配的认证、授权、签名校验、敏感数据保护和支付风险控制。

### 为什么现在做

生产化需要安全策略，但安全策略必须建立在稳定业务边界和正式账务模型上。

### 前置条件

核心领域和 Ledger 稳定；安全策略由负责人确认。

### 包含的 Feature

- 服务和操作权限。
- 渠道回调签名和来源校验。
- 敏感数据脱敏和密钥管理。
- 与支付流程匹配的最小风控规则。

### 不包含

不包含一开始就建设复杂风控平台或全量合规体系。

### 验收标准

未授权请求被拒绝；伪造回调无法改变支付；敏感信息不进入日志；安全策略可审计。

### 完成后获得的能力

平台具备进入受控环境验证的安全基础。

### 进入下一阶段原因

安全和账务基线具备后，才有条件评估部分服务独立扩展。

## Phase 10 — Distributed Evolution

### 目标

根据实际负载、故障隔离和团队 ownership，有证据地演进服务和数据库部署。

### 为什么现在做

微服务已经是当前服务形态，但更复杂的独立数据库、消息基础设施和服务治理不应无理由提前引入。

### 前置条件

至少一个真实业务瓶颈或隔离需求；拆分方案和运维成本获得负责人确认。

### 包含的 Feature

- 部分服务独立数据库迁移。
- 必要时评估跨服务异步消息。
- 服务独立扩缩容、发布和故障隔离。
- 云部署路径评估。

### 不包含

不因“看起来像微服务”而默认引入 Service Mesh、Kubernetes、CQRS 或 Event Sourcing。

### 验收标准

每次拆分都有问题、收益、成本和回滚方案；业务事实和契约向后兼容；服务边界测试和运行手册齐全。

### 完成后获得的能力

平台具备按证据演进的分布式架构，而不是预先堆叠基础设施。

### 进入下一阶段原因

该阶段本身是长期演进终点，不预设下一阶段；后续按新的 Roadmap 版本调整。

## 每个 Feature 完成后的 SOP

1. 使用 Spec Kit 创建或更新 `docs/specs/<feature>/spec.md`。
2. 运行 `/speckit-clarify`，只解决会改变范围、边界、状态机或验收的关键歧义。
3. 运行 `/speckit-plan`，检查是否符合当前总体架构方案和 Roadmap 阶段。
4. 负责人审阅并确认 Spec、Plan 和涉及的人类决策边界。
5. 运行 `/speckit-tasks`，确认每个任务有故事标签、依赖、路径和验收方式。
6. 只按任务执行 `/speckit-implement`，不直接绕过任务清单改实现。
7. 运行对应测试、`mvnw verify`、quickstart 和必要的服务联调。
8. 运行 `/review`；涉及支付、退款、对账、结算或 Ledger 时运行 `/payment-review`。
9. 更新本 Roadmap 的 Current Status、Next Feature 和 Feature 状态。
10. 将未完成内容转为后续 Feature，不把临时决定悄悄留在代码中。
