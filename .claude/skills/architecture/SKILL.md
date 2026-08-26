---
name: architecture
description: 项目架构——Spring Cloud 微服务、服务边界、分层与依赖方向、分布式一致性（Saga + Outbox + 幂等）。涉及服务边界/依赖方向/新增中间件必读。
---

# 项目架构

来源：`docs/adr/0001-adopt-spring-cloud-microservices.md`、`docs/adr/0002-technology-stack.md`、`docs/architecture/overview.md`。


## 进行以下任务时使用：

- 新模块
- 新服务
- 数据库设计
- MQ
- 分布式事务
- 服务拆分
- API 设计

## 必须先回答：

1. 为什么需要？
2. 当前架构为什么不能解决？
3. 最简单方案是什么？
4. 为什么不采用最简单方案？
5. 引入后的复杂度是什么？
6. 运维成本是什么？
7. 后续如何演进？

禁止为了展示技术而增加复杂度。



## 总体架构：Spring Cloud 微服务

- 按 **Bounded Context** 划分服务（一个领域上下文一个服务），**Database-per-Service**，跨服务通过公开 HTTP/RPC 交互，**不共享表**；单机阶段可共用物理数据库，但必须使用独立 Schema。
- 分布式一致性用 **Saga + 同步 RPC + 幂等重试**，当前不引入 MQ 或跨服务异步事件，**禁止** 2PC/XA 分布式事务。

## 服务边界（ADR-0001）

| 服务 | 领域 | 核心职责 |
|---|---|---|
| gateway | 接入层 | 路由、鉴权、限流 |
| merchant-service | Merchant | 商户注册、资质、结算账户 |
| catalog-service | Product/SKU | 商品、SKU、价格 |
| order-service | Order | 订单/交易状态机 |
| payment-service | Payment + Channel | 支付编排、幂等、渠道适配、回调 |
| refund-service | Refund | 退款编排 |
| fulfillment-service | Fulfillment | 履约、发货 |
| entitlement-service | Entitlement | 权益授予/撤销/查询 |
| ledger-service | Ledger | 复式记账（资金核心） |
| reconciliation-service | Reconciliation | 异步对账 |
| settlement-service | Settlement | 结算划转 |

> **Channel 不单独成服务**：渠道适配以「接口 + 模块」存在于 payment-service 内（Payment ≠ Channel 边界）。

## 单服务分层

```
api → application → domain ← infra
```

依赖单向；`domain` 不依赖任何框架层；`infra` 实现 `domain` 声明的仓储接口（依赖倒置）。包名 `com.payment.<service>.<layer>`。

## 技术栈（ADR-0002）

Java 21 LTS · Spring Boot 3.x + Spring Cloud · Maven（mvnw）· MyBatis/MyBatis-Plus · Nacos（注册+配置）· OpenFeign + LoadBalancer · Spring Cloud Gateway · Micrometer（+ Tracing）。

## 禁止清单（Constitution §3.3）

- ❌ 跨领域直接 SQL 他领域表。
- ❌ 核心领域（Payment/Order/Ledger）依赖具体渠道实现。
- ❌ 过度拆分（CQRS/Event Sourcing/DDD 全套仪式）除非业务真实需要。
- ❌ 无理由引入微服务 / 分布式事务 / 中间件（Kafka/Redis/MQ/ES 等，须经 ADR 论证）。

## 人类决策边界（Constitution §8）

领域边界调整、重大架构变化、破坏性 schema 迁移、API 破坏性变更、安全策略、生产部署策略、数据迁移、支付状态机变更——AI 只能提方案，MUST 人类批准后执行。
