---
name: architecture
description: 项目架构导航——服务边界、分层与依赖方向、跨服务一致性。涉及服务边界/依赖方向/新增中间件/服务拆分时必读。本技能只做**导航**，架构事实以权威文档为准。
---

# 项目架构（导航层）

> **本技能不定义架构事实**。任何架构事实（服务清单、端口、分层、依赖方向、一致性方案）MUST 以权威文档为准，本技能只负责**告诉你去哪读**、以及**提问清单**。
>
> | 需要的事实 | 权威来源（L0/L1） |
> |---|---|
> | 当前服务清单 / 端口 / 部署形态 | [docs/architecture/technical-solution.md](../../../docs/architecture/technical-solution.md)（§3 领域与依赖、§6 部署） |
> | 全平台容器视图 | [docs/architecture/diagrams/02-container-overview.puml](../../../docs/architecture/diagrams/02-container-overview.puml)（**9 个业务服务 + 1 个 Demo 进程 = 10 个运行进程**） |
> | 某服务内部实现事实 | [docs/architecture/systems/](../../../docs/architecture/systems/) 对应文档（15 章骨架） |
> | 架构硬约束与红线 | [.specify/memory/constitution.md](../../../.specify/memory/constitution.md) §III 领域边界 / §IV 架构边界 / §V 一致性 |
> | 历史决策原因 | [docs/adr/README.md](../../../docs/adr/README.md)（完整清单）、[docs/adr/traceability.md](../../../docs/adr/traceability.md)（落点） |
> | 文档写法与章号 | [docs/standards/](../../../docs/standards/)（technical-solution / system-design / adr 规范） |

## 进行以下任务时使用

新模块 · 新服务 · 数据库设计 · MQ / 消息通道 · 分布式事务 · 服务拆分 · API 设计。

## 必须先回答（动手前）

1. 为什么需要？
2. 当前架构为什么不能解决？
3. 最简单方案是什么？
4. 为什么不采用最简单方案？
5. 引入后的复杂度是什么？
6. 运维成本是什么？
7. 后续如何演进？

禁止为了展示技术而增加复杂度。

## 当前架构要点（截至 2026-09-22 的**摘要**，细节以 L0 为准）

- **Spring Cloud 微服务**：按 Bounded Context 划分服务，**Database-per-Service**，跨服务经公开 HTTP/RPC 或事件通道交互，**不共享表**；单机阶段可共用物理 MySQL 实例，但必须独立 Schema。
- **服务口径（易错，务必用对）**：**9 个核心业务服务**（merchant / catalog / order / payment / fulfillment / entitlement / ledger / reconciliation / settlement）+ **1 个 Demo / 演示进程**（`mock-channel-web`，收银台演示组件）= **10 个运行进程**。**`mock-channel-web` 不是业务服务**；**不要写「10 个服务」**。
  - `gateway` 本 MVP **不创建、不部署**（延后，见 technical-solution §3.2）。
  - `refund-service` **已不存在**——退款域已并入 payment-service（ADR-0064），旧文档中的 refund 服务条目属**历史**。
- **payment-service 内部两层**（ADR-0072）：payment 支付层（`payments`，支付单生命周期 + 支付指令编排）与 channelAttempt 渠道层（`payment_attempts`，渠道交互 + 渠道实现族）。两层**共享同一本地事务**，属职责切分而非分布式拆分。渠道适配**不单独成服务**（`Payment ≠ Channel`）。
- **跨服务一致性**：Saga 思路 + 同步 RPC + 幂等 + 调用方补偿台账；**禁止 2PC/XA**。
- **异步通知通道**：跨服务异步解耦**用既有 Redis（`redis:7`）Streams 模拟 MQ** 承载**事务消息通道**（ADR-0074 / spec 029，🟢 Accepted 已实现）——**不引入 MQ 中间件**，理由是复用已存在的 Redis 依赖以**减少需要新增与长期运维的组件**；**未引入 Kafka / RocketMQ / ES**。Redis 的项目定位是**非数据源**（幂等 / 缓存 / 预扣 / 时间轮 / 事务消息通道）。
- **单服务分层**：`api → application → domain ← infra`，依赖单向；`domain` 不依赖框架层；`infra` 实现 `domain` 声明端口（依赖倒置）。包名 `com.payment.<service>.<layer>`。两个反例边界由 ArchUnit 守卫（`deployment/architecture-tests`）。
- **技术栈**：Java 21 · Spring Boot 3.x + Spring Cloud · Maven（`./mvnw`）· MyBatis-Plus · Nacos · OpenFeign + LoadBalancer · Micrometer。**Spring Cloud Gateway 不在 MVP**。**Micrometer Tracing 未落地**（当前观测手段见 [observability 技能](../observability/SKILL.md)）。

## 禁止清单（Constitution §III / §IV）

- ❌ 跨领域直接 SQL 他领域表。
- ❌ 核心领域（Payment / Order / Ledger）依赖具体渠道实现。
- ❌ 过度拆分（CQRS / Event Sourcing / DDD 全套仪式），除非业务真实需要。
- ❌ 无理由引入新服务 / 中间件 / 分布式事务（须先经 ADR 论证）。
- ❌ 擅自改领域模型 / 状态机 / 服务边界 / 数据库结构 / 公共 API。

## 人类决策边界（Constitution §Governance › 人类决策边界）

领域边界调整、重大架构变化、破坏性 schema 迁移、API 破坏性变更、安全策略、生产部署策略、数据迁移、支付状态机变更——AI 只能提方案，MUST 人类批准后执行。

## 与文档体系的关系

发现 `代码 ≠ System Design`、`System Design ≠ Technical Solution` 或 `Active Spec ≠ 当前实现` 时，MUST 报告 `DOCUMENTATION_DRIFT`（证据 + 各方描述 + 待确认问题），**不得**静默改写架构文档或擅自选定一侧。规则见 [docs/standards/documentation-governance.md](../../../docs/standards/documentation-governance.md)。
