# ADR-0001: 采用 Spring Cloud 微服务架构

- **状态**：Accepted（已接受）
- **日期**：2026-08-26
- **决策者**：人类（项目 Owner）
- **取代**：Constitution §3.1「模块化单体」决策

## Context（背景）

项目最初在 Constitution §3.1 确立「模块化单体」架构（单一进程 + 单一数据库 + 包级领域边界）。在技术栈选型阶段，项目 Owner 明确选择「Spring Cloud 微服务」方向。该选择属于 Constitution §8 的 **Major Architecture Change**，由人类拍板，本 ADR 正式记录，并取代 §3.1。

## Decision（决策）

采用 **Spring Cloud 微服务架构**：

1. 按 **Bounded Context（限界上下文）** 划分服务——一个领域上下文一个服务，而非按实体或函数拆分。
2. 每个服务拥有**独立数据库**（Database-per-Service），跨服务通过 API / 事件交互，**不共享表**。
3. 分布式一致性使用 **Saga + Outbox + 幂等**，**不采用**分布式事务（2PC / XA）。

## 服务边界划分

| 服务 | 负责领域 | 核心职责 |
|---|---|---|
| gateway | 接入层 | 统一入口、路由、鉴权、限流 |
| merchant-service | Merchant | 商户注册、资质、结算账户 |
| catalog-service | Product / SKU | 商品、SKU、价格 |
| order-service | Order | 订单 / 交易状态机 |
| payment-service | Payment + Channel | 支付编排、幂等、渠道适配、回调 |
| refund-service | Refund | 退款编排（渠道退款 + 权益撤销 + 账本冲正） |
| fulfillment-service | Fulfillment | 履约、发货 |
| entitlement-service | Entitlement | 权益授予 / 撤销 / 查询 |
| ledger-service | Ledger | 复式记账（资金核心） |
| reconciliation-service | Reconciliation | 异步对账 |
| settlement-service | Settlement | 结算划转 |

> **Channel 不单独成服务**：渠道适配以「接口 + 模块」形式存在于 payment-service 内，保持 Constitution §2.3 中 **Payment ≠ Channel** 的边界（靠接口抽象实现）。当渠道数量与隔离需求上升时，再抽取为独立 gateway 服务（届时另立 ADR）。

## Consequences（后果）

**正面**：
- 各领域独立演进、独立部署、独立扩容。
- 故障隔离：单服务故障不拖垮全链路。
- 技术栈可逐步替换（未来某服务可独立换语言/框架）。

**代价（必须显式应对）**：
- **分布式一致性成本**：跨服务资金流转需要 Saga / Outbox / 幂等，复杂度显著高于单体。
- **运维复杂度**：注册中心、配置中心、网关、多套部署。
- **跨服务幂等与对账成为硬要求**（见 Constitution §4）。

## 关联

- 取代 Constitution §3.1（模块化单体）—— Constitution 需按 §10 修订。
- ADR-0002：技术栈选型。
- `docs/project-structure.md`：目录结构。
