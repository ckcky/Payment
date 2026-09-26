# 041-payment-service-governance — Plan

> **Status**: Draft
> **Date**: 2026-09-26
> **Spec**: [spec.md](spec.md)

## 1. 技术上下文与前置决策

改造只发生在 `payment-service` 进程内：不新增服务、模块、数据库实例、MQ 或分布式事务。实施前先创建并 Accepted 新 ADR，记录双域边界、一次性 API 替换和仅开发/测试环境清库的负责人决定。

清库工具必须显式要求 `APP_ENV=development` 或 `APP_ENV=test`；任一其他值、缺失值或目标库无法识别时，必须失败且零删除。真实数据环境不属于本 Feature。

## 2. 目标组件与依赖

```text
HTTP Client / Channel Provider
          │
          ▼
 payment.api / channel.api
          │
          ▼
payment.application ──ChannelGateway──> channel.application
          ▲                                  │
          └──────PaymentResultPort───────────┘
          │                                  │
payment.domain <── payment.infra      channel.domain <── channel.infra
```

- Payment 可依赖 `channel.application.port.ChannelGateway` 与共享 DTO；禁止依赖 Channel 的其他 application 类型、domain、infra、Mapper、Entity、Plugin、Router、Registry。
- Channel 可依赖 `payment.application.port.PaymentResultPort` 与共享 DTO；禁止依赖 Payment 聚合、Repository、Mapper 或 application 实现。
- Domain 互不依赖；infra 只实现本域端口；Controller 只调用一个本域 command/query facade。
- 跨域 DTO 放在 `common-dto`，只含业务单号、金额、币种、状态/原因和必要关联字段，不含数据库 ID、实体、SDK 或原始渠道报文。

## 3. HTTP 契约与调用方迁移

| 领域 | 新端点 | 请求/响应关键身份 | 同步调用方 |
| --- | --- | --- | --- |
| Payment | `POST /api/payments` | transactionNo、orderNo、userId、merchantId、amountMinor、currencyCode、idempotencyKey、可选 channelCode | order、demo、e2e、压测 |
| Payment | `GET /api/payments/{paymentNo}` / `GET /api/payments?transactionNo=` | paymentNo 或 transactionNo | demo、运维 |
| Payment | `POST /api/payments/{paymentNo}/resolve` | paymentNo、显式 resolve 命令 | 运维、demo |
| Refund | `POST /api/payments/{paymentNo}/refunds` | refundNo 派生、amountMinor、currencyCode、reason、idempotencyKey | order、demo、e2e |
| Refund | `GET /api/refunds/{refundNo}` / `POST /api/refunds/{refundNo}/resolve` | refundNo | order、demo、运维 |
| Channel | `POST /callbacks/channels/{channelCode}` | channelCode、原始报文/签名 | 真实渠道、mock-channel-web |
| Channel | `GET /internal/channels/orders/{channelNo}` / `?paymentNo=` | channelNo/paymentNo | 运维、demo |
| Channel | `GET /internal/channels`、`GET /internal/channels/route-preview`、`POST /internal/channels/{channelCode}/availability` | channelCode、路由上下文 | demo、运维 |
| Facts | `/internal/payments/confirmed-facts`、`/internal/refunds/confirmed-facts` | 期间/商户过滤条件 | reconciliation-service |

旧端点和旧 DTO 在全部调用方迁移后删除，不创建转发、别名或 deprecated 兼容层。每个请求/响应契约须明确哪些业务单号必填，禁止 `id`、`attemptId` 等数值主键跨 HTTP 或跨域端口出现。

## 4. 数据与一致性设计

### 4.1 所有权

| 所有者 | 聚合/表 | 可写层 |
| --- | --- | --- |
| Payment | Payment、Refund、Limit、PendingPosting、消息/审计辅助事实 | payment.application 经 payment.domain Repository 端口 |
| Channel | ChannelOrder、渠道引用、渠道模态、渠道调用错误与重试事实 | channel.application 经 channel.domain Repository 端口 |

从空库按 Payment/Refund → ChannelOrder → Limit → PendingPosting → 消息/审计辅助表顺序建表。每张表有业务唯一键和必要查询索引；跨域只保存 paymentNo/refundNo/channelNo 等业务单号，不建跨域外键。DDL、H2、MySQL 和 replay 由同一 Schema 来源生成。

### 4.2 失败与恢复

- Payment 本地事务只保护 Payment 事实和本域失败台账；Channel 本地事务只保护 ChannelOrder。两域不共享事务。
- 渠道建单/调用失败时 Payment 保留可收敛 PENDING/UNKNOWN；终态偏差用查询、对账和人工显式命令处理，不回滚已确认外部事实。
- 账本调用维持同步；失败写 Pending Posting 并可重放，绝不伪装为完整资金成功。
- Redis Streams 只承载通知。通道失败不改变资金真相；消费方按业务键幂等，允许按既有同步回落恢复时效。
- 回调固定先在 Channel 验签、验证引用/金额/币种，再经 PaymentResultPort 通知；失败永不触达 Payment 状态机。

## 5. 插件模型

`channel.application` 定义模板方法：校验命令、定位/创建 ChannelOrder、调用插件、映射标准结果、收敛 ChannelOrder、通知 Payment。模板入口不可被插件绕过。

Factory/Registry 只按规范化 channelCode 定位唯一 Plugin。每个 Plugin 用 Strategy 表达协议差异（支付、退款、查询、验签、报文解析、可用性）；不得读取 Payment/Refund 实体、执行业务记账、调用订单服务或决定业务状态机。每个 `plugins/<code>/` 目录携带该渠道所有生产资源和测试。

## 6. 实施顺序、风险与回滚

1. ADR、四件套、迁移清单与目标包骨架。
2. 共享契约、双向端口、ChannelOrder 和 Channel 持久化。
3. 渠道模板、Factory/Registry、全部插件与回调。
4. Payment/Refund/Limit/可靠性/挂账/MQ/Feign/配置与 Controller。
5. Schema、HTTP API、上下游与 deployment 调用方。
6. 删除旧实现、加固门禁、全量验证与 L0 文档更新。

风险：API 漏迁、清库误环境、状态机漂移、跨域双向实现依赖、插件资源遗漏和新旧双写。缓解：迁移清单、环境保护、状态机先行测试、受控端口依赖、插件装配测试及切换后立即删除旧路径。

回滚以 Feature 分支/PR 为单位；禁止新旧 API 混跑。未合并时丢弃分支；合并后 revert merge commit。真实数据迁移需要独立 Feature。

## 7. 验证方式

执行顺序：Schema lint/双路径 replay → 单元测试 → 真库幂等并发测试 → ArchUnit → payment-service 测试 → 上下游 HTTP 契约测试 → 全 reactor `mvnw clean verify` → 容器关键 Demo → docs lint。

