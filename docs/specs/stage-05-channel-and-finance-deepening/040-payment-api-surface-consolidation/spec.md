# Spec: 040-payment-api-surface-consolidation（支付域 API 面收口）

**Feature**：040　**标题**：Payment API Surface Consolidation（查单双单号 / 通用退款入口 / 死端点清理 / 运维端点可控）
**版本**：v1.0（Draft）　**日期**：2026-09-26
> **Status**: Draft <!-- Draft | In Review | Approved | In Development | Implemented | Deprecated | Superseded | Not Implemented -->
**前置**：036（渠道微内核）、037（渠道网关边界）、038（payment-service 包边界）、039（微信插件）均已在 master
**输入权威**：[Constitution §Governance / §Architecture](../../../../.specify/memory/constitution.md)、
[ADR-0063 跨系统标识](../../../adr/)、[ADR-0067 两层退款单](../../../adr/)、[ADR-0016 退款恒按全退](../../../adr/)

**阅读约定**：`【现状】`= 已存在的事实（附 `path:line`）；`【目标】`= 设计意图，**尚未实现**；`【待确认】`= 人类决策。

---

## 0. 定位与一句话目标

**把 payment-service 的 HTTP 面收敛成「每个端点都有真实调用方、每个查询都能按业务单号定位、GET 零副作用、运维端点可开关」，并补上当前缺失的通用退款入口。**

本 Feature **不是**架构重构，是 API 面治理。触发原因是负责人逐条质询 payment-service 暴露的 12 个 `payment/api` 端点后，确认存在 4 类问题：查单不支持交易单号且违反 ADR-0063、通用退款能力「应用层有但 HTTP 层没暴露」、2 个零调用死端点、1 个运维端点无开关。

**明确不做**：不改退款领域模型；不改 `RefundPolicy` 决策逻辑；不动账本科目；不合并 `refund-command` 与通用退款端点（见 §4 Non-goals）；不引入 gateway / 鉴权 / 限流（全局既定裁剪）。

---

## 1. Problem：证据化的现状

### 1.1 查单不支持交易单号，且默认分支违反 ADR-0063

| # | 【现状】 | 证据 |
|---|---|---|
| 1 | `getPaymentByRef(ref)` 的逻辑是「**全数字 → 按数值主键 `id` 查**，否则 → 按 `paymentNo`」 | `PaymentApplicationService.java:333-337` |
| 2 | 因此 **`transactionId` 完全不可用于查单**，尽管 `PaymentRepository.findByTransactionId` 早已存在且持久层已实现 | `PaymentRepository.java:16`、`MybatisPaymentRepository.java:43-45` |
| 3 | 「数字就按 id 查」直接违反 ADR-0063「跨系统标识一律业务单号，**禁止数值 ID**」 | `PaymentApplicationService.java:334-335` |
| 4 | 该数值 id 分支**实际零调用方**：全部 6 个调用点传的都是 `PAYMENT_NO`（`PM...`） | `demo.html:805`（`pid = o.data.paymentNo`）、`scenario-refund.sh:32,42`、`scenario-payment-unknown.sh:31,33`、`scenario-routing.sh:76`、`traffic-gen.sh:173` |

> 结论：数值 id 分支**既违规又无用**，可直接删除，零破坏性。

### 1.2 通用退款能力「应用层有、HTTP 层没有」

| # | 【现状】 | 证据 |
|---|---|---|
| 5 | `RefundApplicationService.createRefund(CreateRefundCommand)` **已实现**（含幂等、并发串行化、额度校验、渠道调用、三态收敛） | `RefundApplicationService.java:60-124` |
| 6 | 但 `RefundController` **只有** GET / resolve / channel-callback / confirmed-facts，**没有任何 POST 创建退款** | `RefundController.java:46-74` |
| 7 | 唯一能创建退款的入口是 `POST /internal/payments/refund-command`，走 `PaymentAutoRefundService.refundByOrder`，语义是 **order 驱动的 surplus 自动退款**（必带 `transactionRefundNo` TXRF） | `PaymentRefundCommandController`、`PaymentAutoRefundService.java:63`、`RefundCommandRequest.java:14-15` |
| 8 | 后果：**用户主动申请退款 / 客服手工退款**这类「普通退款」**无处可调**——上游只能调自动退款专用入口 | — |

### 1.3 两个零调用死端点

| # | 【现状】 | 证据 |
|---|---|---|
| 9 | `POST /internal/payments/refund-attempt` —— 全仓**零调用方、零测试** | `RefundRpcController.java:29-30`；grep 全仓仅命中定义本身 |
| 10 | `POST /internal/payments/query-amount` —— 仅被 `InternalServiceAuthTest` 当作鉴权样例引用，**无生产调用方** | `RefundRpcController.java:24-25`、`InternalServiceAuthTest.java:207-211` |
| 11 | ⚠️ **关键约束**：`PaymentRefundService` **类不能删**——它被 `LocalPaymentRefundGateway` 真实依赖，后者是 `PaymentRefundGateway` 端口的实现，被 `RefundApplicationService.createRefund` 与 `RefundResultProcessor` 调用 | `LocalPaymentRefundGateway.java:19-27`、`RefundApplicationService.java:75`、`RefundResultProcessor.java:150` |

> 结论：**只删 HTTP 端点（连同 `RefundRpcController`），保留 `PaymentRefundService` 类**。误删该类会导致通用退款链路直接断裂。

### 1.4 运维端点无开关

| # | 【现状】 | 证据 |
|---|---|---|
| 12 | `GET /internal/payments/unknown` 无条件注册，返回全量 UNKNOWN 支付单分桶（明细有界 `payment.unknown.view-limit:100`） | `UnknownQueueController.java:29-42` |
| 13 | 项目**无 gateway、内部鉴权 `return true`**（全局既定裁剪）⇒ 任何能连到 808x 端口的人都能拉全量卡单列表 | 既定裁剪，见 §4 |
| 14 | ⚠️ 但它**不是死端点**：是 `runbook.md` A-04/A-05/A-06 与 `prometheus/rules/payment-alerts.yml` A-04/05/06/08/09 的**人工排障第一步** | `runbook.md:352,354`、`payment-alerts.yml:36,64,163` |

> 结论：**不能删、不能默认关**（会破坏运维链路）。正确处置是加显式开关（默认开启 = 零行为变化），把「是否暴露」变成可配置决策。

### 1.5 confirmed-facts 两处的性质（澄清，非缺陷）

| # | 【现状】 | 证据 |
|---|---|---|
| 15 | `/internal/payments/confirmed-facts` → `PaymentFactsService`（**支付**事实） | `ReconciliationFactsController.java` |
| 16 | `/internal/payments/refunds/confirmed-facts` → `RefundFactsService`（**退款**事实） | `RefundController.java:73-74` |
| 17 | 两者**不是冗余**：reconciliation-service 通过 `PaymentFactsClient` / `RefundFactsClient` 分别拉取，用于平台侧已确认资金事实与渠道对账单逐笔比对（找长短款） | reconciliation 侧两个 Feign Client |

> 结论：**保留不动**，仅补 javadoc 说明用途（FR-017）。

---

## 2. Goal

| # | 目标 | 判定方式 |
|---|---|---|
| G1 | 查单可按 `paymentNo` 或 `transactionId` 任一定位，两个都传时交叉校验 | FR-001~FR-005 + SC-001~SC-004 |
| G2 | 查单**不再接受数值主键**（删除违规分支） | INV-1 + SC-001 |
| G3 | 补上通用退款 HTTP 入口，让「普通退款」可调 | FR-010~FR-012 + SC-006 |
| G4 | 删除 2 个零调用端点，净端点数下降 | FR-014~FR-016 + SC-007 |
| G5 | 运维观测端点具备显式开关 | FR-013 + SC-008 |
| G6 | 收敛动作**不进入 GET**（保持 GET 零副作用），改用响应提示字段引导 | FR-006~FR-009 + INV-3 + SC-005 |

---

## 3. Scope

**范围内**：

- `payment-service` 的 `com.payment.payment.api` 包（控制器与 DTO）
- `PaymentApplicationService` 的单号解析逻辑
- `RefundController` 新增 POST 创建端点 + 响应字段扩展
- `PaymentResponse` / `RefundResponse` 响应结构扩展（只读提示字段）
- `RefundRpcController` 删除
- `UnknownQueueController` 加条件注册
- 相关单测、鉴权测试样例、demo 脚本、runbook 注释同步

**范围外（不触碰）**：

- `channelgateway` / `posting` / `limit` 三个域的端点
- 退款领域模型、`RefundPolicy`、`RefundStateMachine`
- `PaymentRefundService` 类本体
- 账本科目与 seed
- 渠道路由与插件

---

## 4. Non-goals

| # | 明确不做 | 理由 |
|---|---|---|
| NG1 | **不把状态收敛（resolve）放进 GET 查询接口** | GET 是安全方法（RFC 7231 §4.2.2），不得有副作用。收敛会改状态、可能触发记账与 `FINANCIAL_AUDIT`。浏览器预取/爬虫/探活/地址栏回车都会触发资金变更。改用「GET 返回 `resolvable` 提示 + POST 执行」（FR-007/FR-008） |
| NG2 | **不合并 `refund-command` 与新增的通用退款端点** | 前者承载 ADR-0067 的 TXRF/PMRF 双层互记语义（`transactionRefundNo` 必带），后者面向无交易上下文的普通退款。合并需改 ADR-0067，属人类决策边界，留待后续 Feature |
| NG3 | **不删除 `GET /internal/payments/unknown`** | 是 runbook 与 5 条 Prometheus 告警规则的人工排障入口，删除会破坏运维链路 |
| NG4 | **不引入 gateway / 鉴权 / 限流** | 全局既定裁剪（项目红线明确「内部鉴权 `return true`」）。本 Feature 只提供开关，不做暴露面治理 |
| NG5 | **不删除 `PaymentRefundService` 类** | 被 `LocalPaymentRefundGateway` 真实依赖，删了通用退款链路断裂 |
| NG6 | 不改 `confirmed-facts` 两处的拆分结构 | 支付事实 / 退款事实是两条独立资金流，reconciliation 分别拉取，非冗余 |

---

## 5. Scenarios / User Stories

### US-1 运营按交易单号查支付单（正常流）

> 作为**运营人员**，我手上只有交易单号 `TX...`，希望能直接查到支付单，而不是先去别处换算出 `paymentNo`。

**流程**：`GET /payments?transactionId=TX...` → 200，返回该笔支付单完整信息。

### US-2 双单号交叉校验（正常流 + 失败流）

> 作为**对账系统**，我同时持有 `paymentNo` 与 `transactionId`，希望用两个单号一起查，若它们指向的不是同一笔则明确报错（防串号）。

**正常流**：`GET /payments?paymentNo=PM...&transactionId=TX...` → 两者指向同一实体 → 200。
**失败流**：两者指向不同实体 → **409 CONFLICT**，错误码 `CONFLICT`，消息说明「paymentNo 与 transactionId 指向不同支付单」。

### US-3 上游发起普通退款（正常流）

> 作为**上游服务（如客服/订单）**，用户主动申请退款，我需要一个通用退款入口，而不是只能调 surplus 自动退款专用端点。

**流程**：`POST /internal/payments/refunds`，body 带 `paymentNo` / `orderNo` / `userId` / `amountMinor` / `currencyCode` / `reason` / `idempotencyKey` → 201，返回退款单。

### US-4 重复提交普通退款（幂等流）

> 同一 `idempotencyKey` 重复提交 → 返回**首次创建的同一笔退款单**，不产生第二笔，不重复调渠道。

### US-5 查询接口提示可收敛（正常流）

> 作为**运维**，我查一笔 UNKNOWN 支付单时，希望响应直接告诉我「这笔可以收敛、建议动作是什么」，而不是我要靠猜。

**流程**：`GET /payments?paymentNo=PM...` → 200，响应含 `"resolvable": true, "suggestedAction": "RESOLVE"`。运维再显式调 `POST /payments/{ref}/resolve`。

### US-6 运维端点可关闭（正常流）

> 作为**部署方**，我希望能在生产环境关闭 UNKNOWN 队列端点，避免全量卡单列表被无鉴权访问。

**流程**：配置 `payment.ops.unknown-queue.enabled=false` → 端点不注册 → 访问 404。缺省不配置时**保持开启**（零行为变化）。

---

## 6. Functional Requirements

### A 组：查单（FR-001 ~ FR-005）

| ID | 需求 |
|---|---|
| **FR-001** | 新增查询参数式查单入口 `GET /payments`，接受两个**可选**查询参数：`paymentNo`、`transactionId`。 |
| **FR-002** | 两个参数**均为空或均缺省** → 返回 **400**，错误码 `INVALID_ARGUMENT`，消息明确「paymentNo 与 transactionId 不可同时为空」。 |
| **FR-003** | **仅传一个**参数 → 按该单号查询。查不到 → **404**，错误码 `NOT_FOUND`。 |
| **FR-004** | **两个都传** → 分别查询后交叉校验：① 任一查不到 → 404；② 都查到但**不是同一实体**（`id` 不等）→ **409**，错误码 `CONFLICT`；③ 都查到且同一实体 → 200，返回该实体。 |
| **FR-005** | 保留路径变量式入口 `GET /payments/{ref}`，但**重定义 ref 语义**：ref 按业务单号前缀识别（`PM` → `paymentNo`，`TX` → `transactionId`），**删除「全数字按数值 id 查」分支**。无法识别前缀时**回退为 `paymentNo` 查询**（保持既有调用方行为）。 |
| **FR-005a** | FR-005 与 FR-001 **共用同一个单号解析器**（`PaymentRefResolver`），禁止两套逻辑。旧入口等价于「只传一个参数」的新入口。 |

> **注**：`POST /payments/{ref}/resolve` 的 `ref` 解析**同样走 `PaymentRefResolver`**（FR-006），不再自行判数字。

### B 组：收敛动作与查询提示（FR-006 ~ FR-009）

| ID | 需求 |
|---|---|
| **FR-006** | `POST /payments/{ref}/resolve` **保留**（不删除、不迁移进 GET），其 `ref` 解析委托 `PaymentRefResolver`。 |
| **FR-007** | `PaymentResponse` 新增两个**只读**字段：`resolvable`（boolean，当前状态是否可被人工收敛）、`suggestedAction`（`String`，可为 `null`；可收敛时为 `"RESOLVE"`，否则 `null`）。**该字段纯派生自状态，不得触发任何写操作**。 |
| **FR-008** | `GET /internal/payments/refunds/{refundNo}` 的响应同样新增 `resolvable` + `suggestedAction`，语义同 FR-007。 |
| **FR-009** | `POST /internal/payments/refunds/{refundNo}/resolve` **保留**（不删除、不迁移进 GET）。 |

> **FR-007/FR-008 的判定口径**：`resolvable == true` 当且仅当实体当前状态处于「允许人工收敛」集合内。支付侧 = `UNKNOWN`；退款侧 = `RefundStateMachine` 中允许 `resolve` 的状态集合（**执行方 MUST 以代码现状为准**，不得自行猜测状态名）。

### C 组：通用退款入口（FR-010 ~ FR-012）

| ID | 需求 |
|---|---|
| **FR-010** | 新增 `POST /internal/payments/refunds`（创建退款），落到既有 `RefundApplicationService.createRefund`，**不新增应用服务、不改领域逻辑**。 |
| **FR-011** | 请求 DTO **必须**携带 `idempotencyKey`（非空、非空白）。缺失或空白 → **400** `INVALID_ARGUMENT`。**资金入口无幂等键是项目红线**。 |
| **FR-012** | `POST /internal/payments/refund-command` **保留不动**（order 驱动 surplus 自动退款，承载 ADR-0067 TXRF/PMRF 双层互记）。其在 `RefundController` 之外的位置与语义均不变。 |
| **FR-012a** | 通用退款端点**不强制** `transactionNo` / `transactionRefundNo`（普通退款无交易上下文）。传入时按 `CreateRefundCommand` 既有语义处理（传入 `transactionRefundNo` 时幂等键取它）。 |

### D 组：运维端点开关（FR-013）

| ID | 需求 |
|---|---|
| **FR-013** | `UnknownQueueController` 增加 `@ConditionalOnProperty(name = "payment.ops.unknown-queue.enabled", havingValue = "true", matchIfMissing = true)`。**缺省保持注册**（零行为变化），显式置 `false` 时端点不注册。 |
| **FR-013a** | 在该类 javadoc 中明确标注：① 运维观测端点；② 仅返回 UNKNOWN 队列聚合与有界明细；③ 项目无 gateway、内部鉴权为既定 `return true`，**生产部署 MUST 置于内网或网关之后，或通过本开关关闭**。 |

### E 组：死端点清理（FR-014 ~ FR-016）

| ID | 需求 |
|---|---|
| **FR-014** | 删除 `POST /internal/payments/refund-attempt` 端点（连同 `RefundRpcController` 的该方法）。 |
| **FR-015** | 删除 `POST /internal/payments/query-amount` 端点（连同 `RefundRpcController` 的该方法）。整类 `RefundRpcController` 在 FR-014/FR-015 后无残留方法即**整体删除**。 |
| **FR-016** | **`PaymentRefundService` 类保留**（含其 `queryAmount` / `refund` 方法），仅移除 HTTP 暴露。`InternalServiceAuthTest` 中引用 `query-amount` 的鉴权样例（`:207-211` 及 6 处调用）**MUST 改用其他仍在册的内部端点**（建议改用 `GET /internal/payments/refunds/{refundNo}`），**禁止删测试或改断言迎合**（项目红线）。 |

### F 组：文档与语义澄清（FR-017）

| ID | 需求 |
|---|---|
| **FR-017** | 两处 `confirmed-facts` 端点补充 javadoc，明确说明：① 用途 = 供 reconciliation 拉取平台侧已确认资金事实，与渠道对账单比对找长短款；② 支付侧 / 退款侧是两条独立资金流，**不是冗余**；③ 可选 `?period=YYYY-MM-DD` 按创建日期过滤，缺省全量。 |

---

## 7. Business Rules / Invariants

| ID | 不变量 | 验证方式 |
|---|---|---|
| **INV-1** | 查单**不得接受数值主键**。任何入口（新 `GET /payments`、`GET /payments/{ref}`、`POST /payments/{ref}/resolve`）都不得出现 `Long.parseLong(ref)` 之类按主键定位的逻辑。 | grep `payment-service/src/main` 中 `PaymentApplicationService` 的 `getPaymentByRef` 无 `isDigit` 分支；单测断言传纯数字单号不被当 id |
| **INV-2** | 双单号交叉校验**必须指向同一实体**。两个单号都命中但 `id` 不等时**必须**失败（409），**禁止**「取第一个命中就返回」。 | 单测：构造两笔不同支付，用 A 的 paymentNo + B 的 transactionId 查询 → 409 |
| **INV-3** | **GET 端点零副作用**。查单类 GET 不得改状态、不得触发记账、不得写审计、不得调渠道。新增的 `resolvable` / `suggestedAction` 必须是纯派生字段。 | 单测：连续 2 次 `GET /payments` 响应完全一致（含 `status`），且无 `FINANCIAL_AUDIT` 写入 |
| **INV-4** | **资金创建入口必带幂等键**。通用退款端点缺失 `idempotencyKey` → 400，不得放行创建。 | 单测：body 不带幂等键 → 400；同键重复提交 → 返回同一 `refundNo`，退款单总数 = 1 |
| **INV-5** | **删除端点不得删除仍被依赖的应用服务类**。`PaymentRefundService` 与 `LocalPaymentRefundGateway` **MUST 保留**且行为不变。 | grep 确认 `PaymentRefundService` 仍存在；`PaymentRefundServiceTest` 全部保持绿 |
| **INV-6** | **运维观测端点默认保持可用**。FR-013 的 `matchIfMissing` **MUST 为 `true`**（缺省注册），禁止默认关闭——否则破坏 runbook/告警链路。 | 单测：不配置该属性时端点返回 200；置 `false` 时 404 |
| **INV-7** | **既有调用方零破坏**。所有现存调用点（`demo.html:805`、5 个 scenario/traffic 脚本）传的是业务单号（`PM...`），改造后行为必须不变。 | 全链路：起栈后跑 `scenario-refund.sh` / `scenario-payment-unknown.sh` / `scenario-routing.sh` 全部 EXIT=0 |

---

## 8. State / Lifecycle

本 Feature **不新增、不修改任何状态机**。涉及的状态语义如下（只读引用）：

| 实体 | 与本 Feature 相关的状态事实 |
|---|---|
| `Payment` | `UNKNOWN` 是唯一允许人工 `resolve` 的状态（FR-007 `resolvable` 的判定来源）。具体允许集合 **MUST 以 `PaymentUnknownResolutionService` / `PaymentStatus` 代码现状为准** |
| `Refund` | `resolve` 允许的状态集合由 `RefundStateMachine` 决定（FR-008 判定来源）。**MUST 以代码现状为准，不得猜测** |
| 新增 `resolvable` / `suggestedAction` | **不是状态字段**，是派生只读字段，不入库、不进状态机 |

---

## 9. Error / Edge Cases

| # | 场景 | 期望行为 |
|---|---|---|
| E1 | `GET /payments`（无参数） | 400 `INVALID_ARGUMENT` |
| E2 | `GET /payments?paymentNo=`（空串） | 400（空串视为未提供） |
| E3 | `GET /payments?paymentNo=PM-999` | 404 `NOT_FOUND` |
| E4 | `GET /payments?paymentNo=A&transactionId=B`，A、B 指向同一笔 | 200 |
| E5 | 同上，A、B 指向不同笔 | **409** `CONFLICT` |
| E6 | 同上，A 命中、B 未命中 | 404 `NOT_FOUND` |
| E7 | `GET /payments/{ref}` 传纯数字（如 `123`） | **不再按 id 查**；按前缀无法识别 → 回退 `paymentNo` 查询 → 通常 404。**禁止**返回 id=123 的单据 |
| E8 | `POST /internal/payments/refunds` body 缺 `idempotencyKey` | 400 `INVALID_ARGUMENT` |
| E9 | 同上，`amountMinor <= 0` | 400（沿用 `RefundPolicy` / 既有校验，不新增规则） |
| E10 | 同上，支付单非 `SUCCEEDED` | 退款单被 `reject`（既有 `createRefund` 行为，本 Feature 不改） |
| E11 | 同 `idempotencyKey` 重复提交 | 返回首次创建的同一笔（既有 `findByIdempotencyKey` 短路） |
| E12 | `payment.ops.unknown-queue.enabled=false` 时访问 unknown 端点 | 404 |
| E13 | 删除端点后访问 `refund-attempt` / `query-amount` | 404（端点不存在） |

---

## 10. Idempotency

| 场景 | 幂等键 | 行为 |
|---|---|---|
| 通用退款创建（FR-010） | 请求 body 的 `idempotencyKey` | `createRefund` 内 `refundRepository.findByIdempotencyKey(key)` 命中即返回既有单（**既有逻辑，本 Feature 不改**） |
| 通用退款创建（传 `transactionRefundNo` 时） | **`transactionRefundNo` 优先**（`CreateRefundCommand` 既有语义：`transactionRefundNo != null ? transactionRefundNo : idempotencyKey`） | 同上 |
| 查单类 GET | 不适用（无副作用） | INV-3 |
| resolve 类 POST | 沿用既有收敛幂等（本 Feature 不改） | — |

> ⚠️ **执行方注意**：FR-011 只校验「`idempotencyKey` 非空」，**不得**改变 `createRefund` 内部「`transactionRefundNo` 优先」的键选择逻辑。

---

## 11. Acceptance Criteria

| ID | 验收标准 | 验证方式 |
|---|---|---|
| **SC-001** | `GET /payments?transactionId=TX...` 能查到支付单；`PaymentApplicationService` 中**不存在**按数值 id 查单的分支 | 单测 + grep |
| **SC-002** | `GET /payments` 无参数 → 400；单参数命中 → 200；未命中 → 404 | 单测（E1/E3） |
| **SC-003** | 双参数指向同一笔 → 200；指向不同笔 → 409 | 单测（E4/E5） |
| **SC-004** | 旧入口 `GET /payments/{ref}` 传业务号行为不变；传纯数字**不再**按 id 命中 | 单测（E7） |
| **SC-005** | `GET /payments` 响应含 `resolvable` / `suggestedAction`；连续两次 GET 响应完全一致且无审计写入 | 单测（INV-3） |
| **SC-006** | `POST /internal/payments/refunds` 能创建退款；同 `idempotencyKey` 重复提交返回同一 `refundNo`；缺幂等键 → 400 | 单测（E8/E11/INV-4） |
| **SC-007** | `refund-attempt` 与 `query-amount` 端点访问 → 404；`PaymentRefundService` 类仍存在且其测试全绿 | 单测 + grep（INV-5） |
| **SC-008** | unknown 端点：不配置开关 → 200（保持可用）；置 `false` → 404 | 单测（INV-6） |
| **SC-009** | 全量单测零回归（payment-service 基线 583，允许新增，不得减少） | `./mvnw -B -pl payment-service -am test` |
| **SC-010** | 全链路：`scenario-refund.sh` / `scenario-payment-unknown.sh` / `scenario-routing.sh` 全部 EXIT=0（既有调用方零破坏） | 起栈后跑脚本（INV-7） |

---

## 12. Dependencies

**前置 Feature（均已在 master）**：

- [036-channel-plugin-microkernel](../036-channel-plugin-microkernel/spec.md) — 渠道插件内核，本 Feature 不涉及
- [037-channel-gateway-boundary](../037-channel-gateway-boundary/spec.md) — 渠道网关边界，本 Feature 不涉及
- [038-payment-service-package-boundary](../038-payment-service-package-boundary/spec.md) — **包路径基准**：本 Feature 的所有类路径以 `com.payment.payment.**` 为准（非旧的 `com.payment.refund.**`）
- [039-wechat-pay-channel-plugin](../039-wechat-pay-channel-plugin/spec.md) — 微信插件，本 Feature 不涉及

**ADR**：

- **ADR-0063 跨系统标识** — INV-1 的直接依据（禁止数值 ID 出服务边界）
- **ADR-0067 两层退款单** — FR-012 保留 `refund-command` 的依据（TXRF/PMRF 双层互记）
- **ADR-0016 退款恒按全退** — 通用退款沿用既有 `RefundPolicy` 决策，本 Feature 不改

**服务依赖**：

- `reconciliation-service` — confirmed-facts 的消费方（FR-017 澄清，不改契约）
- `order-service` — `refund-command` 的调用方（FR-012 保留，不改）
- `mock-channel-web`（:8091）— `demo.html:805` 查单调用方（FR-005 保持兼容）

---

## 13. Related Documents

- [payment-service 系统设计](../../../architecture/systems/payment-service.md) — L0，**本 Feature 完成后需同步端点表**（属 L0 漂移处置，见 AGENTS.md）
- [runbook](../../../operations/runbook.md) — A-04/A-05/A-06 依赖 unknown 端点（FR-013 不得默认关闭）
- [Spec 规范](../../../standards/spec-standard.md) — 本文档遵循其 13 章骨架
- [Constitution](../../../../.specify/memory/constitution.md) — 人类决策边界

---

## 14. 决策记录（Decision Log）

| ID | 决策 | 理由 | 备选（未采纳） |
|---|---|---|---|
| **D1** | 查单**同时保留** `GET /payments/{ref}` 与**新增** `GET /payments`（查询参数式），两者共用 `PaymentRefResolver` | 旧入口有 6 个真实调用点，删除需同步改 demo 页面 + 5 个脚本，属破坏性变更；保留且修正语义成本最低、风险最小。净端点数仍下降（+1 新增 / −2 删除） | 只保留查询参数式、删除 `{ref}`：破坏性变更，改动面更大 |
| **D2** | **不**把 resolve 迁移进 GET | GET 是安全方法，不得有副作用（RFC 7231 §4.2.2）。收敛会改状态/触发记账/写审计，浏览器预取或地址栏回车即可触发资金变更。改用响应提示字段引导 | 在 GET 内自动收敛：**否决** |
| **D3** | 通用退款端点与 `refund-command` **并存** | 后者承载 ADR-0067 的 TXRF/PMRF 双层互记，合并需改 ADR（人类决策边界） | 合并为单一端点：**留待后续 Feature** |
| **D4** | 只删 HTTP 端点，**保留** `PaymentRefundService` 类 | 该类被 `LocalPaymentRefundGateway` 真实依赖（→ `createRefund` / `RefundResultProcessor`）。误删会直接断裂通用退款链路 | 连类一起删：**否决**（会导致 FR-010 无法实现） |
| **D5** | unknown 端点**加开关但默认开启**（`matchIfMissing = true`） | 是 runbook 与 5 条告警规则的排障入口，默认关闭会破坏运维链路。开关只提供「生产可关闭」的能力 | 默认关闭 / 直接删除：**否决** |
| **D6** | `confirmed-facts` 两处**保留不动**，仅补 javadoc | 支付事实 / 退款事实是两条独立资金流，reconciliation 分别拉取，非冗余 | 合并成一个端点：**否决** |

---

## 15. 待确认（人类决策边界）

| # | 事项 | 说明 |
|---|---|---|
| **Q1** | `GET /payments/{ref}` 是否在后续版本废弃删除 | 本 Feature 保留并修正语义（D1）。是否彻底删除旧入口、改 demo 页面与 5 个脚本，需负责人决定 |
| **Q2** | 通用退款端点是否需要 `X-Admin-Token` 类管控 | 当前内部鉴权 `return true`（既定裁剪）。若负责人认为普通退款需额外管控，需单开 Feature（涉及鉴权，属 Non-goal NG4） |
| **Q3** | 是否需新增 ADR | 本 Feature 未新增架构决策（均为既有 ADR 的落实）。若负责人认为「查单双单号」需升格为架构约束，则新建 ADR |
