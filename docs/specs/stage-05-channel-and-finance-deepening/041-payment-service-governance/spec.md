# 041-payment-service-governance — Spec

> **Status**: Approved（2026-09-26 负责人裁决批准立项，H-041-1~3 已裁决）
> **Date**: 2026-09-26
> **Stage / Path**: `docs/specs/stage-05-channel-and-finance-deepening/041-payment-service-governance/`
> **Related ADR**: [ADR-0084](../../../adr/0084-payment-channel-governance.md)（🟡 **Proposed**，2026-09-26 起草，待负责人 Accept；**Supersedes ADR-0072 §6**）
> **Standard**: [spec-standard.md](../../../standards/spec-standard.md)
>
> ⚠️ **实施门禁（不得跳过）**：`acceptance.md` §1 前置条件 1 要求「**新 ADR 已 Accepted**；
> Feature 已进入 `In Development` 或更高状态」。当前 ADR-0084 为 **Proposed**，
> 且 H-041-4~6（Supersedes ADR-0072 §6 / ADR-0083 排除路径补 `/callbacks/**` / spec 040 置 Superseded）待裁决
> ⇒ **Accept 前禁止动任何代码**；本轮只完成 T01（ADR 草案）、T02（四件套与索引）、T03（迁移映射）。

### 0.5 现状与目标结构的差异（2026-09-26 实测核对，实施前必读）

spec 正文按**目标结构**书写，实施者**MUST NOT**照字面把目标当现状。以下为实测差异：

| 项 | spec 目标 | 现状（master `5ce4853`） | 实施含义 |
|---|---|---|---|
| 渠道域包名 | `com.payment.channel` | `com.payment.channelgateway`（63 生产 / 26 测试文件） | **改名**，不是新建 |
| 挂账域 | 无独立包 | `com.payment.posting.{api,application,domain,infra}`（13 生产 / 1 测试） | **并入** `payment` |
| 旁路包 | 只有四层 | `payment/{limit,mq,web}` + `channelgateway/web` | **收进四层** |
| 插件目录 | `plugins/<vendor>/` 自包含 | 完整：`infra/wechat/`、`infra/stripe/`；半成品：`infra/alipay/`（缺 `AlipayChannelAdapter` 与 `config/AlipaySandboxProperties`，二者在目录外）；未成型：`DouyinChannelAdapter`、`MockChannelAdapter` 为扁平文件 | 逐渠道补齐（T11） |
| 渠道单类名 | `ChannelOrder` | 已是 `ChannelOrder`（旧 041 `57e12c3` 由 `PaymentAttempt` 正名而来），表 `channel_orders` | **保留**，只迁包路径 |
| 事务边界 | 两域不共享事务 | 已拆（旧 041 `57e12c3` D2） | 与 **ADR-0072 §6** 冲突 ⇒ 由 ADR-0084 决策 6 收口 |
| 回调端点 | `/callbacks/channels/{channelCode}` | `/internal/channels/{channelCode}/callback` | 改名后**脱离 ADR-0083 的 `/internal/channels/**` 排除路径** ⇒ 必须同步补 `/callbacks/**`（ADR-0084 X-2） |
| spec 040 | 「可由本 Feature 吸收」 | 040 为 Draft，38 个任务全未勾选 | 须显式置 `Superseded by 041` |

**实测规模**：payment-service 187 个生产 java / 18,493 行；78 个测试 java / 12,351 行。
旧端点路径引用面 130+ 文件（含 docs），非文档约 40 个。

## 1. 背景（Problem）

`payment-service` 同时承载 Payment、Channel、Refund、Limit、可靠性、挂账、消息与 HTTP 接入职责。历史包、控制器、渠道实现和持久化模型混杂，且有未归档的“spec 041”代码痕迹，无法作为可审计设计依据。Payment 必须不再直接了解渠道订单、插件、路由、SDK 或持久化实现。

本 Feature 将当前实现视为可替换基线。037–040 及未归档痕迹可用于迁移风险调查，但不是目标结构、行为或验收的约束。

## 2. 目标（Goal）

1. 将全部 payment-service 生产代码收敛为 Payment 与 Channel 两个对称领域，均采用 `api / application / domain / infra` 四层。
2. Payment 只通过 `ChannelGateway` 调用渠道；Channel 只通过 Payment 定义的 `PaymentResultPort` 交付标准化结果。
3. 每个渠道的协议、SDK、签名、配置、策略、插件、回调解析与测试可在单一插件目录定位。
4. 一次性替换仓内 HTTP 调用面，重建开发环境 Schema，且不破坏资金正确性。
5. 通过自动化门禁和端到端验收证明：无跨域数据直写、无插件泄漏、无旧兼容面或散落 Controller。

## 3. 范围（Scope）

### 3.1 领域与目录

```text
com.payment.payment
├── api
├── application
├── domain
└── infra

com.payment.channel
├── api
├── application
├── domain
└── infra
    ├── persistence
    └── plugins
        ├── alipay
        ├── wechat
        ├── stripe
        ├── douyin
        └── mock
```

本 Feature 覆盖 payment-service 的支付、退款、渠道、限额、可靠性、挂账、MQ、Feign、配置、所有 Controller、测试、Schema、Demo、E2E 与运行文档。Refund、Limit、可靠性、挂账和消息均为 Payment 的操作切片；ChannelOrder、选路、回调和插件均为 Channel 的操作切片。

### 3.2 API 与数据边界

- 所有旧 HTTP API 允许一次性替换，所有仓内调用方须在本 Feature 同步迁移；不保留兼容 Controller、旧路径或旧 DTO。
- 仅开发/测试环境允许清空 payment Schema 后重建；不迁移历史数据。
- 不得改变 Payment、Refund、ChannelOrder 的状态枚举或合法迁移；如必须改变，实施必须暂停并走新的负责人决策。

## 4. 非目标（Non-goals）

- 不新增部署服务、Maven 模块、数据库实例、MQ、2PC/XA、CQRS、Event Sourcing、Service Mesh。
- 不接入新的支付渠道，不改变既有渠道业务能力、路由策略、账本科目或 ledger-service 契约。
- 不进行生产数据迁移、灰度发布、旧 API 兼容或真实环境清库。
- 不允许 GET 执行状态迁移、重试、回放或人工收敛。

## 5. 场景与用户故事（Scenarios / User Stories）

| ID | 前置条件 → 触发 | 期望结果 |
| --- | --- | --- |
| US-01 | 合法订单和幂等键 → 创建支付 | Payment 创建/复用支付单，只经 ChannelGateway 创建渠道交互，返回标准 Payment 事实。 |
| US-02 | 同一支付命令重复提交 | 返回同一 paymentNo；无第二个 ChannelOrder、账本分录或下游通知。 |
| US-03 | 渠道超时、断连或回执不完整 | Payment 与 ChannelOrder 进入 UNKNOWN/PENDING；不猜测成功或失败。 |
| US-04 | 渠道异步回调到达 | Channel 完成身份识别、验签、解析与自身收敛；再以 PaymentResultPort 通知 Payment。 |
| US-05 | 回调签名、渠道、引用、金额或币种不一致 | 回调被拒绝，Payment/Refund、账本和下游通知均不变化。 |
| US-06 | 支付/退款未知后查询或重试 | Channel 使用原支付渠道收敛；禁止重新选路。 |
| US-07 | 已成功支付后申请退款 | Payment 创建/复用 Refund，经 ChannelGateway 回原渠道退款并按结果收敛。 |
| US-08 | 退款重复、迟到或冲突结果 | 终态吸收，绝不重复退款、记账或权益后处理。 |
| US-09 | 开发者维护支付宝/微信等渠道 | 在唯一 `plugins/<channel>/` 目录找到该渠道全部实现与测试，Payment 无渠道私有依赖。 |
| US-10 | 空开发库初始化 | Schema 重放后服务和全部仓内调用方可用；旧表、旧端点与旧包不存在。 |

## 6. 功能需求（Functional Requirements）

| ID | 需求 |
| --- | --- |
| FR-001 | payment-service 全部生产源码 MUST 归入 Payment 或 Channel 及其四层目录；不得保留历史散落包。 |
| FR-002 | 创建支付 MUST 固定执行“业务校验 → 幂等创建/复用 Payment → `ChannelGateway.pay` → 应用标准化结果 → 记账和下游编排”。 |
| FR-003 | 退款、主动查询和重试 MUST 仅经 `ChannelGateway.refund/query`，并使用已记录的原支付渠道。 |
| FR-004 | 渠道支付、退款、查询 MUST 固定执行“命令校验 → 幂等创建/复用 ChannelOrder → 定位插件 → 外部调用 → 收敛 → 返回/通知结果”。 |
| FR-005 | 外部渠道回调 MUST 仅由 Channel API 接收；渠道身份识别、验签和私有报文解析 MUST 在 Channel 内完成。 |
| FR-006 | Channel 收敛后 MUST 仅经 Payment 定义的 `PaymentResultPort` 交付标准支付/退款结果；Payment 负责状态、账本和下游副作用。 |
| FR-007 | 每个渠道的 SDK Gateway、配置、签名器、Factory、Strategy、Plugin、回调解析器和测试 MUST 位于专属插件目录。 |
| FR-008 | 渠道调用 MUST 使用模板方法固定通用步骤；Factory/Registry 只选插件；Strategy 只表达渠道差异。 |
| FR-009 | Controller MUST 只做 HTTP 入参校验、调用单一本域应用入口与响应转换；不得访问 Repository、Mapper、Entity、Plugin 或跨域实现。 |
| FR-010 | 所有读写 MUST 经领域 Repository 端口进入 `infra.persistence`；应用服务不得直接依赖 Mapper。 |
| FR-011 | 系统 MUST 一次性替换支付、退款、查询、UNKNOWN 收敛、渠道回调、渠道订单查询、运维和路由 HTTP API，并迁移全部仓内调用方。 |
| FR-012 | 系统 MUST 重建开发环境 payment Schema；Payment、Refund、ChannelOrder、Limit、Pending Posting 分别拥有数据所有权、唯一键和查询索引。 |
| FR-013 | 系统 MUST 删除历史包、重复 Controller/Service、废弃 DTO、旧端点、旧配置、旧测试及兼容垫片。 |
| FR-014 | 系统 MUST 以 ArchUnit 强制双域边界、四层依赖、Controller、插件目录与持久化边界，并有阳性对照。 |
| FR-015 | 系统 MUST 更新系统设计、技术方案、Roadmap、Specs 索引、运行手册、Schema、Demo 和测试说明，消除旧术语口径。 |

## 7. 业务规则与不变量（Business Rules）

| ID | 规则 / 不变量 | 违反时处理 |
| --- | --- | --- |
| INV-001 | 金额只能用 `long amountMinor` 与 `currencyCode` 成对传递；禁止 `float/double`。 | 拒绝命令，零持久化副作用。 |
| INV-002 | Payment、Refund、ChannelOrder 只由所属领域状态机推进；终态吸收迟到、重复和冲突结果。 | 拒绝非法迁移或返回既有终态。 |
| INV-003 | Payment 不得读写 ChannelOrder；Channel 不得读写 Payment/Refund。 | 架构门禁失败，禁止合并。 |
| INV-004 | 重复资金命令不得产生第二笔 Payment、Refund、ChannelOrder、账本分录或下游通知。 | 返回首次事实/幂等结果。 |
| INV-005 | 超时、断连、无效回执或不可确认结果必须进入 UNKNOWN/PENDING。 | 保留可收敛事实，禁止猜测结果。 |
| INV-006 | 已确认资金变动必须经 ledger-service 复式记账；不得直改余额。 | 进入失败台账/重放，禁止伪成功。 |
| INV-007 | 退款、重试、主动查询只能回原支付渠道。 | 无法解析原渠道时拒绝并记录可处置失败。 |
| INV-008 | 验签、渠道、引用、金额或币种不一致不得进入 Payment 状态机。 | 拒绝回调，业务状态/账本/通知不变。 |
| INV-009 | 渠道私有协议、SDK、签名、配置和 DTO 不得泄漏到 Payment 或公共 DTO。 | 架构门禁失败，禁止合并。 |
| INV-010 | GET 零副作用；状态推进、重试、回放、人工收敛必须是显式命令。 | API 验收失败。 |

## 8. 状态与生命周期（State / Lifecycle）

本 Feature 仅改变状态机的归属和调用边界：Payment 域推进 Payment/Refund，Channel 域推进 ChannelOrder。不得更改状态枚举、迁移条件、终态定义或资金语义。发现必须改变时，实施者 MUST 停止并另行提出 ADR/Feature。

## 9. 错误与边界（Error / Edge Cases）

| 场景 | 触发 | 期望行为 | 错误标识 |
| --- | --- | --- | --- |
| 无效金额/币种 | 非正金额或缺失币种 | 拒绝，零写入 | `INVALID_ARGUMENT` |
| 幂等键冲突 | 同键对应不同业务字段 | 拒绝，不复用错误事实 | `CONFLICT` |
| 渠道不可用 | 路由或原渠道无法解析 | 不新建渠道交互 | `CHANNEL_UNAVAILABLE` |
| 外部超时 | 超时/断连/不完整响应 | UNKNOWN/PENDING，不记终态账 | `CHANNEL_UNKNOWN` |
| 回调验签失败 | 签名/时间窗/身份无效 | 403，零业务副作用 | `SIGNATURE_INVALID` |
| 回调事实错配 | 引用/金额/币种/渠道码不符 | 409，零业务副作用 | `CHANNEL_CALLBACK_MISMATCH` |
| 账本失败 | ledger RPC 失败 | 失败台账可重放 | `LEDGER_POSTING_PENDING` |
| 通知失败 | MQ 不可用或消费失败 | 业务事实不变，按重放/回退恢复 | `NOTIFICATION_PENDING` |

## 10. 幂等与重复处理（Idempotency）

| 入口 | 幂等键/身份 | 权威事实 | 重复语义 |
| --- | --- | --- | --- |
| 创建支付 | idempotencyKey + 业务上下文 | Payment | 返回首次 Payment。 |
| 创建退款 | idempotencyKey + 退款上下文 | Refund | 返回首次 Refund，禁止重复调用渠道。 |
| 渠道命令 | paymentNo/refundNo + channelNo + 命令类型 | ChannelOrder | 返回同一渠道订单。 |
| 渠道回调 | channelCode + channelReference + 结果事实 | ChannelOrder | 重复吸收，冲突终态不覆盖。 |
| 账本投递 | accounting event 派生业务键 | ledger/失败台账 | 重放不产生重复分录。 |
| 消息消费 | 事件业务键 | 消费方幂等记录 | 至少一次投递被吸收。 |

业务真相不得依赖 Redis；通知可按既有同步回落恢复，但不得跳过幂等、状态机或账本约束。

## 11. 验收标准（Acceptance Criteria）

| ID | 验收标准 | 验证方式 |
| --- | --- | --- |
| SC-001 | 所有生产源码均在目标双域四层，旧散落包为零。 | 包扫描、ArchUnit。 |
| SC-002 | 正常支付仅经 ChannelGateway，Payment 不访问 Channel 私有实现或持久化。 | 单测、依赖扫描、ArchUnit。 |
| SC-003 | Channel 正常 pay/refund/query 建立并收敛 ChannelOrder，只输出标准化结果。 | 应用/插件测试。 |
| SC-004 | 回调的解析在 Channel，Payment 仅通过 PaymentResultPort 收结果。 | HTTP、端口与依赖测试。 |
| SC-005 | 支付、退款、渠道命令、回调、账本、消息重放不产生重复资金副作用。 | 并发和幂等测试。 |
| SC-006 | 超时/断连不猜结果，能由查询、回调或人工命令收敛。 | UNKNOWN 场景测试。 |
| SC-007 | 退款/查询/重试永远回原渠道。 | 场景测试。 |
| SC-008 | 私有渠道类型不出现在 Payment 或公共契约。 | ArchUnit、源码扫描。 |
| SC-009 | Controller 无 Repository/Mapper/Entity/Plugin 依赖，单请求只委托一个应用入口。 | ArchUnit、MVC 测试。 |
| SC-010 | application 经 Repository 端口访问数据；Mapper 仅在 infra.persistence。 | ArchUnit。 |
| SC-011 | 新 API 完成所有业务/运维场景，仓内旧路径引用为零。 | 契约、E2E、负向扫描。 |
| SC-012 | 空开发库可安全重建，表、唯一键和索引符合目标模型。 | Schema lint/重放、真库测试。 |
| SC-013 | 旧包、API、DTO、配置、测试和兼容代码不存在。 | 迁移清单、负向扫描、编译。 |
| SC-014 | 所有架构规则有防空转阳性对照。 | architecture-tests。 |
| SC-015 | 全量构建、关键 Demo、文档检查通过；任一资金不变量失败即未完成。 | verify、Demo、docs lint。 |

## 12. 依赖（Dependencies）

硬依赖：Constitution；Payment/Order/Ledger 的公开契约；Redis 事务消息通道；Schema 重放及架构测试基础设施；本 Feature 的新增 ADR。

软依赖：040 Draft 可由本 Feature 吸收；037–039 的实现仅作迁移调查输入。

## 13. 相关文档（Related Documents）

- [Constitution](../../../../.specify/memory/constitution.md)
- [payment-service 系统设计](../../../architecture/systems/payment-service.md)
- [总体技术方案](../../../architecture/technical-solution.md)
- [工程规范](../../../guides/engineering-standards.md)
- [业务规范](../../../guides/business-standards.md)
- [040 API 面治理](../040-payment-api-surface-consolidation/spec.md)
