# PaymentArch 项目 Roadmap

**版本**：0.1

**日期**：2026-08-26

**当前原则**：以业务能力为 Feature 边界；服务可以独立部署，但当前可以运行在同一台服务器和同一个物理数据库上。跨服务统一使用同步 HTTP/RPC，数据库按服务使用独立 Schema。

## Current Status

- **当前阶段**：主链 MVP 已交付——`001-core-business-model` 已通过验收；端到端 merchant→catalog→order→payment→fulfillment→entitlement 可跑通；`004-ledger` / `005-refund` / `006-reconciliation` / `007-settlement` 均已落地并接入指标与记账（详见 `docs/architecture/systems/`）。
- **已实现 Feature**：`001-core-business-model`（验收通过）、`003-payment-reliability`（验收通过）、`004-ledger`（前置实现，ADR-0008~0011 Accepted）、`005-refund`（ADR-0016 ⛔ Rejected 已回退、ADR-0017~0018 Accepted）、`006-reconciliation`（ADR-0019~0021 Accepted）、`007-settlement`（ADR-0022~0023，按最简单实现落地）、`009-risk-security`（按 2026-08-30 裁决降级）、`010-distributed-evolution`（ADR-0029~0033 Accepted / 0031 不使用 MQ）、**`011-demo-showcase`（ADR-0048~0051 Accepted：新增 `mock-channel-web` 演示组件 + payUrl 跳转链路 + 对账演示账单 + demo 脚本；验签回落 ADR-0025 空实现，ADR-0052 ⛔ Not Implemented）**。`mvn test` 全量通过。
- **当前 Feature**：`011-demo-showcase`（Roadmap 下一阶段规划之首，见 `next-stage-design.md` §1）——**已交付并通过构建门禁**：新增 `mock-channel-web` 演示组件（端口 8091，演示用、不进服务边界）、payment 接入 payUrl 跳转、Mock 渠道场景配置化（ADR-0049）、对账演示账单生成（ADR-0050）、`demo/` 四场景脚本（ADR-0051）。`mvn -o clean verify -fae` 16 reactor 条目全绿，`architecture-tests` 边界门禁通过。
- **⚠️ 已知偏离（SOP 偏离，提交负责人复盘）**：working tree 同时含 **`013-inventory-reservation` / `014-seckill-and-cache`** 的实质性实现（catalog `Stock*` 聚合 + 三段式库存、order `OrderTimeoutScheduler` Redis ZSet 时间轮 + `SeckillResult` + 限流 + 幂等 + Lua）。该代码**超前 roadmap 顺序（011→012→013→014）、缺 spec/plan/tasks/acceptance 与 ADR-0041~0046、014 的 Redis 引入未经 roadmap §7「压测基线→论证引入」闸门**。决策见 **ADR-0053**：保留代码（编译+测试通过、与 011 在 `order-service` 纠缠不可干净拆分），spec/ADR 补写列为 TODO，待负责人复盘后走 Spec Kit 流程收口。
- **Feature 状态**：001/003/004/005/006/007/009/010/011 均有完整 Spec/Plan/Tasks/Acceptance 产物且已代码实现；**013/014 为「无 spec 的 WIP」**（仅实现 + 单测，缺 acceptance 与压测断言，见 ADR-0053）；可观测埋点（metrics + 资金审计 + traceId 透传）已落地。
- **当前能力**：`mvn -o verify -fae` 全量 BUILD SUCCESS（15 个 Maven 子模块：3 common + 10 服务 + `mock-channel-web` + `architecture-tests` + root，共 **16 个 reactor 条目**，含 `architecture-tests`）；各服务暴露 `/actuator/health`、`/actuator/prometheus` 与 Swagger UI；支付/退款/结算均已接入 ledger 复式记账。
- **ADR 状态（2026-08-30 负责人已裁决，2026-08-31 全部落定）**：
  - ✅ **Accepted**：`0004`（0008~0011）、`0005`（0012~0015）、`0007`（0019~0021 对账）、`0010`（0029/0030/0032/0033 保持现状）、0006 的 `0017` / `0018`、0009 的 `0024`（实现=预留空函数）/ `0025`（实现=预留空函数）/ `0026`（明文 env）。
  - ❌ **Rejected**：`0016`（部分退款不做，代码已回退）。
  - ⛔ **Not Implemented（不做 / 代码已删除或清理）**：`0027`（脱敏）、`0028`（风控）、`0031`（不使用 MQ）、`0034`~`0037`（出入站鉴权令牌）。
  - 裁决总表见 `docs/adr/README.md` 与各 ADR 文档头部；裁剪落地形态见 `docs/architecture/technical-solution.md` §2.4。
- **当前阻塞**：无 ADR 阻塞。已知遗留风险：① N1（对账事实无商户维度，可能跨商户串账）已在 ADR-0023 记录，待单独立项；② 鉴权/验签空实现带来的部署风险（payment-service 不得暴露公网、`/internal/**` 依赖网络层隔离），见 §2.4 与 `009-risk-security/spec.md` §6；③ **013/014 超前落地偏离**（超前顺序、缺 spec/ADR-0041~0046、014 Redis 未经论证闸门），见 **ADR-0053**，待负责人复盘决定是否补 spec 或回退。
- **安全能力最终形态（2026-08-31）**：鉴权与验签**只保留接入点、校验为空实现**（`InternalServiceAuthInterceptor#verifyServiceToken` / `ChannelCallbackSignatureFilter#verifySignature` 恒放行）；脱敏、风控、出站令牌**代码已删除**。`009-risk-security` T013（出站令牌闭环）**整条已回退**，不再存在「开启顺序」，`docs/operations/runbook.md` §4 同步修订。

## Next Feature

- **Roadmap 主链已走完 Phase 0~10**，无预设下一阶段（Phase 10 本身即为长期演进终点，后续按新的 Roadmap 版本调整）。
- **后续立项必须走闸门**：任何服务拆分、数据库实例拆分或引入分布式基础设施（MQ / 容器编排 / Service Mesh），MUST 先填 `docs/operations/split-proposal-template.md`（问题-证据 / 收益 / 成本 / 回滚 四段必填）并通过评审；运行手册 `docs/operations/runbook.md` 同步更新。
- **注意**：原 Roadmap 顺序与 Feature 编号已解耦（遵循 `003-payment-reliability` / `004-ledger` 既定约定：spec 物理目录采用顺序编号 `009-risk-security` / `010-distributed-evolution`）。Phase 9 四项范围（服务和操作权限、渠道回调签名和来源校验、敏感数据脱敏和密钥管理、最小风控规则）已在 `009-risk-security` 内一次性覆盖；Phase 10 四项范围在 `010-distributed-evolution` 内以「门禁 + 判据」形式覆盖。

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

**最终落地情况（2026-08-31，按 2026-08-30 负责人裁决）**：四项范围中，**两项降级为「接入点保留 + 空实现」、两项直接不做**。产物见 `docs/specs/009-risk-security/`，决策见 `docs/adr/0009-risk-security-decisions.md`（ADR-0024~0028）与 `docs/adr/0011-internal-token-decisions.md`（ADR-0034~0037）。

| Phase 9 范围 | 裁决 | 最终落地 |
| --- | --- | --- |
| 服务和操作权限 | ⭕ 预留空函数（ADR-0024 / 0035） | `InternalServiceAuthInterceptor` 仍挂 `/internal/**`，`verifyServiceToken()` **恒放行**；**不推广**到其余 7 个服务；出站令牌链路**已删除**（ADR-0034） |
| 渠道回调签名和来源校验 | ⭕ 预留空函数（ADR-0025） | `ChannelCallbackSignatureFilter` 骨架保留（路径匹配 / body 读 / 可重复读包装 / 403 分支），`verifySignature()` **恒通过**；`SignatureVerifier`（HMAC-SHA256 + 防重放）作为 common-core 工具类保留，8 单测覆盖 |
| 敏感数据脱敏和密钥管理 | ⛔ 不做（ADR-0027）／✅ 明文（ADR-0026） | `SensitiveDataMasker` **已删除**；密钥仍约定 env 注入（当前生效的仅 `PAYMENT_ADMIN_TOKEN`） |
| 最小风控规则 | ⛔ 不做（ADR-0028） | `RiskCheckService` 与 `payment.risk.*` **已删除**，`PaymentApplicationService` 调用点已清理 |

> ~~**补充落地（2026-08-30，T013）**：出站内部服务令牌闭环……~~ ⛔ **整条已回退**（2026-08-31）：负责人裁决「出入站的鉴权令牌都先不做」，`InternalTokenRequestInterceptor` / `FeignInternalTokenAutoConfiguration` / `platform.security.*` / `payment.internal_auth_rejected` 埋点全部删除。决策见 `docs/adr/0011-internal-token-decisions.md`（ADR-0034~0037，均 ⛔ Not Implemented）。

### 不包含

不包含一开始就建设复杂风控平台或全量合规体系。**未做**：mTLS / OAuth2、密钥管理服务（Vault/KMS）、规则引擎、出站内部令牌（ADR-0034）、入站鉴权推广到 payment 以外的服务（ADR-0035）、双令牌平滑轮换（ADR-0036）、鉴权失败埋点告警（ADR-0037）、敏感数据脱敏（ADR-0027）、最小风控（ADR-0028）。

### 验收标准

原标准：**未授权请求被拒绝；伪造回调无法改变支付；敏感信息不进入日志；安全策略可审计。**

**达成情况（按裁决修订）**：

- ⭕ **未授权请求被拒绝** → 本期**不适用**：鉴权为空实现，未授权请求**会被放行**。测试以 `...IsAllowedWhileAuthIsStubbed` 显式断言此行为。
- ⭕ **伪造回调无法改变支付** → 本期**不适用**：验签为空实现，伪造回调**可翻转支付状态**。测试以 `...IsAllowedWhileSignatureVerificationIsStubbed` 显式断言此行为。
- ⛔ **敏感信息不进入日志** → **ADR-0027 不做**，无脱敏工具。
- ✅ **安全策略可审计** → 通过：所有裁决与空实现位置均在 ADR / spec / 代码 Javadoc 中留痕，无隐性占位。

**已知的部署前置条件**（负责人已接受）：payment-service **不得暴露到公网**、`/internal/**` 依赖安全组 / 服务网格做网络层隔离。接入真实渠道前必须先实现 `verifySignature` 与 `verifyServiceToken`。详见 `docs/specs/009-risk-security/acceptance.md`。

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

**落地情况（2026-08-30）**：前置条件为「至少一个真实业务瓶颈或隔离需求 + 负责人确认」，当前两者皆无，因此 `010-distributed-evolution` 的**最简实现是「不拆服务，先立门禁」**——产物见 `docs/specs/010-distributed-evolution/`，决策见 `docs/adr/0010-distributed-evolution-decisions.md`（ADR-0029~0033，Proposed 待确认）：

| Phase 10 范围 | 落地形态 |
| --- | --- |
| 部分服务独立数据库迁移 | **不迁**；只登记触发条件（容量 / 隔离 / 合规归属 / 可用性）与顺位建议（首推 `ledger`），见 ADR-0030 |
| 必要时评估跨服务异步消息 | **不引入**；登记引入判据（性能 / 解耦 / 削峰证据）与不可越过的一致性约束，见 ADR-0031；构建期规则禁止 MQ/JTA-XA 出现 |
| 服务独立扩缩容、发布和故障隔离 | 只立关键等级 T0~T3 分级表作为投入排序依据，不改任何部署形态，见 ADR-0032 |
| 云部署路径评估 | 纳入拆分提案模板的「成本」段，按提案走，不预设路径 |
| 服务边界测试 | 新增 `architecture-tests` 模块（ArchUnit）：服务零编译期耦合 / domain 框架无关 / 接入层不直达仓储 / 无 MQ-JTA，另含防空转门禁 |
| 运行手册 | `docs/operations/runbook.md`（10 服务端口、依赖、启动顺序、指标、故障处置、回滚） |
| 拆分提案模板 | `docs/operations/split-proposal-template.md`（问题-证据 / 收益 / 成本 / 回滚，四段必填） |

### 不包含

不因“看起来像微服务”而默认引入 Service Mesh、Kubernetes、CQRS 或 Event Sourcing。**本期未做**：任何真实的服务拆分、数据库实例拆分、容器化、MQ、Service Mesh。

### 验收标准

每次拆分都有问题、收益、成本和回滚方案；业务事实和契约向后兼容；服务边界测试和运行手册齐全。

**达成情况**：后两项已由 `010-distributed-evolution` 交付并通过验证；第一项（每**次**拆分都有方案）是持续性门禁——由 `split-proposal-template.md` 保证，尚无真实拆分发生，故未产生实际提案。

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
