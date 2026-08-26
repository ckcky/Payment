# Implementation Plan: Commerce & Payment Platform MVP

**Branch**: `001-core-business-model` | **Date**: 2026-08-26 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `docs/specs/001-core-business-model/spec.md` plus the confirmed modular-monolith MVP constraints.

## Summary

实现一个可运行的 Commerce & Payment Platform MVP，用多个独立微服务验证订单、交易、支付、渠道抽象、支付回调、幂等、UNKNOWN 收敛、履约、权益、退款、基础对账、基础结算和可观测性。服务可以部署在同一台服务器上，但每个服务独立监听端口并保持独立边界。

本 Plan 的关键取舍：Order 1:1 Transaction，Transaction 1:1 Payment，Payment 1:N PaymentAttempt；Payment Channel 是 Payment 模块内部的渠道适配能力；跨服务使用同步 HTTP/RPC；Ledger 暂不实现。所有支付、退款和结算均为可验证的业务模拟，不能宣称支持真实资金生产流转。

## Technical Context

**语言/版本**：Java 21 LTS

**主要依赖**：Spring Boot 3.x、Spring Cloud、MyBatis/MyBatis-Plus、Micrometer。具体版本统一由 Maven 父工程管理；本 MVP 使用 Spring Cloud 支持独立服务调用和服务配置，但不引入 MQ。

**存储**：每个服务拥有独立 Schema 和数据访问边界；单机部署阶段允许多个 Schema 位于同一个物理数据库中。具体表结构属于实现阶段，本 Plan 不定义。

**测试**：JUnit 5、Mockito、AssertJ；使用 Testcontainers 进行集成测试；为接口和业务事件编写契约测试与场景测试。

**目标平台**：本地 JVM 开发、Docker Compose 和单机部署。

**项目类型**：多服务 Web 平台（各服务独立部署）。

**性能目标**：面向 MVP 规模的本地部署；在实现任务中定义并验证同步命令的用户可感知响应目标，不针对生产规模做性能优化。

**约束**：不引入微服务集群治理、Kubernetes、Service Mesh、分布式事务框架、MQ、CQRS、Event Sourcing、复杂 Ledger、多币种清分、复杂分账、多级商户模型或复杂风控系统。

**规模/范围**：一个可运行 MVP、一个支付渠道适配器（Mock Channel）、初始业务场景中的一种币种、一个商户结算路径，以及 US1-US3 的核心场景。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

### Pre-Phase Gate: PASS WITH EXPLICIT LIMITS

- **面向生产实践**：通过。MVP 包含测试、文档、可观测性和可重复的本地验证。
- **资金正确性**：有限通过。本阶段不包含真实资金流转或 Ledger 实现。Payment、Refund、Settlement 只表示模拟业务事实；未来任何真实资金路径都必须先依赖 Ledger。
- **领域边界**：通过。Payment/Channel、Order/Transaction 和 Payment/Attempt 保持独立概念，并遵守前文约定的基数。
- **架构**：通过。当前采用多个独立微服务；单机部署只改变物理部署位置，不改变服务边界。
- **一致性**：通过。必须具备服务内本地事务、显式状态机、幂等、同步 RPC 超时/重试、状态查询和 UNKNOWN 处理。
- **实现前需要人工确认**：任何领域边界、支付状态机、真实资金账本、破坏性数据库迁移、安全策略或生产部署策略的变更。

## Project Structure

### Documentation (this feature)

```text
docs/specs/001-core-business-model/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
└── tasks.md
```

### Source Code (repository root)

```text
pom.xml
gateway/                         # 当前不创建
merchant-service/
catalog-service/
order-service/                   # Order + Transaction
payment-service/                 # Payment + Attempt + Channel Adapter
refund-service/
fulfillment-service/
entitlement-service/
reconciliation-service/
settlement-service/
common/
├── common-core/
├── common-dto/
└── common-mybatis/
docs/
└── deployment/
  └── docker-compose.yml
```

**结构决策**：采用多个独立微服务。每个服务拥有自己的领域、应用、接口、基础设施、测试边界和独立端口；单机部署不改变服务边界。单个物理数据库可承载多个服务 Schema，但每个服务只能访问自己的 Schema。跨服务通过公开 HTTP/RPC 用例交互，不直接访问他服务数据。

## 1. Architecture Overview

运行时是多个独立可启动的微服务，按限界上下文划分。服务可以部署在同一台服务器上，但必须监听不同端口。跨服务统一使用同步 HTTP/RPC；服务内部可以记录事件，但不跨服务发布异步事件。

## 2. Module Boundaries

| 模块 | 当前职责 | MVP 状态 |
|---|---|---|
| Merchant | 商户和结算资格基础信息 | 最小只读/初始化能力 |
| Catalog | Product、SKU、价格和交付定义 | 必须实现 |
| Order | Order、Order Item、Price Snapshot | 必须实现 |
| Transaction | 订单支付义务及交易状态 | 必须实现，Order 1:1 |
| Payment | Payment、PaymentAttempt、状态机、幂等、回调 | 必须实现 |
| Payment Channel | Payment 内部的渠道抽象、Channel Adapter 与 Mock Channel | 必须实现，不独立部署 |
| Refund | 退款申请和支付退款编排 | 必须实现最小闭环 |
| Fulfillment | 支付成功后的交付任务 | 必须实现最小 RPC 闭环 |
| Entitlement | 权益授予、查询和可用状态 | 必须实现最小闭环 |
| Reconciliation | 平台支付/退款事实与渠道记录比对 | 必须实现基础对账 |
| Settlement | 基于已确认事实形成商户结算批次 | 必须实现基础结算 |
| Ledger | 复式账本 | 本阶段不实现，仅保留后续 Feature |

## 3. Core Domain Model

采用已确认的基数：`Order 1:1 Transaction`、`Transaction 1:1 Payment`、`Payment 1:N PaymentAttempt`。Payment 保存平台支付意图和最终平台状态，PaymentAttempt 保存每次渠道交互事实；Channel 只提供抽象能力和渠道引用。Fulfillment 与 Entitlement 各自维护状态，不把支付成功等价为权益已发放。

## 4. Business Flow

主链路：Catalog/SKU 可售 → order-service 创建价格快照和订单 → order-service 创建 Transaction → payment-service 创建 Payment → PaymentAttempt → Mock Channel → 明确支付结果 → payment-service 通过 RPC 调用 fulfillment-service → fulfillment-service 通过 RPC 调用 entitlement-service。

未知链路：渠道超时/不完整结果 → payment-service 将 Payment 置为 UNKNOWN → 查询或回调 RPC → 明确成功/失败 → 仅在状态允许时通过 RPC 触发后续处理。

退款链路：refund-service 受理 Refund → 校验可退金额 → 通过 RPC 请求 payment-service 执行退款尝试 → 成功/未知 → 通过 RPC 请求 Fulfillment/Entitlement 后处理 → reconciliation-service。

资金闭环：通过 RPC 获取已确认 Payment/Refund 事实 → reconciliation-service 使用 Mock/预置渠道账单对账 → 差异处理 → settlement-service 判断资格 → 生成 Settlement 批次和模拟结果。

## 5. Sync / Async Boundary

跨服务全部使用同步 HTTP/RPC：order-service 调用 catalog-service 校验 SKU，order-service 调用 payment-service 创建支付意图，payment-service 调用 fulfillment-service，fulfillment-service 调用 entitlement-service，refund-service 调用 payment-service/fulfillment-service/entitlement-service，reconciliation-service 调用 Payment/Refund 查询，settlement-service 调用 Merchant/Reconciliation 查询。服务内部可以使用事件记录本地状态，但不跨服务发布异步事件；调用方不得把所有后置动作伪装成本地事务。

## 6. Persistence Strategy

每个服务拥有自己的持久化模型、Schema 和数据访问边界；单机阶段允许多个 Schema 位于同一物理数据库。服务只能访问自己的 Schema，跨服务数据读取必须调用对方公开 RPC。MVP 只设计持久化职责、唯一性和历史追踪，不在本 Plan 创建或冻结数据库表；RPC 请求、幂等结果、外部渠道引用和状态变化必须可追踪。

## 7. Event Strategy

服务内部可以使用 `PaymentSucceeded`、`PaymentUnknown` 等领域事实记录状态变化，但 MVP 不建立跨服务事件契约、不引入 Kafka、RabbitMQ 或其他 MQ。跨服务后置流程通过公开同步 HTTP/RPC 用例触发，并返回可查询的业务状态；未来若确有异步需求，再单独建立消息契约和 ADR。

## 8. Idempotency Strategy

支付、退款、结算入口必须有调用方幂等键；渠道回调以渠道交易引用和尝试身份去重；跨服务 RPC 以业务幂等键和请求身份去重。重复请求返回原结果或当前状态，不创建第二个资金动作、履约任务或权益授予。未知状态下不得盲目重发无法确认是否已执行的外部资金请求。

## 9. State Machine Strategy

状态转换集中于各服务 domain 层，禁止 Controller 或 RPC 适配器直接写状态。Payment 至少支持待支付、处理中、成功、失败、UNKNOWN、已关闭；退款和履约/权益使用 Spec 中定义的独立状态。每个非法跳转、终态重复通知、超时 RPC 和 UNKNOWN 收敛路径都必须有测试。

## 10. Error Handling

错误分为业务拒绝、外部失败、未知结果和内部系统失败。业务拒绝返回可解释原因；外部超时进入 UNKNOWN；可证明幂等的操作按有限次数退避重试；重试耗尽进入可查询、可补偿或人工处理状态。任何后置失败不得删除或反写前序成功事实。

## 11. Observability

所有核心用例记录关联标识和状态变化。除请求量、响应延迟、错误率等技术指标外，必须定义并验证以下支付领域业务指标：支付成功数/率、支付失败数/率、Payment UNKNOWN 数量、UNKNOWN 持续时间分布、重复回调数量、退款成功数/率、退款失败数/率、履约失败数/率、权益发放失败数/率、对账差异数量/金额、结算失败数/率。日志结构化并脱敏，资金模拟动作记录审计字段；Trace 只保留关联规则，不提前引入重量级追踪平台。

## 12. Testing Strategy

- 领域测试：金额边界、状态转换、可退款金额、权益授予和结算资格。
- 应用测试：订单→支付、PaymentSucceeded→Fulfillment、退款和对账编排。
- 契约测试：渠道抽象、回调输入、核心查询/命令和事件载荷。
- 集成测试：重复请求、重复回调、UNKNOWN 查询收敛、履约 RPC、权益失败恢复、基础对账和结算。
- 场景测试：覆盖 Spec 的三条用户故事和 quickstart 中的完整演示路径。

## 13. Deployment Strategy

支持本地分别启动多个 JVM 服务、Docker Compose 和单机部署。Compose 只承载 MVP 必需的服务及其最小依赖；不设计 Kubernetes、Service Mesh 或微服务集群治理。演进路线为：本地多服务启动 → Docker Compose → 单机部署 → CI/CD → 可观测性增强 → 部分服务拆分。当前实现前三步，CI/CD、可观测性增强和进一步服务拆分留到后续 Feature；云部署也留到后续 Feature。

## 14. AI Development Workflow

唯一开发流程入口是 Spec Kit：使用 `/speckit-specify` → `/speckit-clarify`（需要时）→ `/speckit-plan` → `/speckit-tasks` → `/speckit-implement`。任务按 Setup、Foundational、US1、US2、US3、Polish 分阶段，且每项写明输入、输出、文件路径和验证命令。涉及支付、退款、结算时加载 `payment-domain`；涉及新模块或部署时加载 `architecture`；涉及日志指标时加载 `observability`。实现前先确认本 Plan 未触碰人类决策边界，完成后运行测试和 Spec Kit 流程内的 Review。

## 15. Implementation Phases

1. **Bootstrap**：父 Maven 工程、多个独立服务的基础配置、不同端口、健康检查、错误模型、测试基座和 Compose 骨架；不新增未批准的服务。
2. **Catalog + Order + Transaction**：商品/SKU、价格快照、订单创建、订单状态和 1:1 交易。
3. **Payment Core**：支付意图、PaymentAttempt、Channel Adapter 抽象、Mock Channel、支付状态机和幂等；Payment Channel 仍属于 Payment 模块内部。
4. **Callback + UNKNOWN**：回调去重、查询收敛、超时处理、重复/乱序回调测试。
5. **Fulfillment + Entitlement**：Payment 成功结果 RPC、履约、权益授予、失败重试和查询。
6. **Refund**：可退金额、部分/全部退款、渠道退款模拟、退款 UNKNOWN 和后处理。
7. **Reconciliation + Settlement**：基础渠道记录导入/比对、差异、结算资格和最小结算批次。
8. **Observability + Delivery**：技术指标、支付领域业务指标、结构化日志、Trace 关联、审计字段、CI verify、Compose 验证和 quickstart 回归。

每个阶段完成后必须通过该阶段的独立测试、RPC 联调和 quickstart 验证，再进入下一阶段；服务目录已经存在的骨架不等于业务能力已完成。

## 未来拆分方案

当前各 `*-service` 都按独立部署单元保留。优先演进 Payment、Refund、Fulfillment、Entitlement、Reconciliation、Settlement，以及 Catalog、Order 的独立扩展和数据库物理隔离。Payment Channel 后续可从 Payment 内部演进为独立 Channel Gateway，但当前只实现 Channel Adapter + Mock Channel。服务之间通过公开 HTTP/RPC 用例和查询结果协作，不通过跨服务异步事件。

当前不应进一步拆分：Payment Channel、PaymentAttempt、Transaction。它们分别是 Payment 内的适配/交互事实和 MVP 交易关联模型，独立部署只会增加 RPC 协调成本。各服务通过独立包、公开 RPC 契约、服务自有 Schema、自有数据访问和禁止跨服务直连降低后续演进成本。

## Deferred Decisions

- Ledger 科目、复式分录、记账时点和余额模型；当前 Payment、Refund、Settlement 只模拟业务资金事实，不实现真实资金记账，未来真实资金模型必须通过 Ledger 建立可追溯账务事实。
- 真实资金划转、生产渠道和真实退款接口。
- 多币种清分、汇率、税费、复杂分账和多级商户。
- 复杂退款政策、已消费权益回收和补偿规则。
- 对账差异自动处理、人工审批和完整渠道账单格式。
- 结算费率、结算账户路由、批次调度和生产资金执行。
- 服务进一步独立物理数据库、服务通信基础设施和消息基础设施。
- 认证授权、密钥管理、生产安全策略和云部署策略的最终方案。

## 复杂度记录

> 本 Plan 无需要额外豁免的复杂度；单机多服务是当前明确的实现形态，未改变服务独立部署目标。

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 无 | 不适用 | 不适用 |
