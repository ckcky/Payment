# PaymentArch 总体架构方案

**状态**：已确认，作为当前实现基线

**生效日期**：2026-08-26

**关联决策**：[ADR-0001](../adr/0001-adopt-spring-cloud-microservices.md)、[ADR-0002](../adr/0002-technology-stack.md)

## 1. 架构定位

PaymentArch 采用按限界上下文划分的 Spring Cloud 微服务架构。每个服务拥有独立的业务模型、状态机、数据访问边界和部署单元。

“每服务独立数据库”首先表示数据所有权和访问边界，而不是强制每个服务必须拥有独立物理数据库。在本地、Docker Compose 和单机部署阶段，可以让多个服务使用同一个物理数据库，但每个服务必须使用独立 Schema；服务不得跨 Schema 直接读写数据。未来需要独立扩展或隔离时，再将 Schema 迁移为独立物理数据库。

## 2. 当前部署形态

服务仍然是独立服务，而不是模块化单体。单机部署只表示多个独立进程运行在同一台服务器上：

```text
一台服务器
├── merchant-service   : 不同端口
├── catalog-service    : 不同端口
├── order-service      : 不同端口
├── payment-service    : 不同端口
├── refund-service     : 不同端口
├── fulfillment-service : 不同端口
├── entitlement-service : 不同端口
├── reconciliation-service : 不同端口
└── settlement-service : 不同端口

一个物理数据库
├── merchant_schema
├── catalog_schema
├── order_schema
├── payment_schema
├── refund_schema
├── fulfillment_schema
├── entitlement_schema
├── reconciliation_schema
└── settlement_schema
```

服务通过各自端口监听，数据库通过各自 Schema 隔离。单机不改变服务边界。

## 3. 服务边界

| 服务 | 负责领域 | 不负责 |
|---|---|---|
| merchant-service | Merchant、结算资格和商户基础信息 | 订单、支付执行、履约和对账处理 |
| catalog-service | Product、SKU、价格和交付定义 | 订单价格快照、支付和权益 |
| order-service | Order、Transaction、订单生命周期 | 支付渠道协议、资金收取和权益发放 |
| payment-service | Payment、PaymentAttempt、Payment Channel、支付回调 | 订单内部状态、履约内部状态和权益内部状态 |
| refund-service | Refund、退款资格和退款编排 | 直接修改 Payment、Fulfillment 或 Entitlement 内部数据 |
| fulfillment-service | Fulfillment、交付任务和交付结果 | 支付结果判断和权益内部状态 |
| entitlement-service | Entitlement、授予、使用、撤销和有效期 | 支付确认和退款决策 |
| reconciliation-service | 平台事实与 Mock/预置渠道账单的对账和差异 | 修改原始 Payment/Refund 事实和真实出款 |
| settlement-service | 商户结算批次、净额计算和模拟结算结果 | 真实资金出款、复式记账和原始差异修正 |
| ledger-service | 后续 Feature 的复式账务事实 | 当前 MVP 不创建、不实现 |

Payment Channel 当前属于 payment-service 内部的 Channel Adapter + Mock Channel。未来可在渠道数量、隔离性和独立扩展需求足够时演进为 Channel Gateway。

## 4. 服务通信原则

跨服务统一通过公开 HTTP/RPC 用例同步调用。调用方必须处理超时、连接失败、返回错误、有限重试和幂等；被调用方必须返回可查询的业务状态。

当前不引入 Kafka、RabbitMQ 或其他 MQ，也不把跨服务异步事件作为 MVP 的通信机制。服务内部可以使用事件表达本地状态变化，但事件不跨服务发布；跨服务后置流程由负责方通过同步 RPC 调用下游公开用例完成。

典型调用：

```text
order-service → catalog-service：校验 SKU 和获取销售数据
order-service → payment-service：创建支付意图
payment-service → 渠道适配器：发起支付和查询渠道结果
payment-service → fulfillment-service：支付成功后请求履约
fulfillment-service → entitlement-service：履约完成后请求权益授予
refund-service → payment-service：发起支付退款
refund-service → fulfillment-service / entitlement-service：请求退款后处理
reconciliation-service → payment-service / refund-service：读取已确认业务事实
settlement-service → merchant-service / reconciliation-service：校验结算资格并生成结算批次
```

## 5. 一致性和资金边界

- 单服务内部状态变化使用本地事务保证一致性。
- 跨服务使用同步 RPC 编排、幂等键、状态查询和补偿流程，禁止 2PC/XA。
- 支付、退款和结算当前只模拟业务资金事实，不执行真实记账和真实出款。
- Payment 超时或结果不完整必须进入 UNKNOWN；只能通过查询、回调、对账或人工处理收敛。
- 未来真实资金模型必须先建立 Ledger 的可追溯复式账务事实，再允许接入真实资金动作。
- 每个服务只能修改自己 Schema 内的数据，任何跨服务数据读取都必须经过对方公开 RPC。

## 6. 演进原则

当前优先保持独立服务的业务边界和端口隔离，但不提前引入所有分布式基础设施。部署演进为：本地多服务启动 → Docker Compose → 单机部署 → CI/CD → 可观测性增强 → 有证据的部分服务拆分和独立数据库迁移。

只有当服务有独立扩展、故障隔离、数据隔离或团队 ownership 的真实需求时，才引入额外基础设施或进一步拆分。
