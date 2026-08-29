# ADR-0012 ~ ADR-0015：支付可靠性（Feature 003）实现期决策

**日期**: 2026-08-28
**状态**: **Accepted**（2026-08-29 负责人确认；其中 0012 / 0013 / 0014 为**修订后接受**）
**关联**: ADR-0003~0007（已 Accepted）、`docs/specs/003-payment-reliability/`

> 本文件记录 **003 实现期**遇到的分歧/歧义点。按负责人指示：实现先按「最简方式」推进，
> 同时把决策点记录在此供决策。**确认前这些决策已生效运行，确认后如被否决需回滚对应实现。**

> **2026-08-29 负责人裁决（已落地）**：
> 1. **ADR-0012 修订接受** —— 错误分类的**唯一来源**是下游响应里的**双响应码**（业务响应码 + 通信响应码）；
>    **通信响应码不为 `SUCCESS` 一律重试**（含超时：超时算通信失败），重试耗尽才进 `UNKNOWN`；
>    `UNKNOWN` 状态本身**不重试**，交 US2 主动查询收敛。
> 2. **ADR-0013 / 0014 修订接受** —— **重试不落库**：调用下游失败时**在本次请求里直接发起重试**；
>    删除 `next_retry_at` 列、`findRetryableDue` 查询与 `PaymentRetryScheduler` 调度器。
> 3. **超时口径统一**：出站 **RPC 1s**、对外 **HTTP 1.5s**（全服务统一，见「超时口径」小节）。
> 4. 其余（ADR-0015 及 0008~0011）按原提议接受。

---

## ADR-0012：重试的错误分类来源（双响应码；通信失败一律重试）

**状态**: **Accepted（修订后接受）**

### 背景

FR-005 要求「幂等调用因**瞬时**错误失败时重试」，FR-006 要求「**硬拒绝**不重试」。
既有 `ChannelResult` 只有 `SUCCESS / FAILURE / UNKNOWN` 三态 + 一个自报的 `errorType`，
**分类由调用方自报**，无法回答「这个失败到底是渠道拒绝还是网络没通」。

### 决策（负责人裁决）

#### 1. 下游响应统一携带双响应码

任何出站调用的响应 **MUST** 同时携带两个码（枚举位于 `common-core`）：

| 码 | 枚举 | 含义 | 取值 |
|---|---|---|---|
| **通信响应码** | `TransportCode` | 这次**通信**有没有成功完成 | `SUCCESS` / `TIMEOUT` / `CONNECTION_ERROR` / `IO_ERROR` / `SERVER_ERROR`(5xx) / `PROTOCOL_ERROR`(4xx·报文不可解析) / `UNKNOWN` |
| **业务响应码** | `BusinessCode` | 通信成功后，**下游业务**怎么处理 | `SUCCESS` / `DECLINED` / `INSUFFICIENT_FUNDS` / `RISK_REJECTED` / `INVALID_REQUEST` / `DUPLICATE` / `UNKNOWN` |

> 通信没成功时**没有**业务结论，此时 `businessCode` 记为 `UNKNOWN`（不得伪造业务结论）。

#### 2. 分类矩阵（重试判定的**唯一来源**）

| 通信码 | 业务码 | 判定 | 重试 | 支付落地状态 |
|---|---|---|---|---|
| `SUCCESS` | `SUCCESS` | 成功 | — | `SUCCEEDED` |
| `SUCCESS` | 非 `SUCCESS`、非 `UNKNOWN` | **业务硬拒绝** | **否** | `FAILED` |
| `SUCCESS` | `UNKNOWN` | 通信成功但业务结论不明 | **否** | `UNKNOWN`（由主动查询收敛） |
| **非 `SUCCESS`**（含 `TIMEOUT`） | 任意 | **通信失败** | **是**（内联，至上限） | 重试耗尽 → `UNKNOWN` |

即：**重试判定只看通信响应码**——`transportCode != SUCCESS` 就重试，业务响应码只决定「通信成功后算成功还是失败」。

#### 3. 「UNKNOWN 不重试」的准确含义

本 ADR 标题里的「UNKNOWN 不重试」指**状态**而非**原因**：

- 超时 / 断连 **不是**「不重试」，它是 `TransportCode.TIMEOUT`，**要重试**；
- 当重试次数耗尽、或通信成功但业务结论不明时，支付进入 `UNKNOWN` 状态；
- **已进入 `UNKNOWN` 的支付不再发起扣款重试**，改由 US2 主动查询（`ChannelQueryService`）收敛，
  避免重复扣款（Constitution §V.7 不猜成败）。

### 理由

- **分类不再自报**：调用方不再自己拍脑袋说「这是瞬时错误」，而是由客观的双码推导，
  消除「网络超时被误标成硬拒绝」这类根因丢失。
- **超时算通信失败**：渠道调用全程携带同一幂等键（同 `paymentId` + 同 `attemptId`），
  重放不会重复扣款，重试收益大于风险（负责人裁决）。
- **UNKNOWN 状态不重试**：守住 Constitution §V「不把未确认当成功或失败」，收敛交给查询/对账。

### 备选（已否决）

- 方案 B：`UNKNOWN`（超时）不重试、直接进 UNKNOWN —— 负责人否决：幂等键已保证重放安全，
  放弃重试会让大量可自愈的网络抖动变成人工工单。

### 影响

- `ChannelResult` 结构变更：新增 `transportCode` / `businessCode` 两个分量，`errorType()` 改为**派生方法**。
- `MockChannelAdapter.Scenario` 新增 `TIMEOUT` 语义调整 + `TRANSPORT_ERROR`。
- 删除自报型工厂 `transientFailure(...)`，改为 `transportFailure(TransportCode, reason)`。

---

## ADR-0013：重试调度的载体（不落库，请求内联重试）

**状态**: **Accepted（修订后接受）**

### 背景

原决策为 `payment_attempts` 增加 `error_type` + `next_retry_at` 两列，由 `PaymentRetryScheduler`
按 5s 间隔扫描 `next_retry_at <= now` 的尝试并重放。负责人认为：**为重试而落库 + 起调度器，
对 MVP 是过度设计**——调用下游失败时，在本次请求里直接退避重试即可。

### 决策（负责人裁决）

- **不持久化重试计划**：删除 `next_retry_at` 列、`PaymentAttempt.nextRetryAt` 字段、
  `PaymentAttemptRepository.findRetryableDue(Instant)` 查询、`PaymentRetryScheduler` 调度器
  与 `payment.reliability.retry-scan-interval-ms` 配置。
- **请求内联重试**：`PaymentRetryService.chargeWithRetry(ChargeRequest)` 在**同一个 HTTP 请求线程内**
  循环调用 `channel.charge`，按退避序列等待，直到成功 / 达到上限 / 出现不可重试结果。
- **保留 `error_type` 列**：它记录「最后一次失败的分类」用于观测与排障，**不是**调度载体，予以保留。
- **退避与上限沿用原口径**：`ReliabilityConfig.retryBackoff`（默认 1s/2s/4s），
  第 n 次重试取序列第 n 项（越界取最后一项）；上限 `retryMaxAttempts`（默认 3，**含首次**）。
- 重试期间**不写数据库**：每次重放都不落库，只在最终收敛时由 `applyAndPersist` 落一次库。

### 理由

- 少一张调度表状态、少一个调度器、少一类「扫描间隔 vs 退避精度」的调参负担。
- 重试窗口内进程重启 = 该次尝试停在 `PROCESSING`，由 **US1 超时扫描**（30s）兜底进 `UNKNOWN`，
  不会丢事实（只是收敛慢一点）。
- 与 ADR-0014「同一 attempt 重放」天然一致：内联循环本来就是同一 attempt。

### 影响

- `payment_attempts` 表**删除** `next_retry_at` 列与 `idx_attempts_next_retry_at` 索引
  （生产库需执行增量 DDL，见 `deployment/schema/03-payment-schema.sql`）。
- 创建支付接口的最坏响应时长上升为「退避总和」（默认 1s+2s=3s）+ 渠道耗时；
  在设定的 1.5s HTTP 超时下最坏约 4.5s，属可接受范围（负责人已知晓）。
- 进程崩溃时「已重试但没落库」的中间态不可观测，只能靠渠道侧对账发现（已记录为已知代价）。

---

## ADR-0014：重试的幂等与事务边界（同 attempt 重放）

**状态**: **Accepted**

### 背景

`PaymentAttempt` 状态机中 `FAILED` 是终态；若在通信失败时先 `attempt.fail(...)`，后续重试会被终态吸收。

### 决策

- 重试时**在同一 attempt 上重放** `channel.charge`（同 `paymentId` + 同 attempt，幂等键不变），
  **不创建新 attempt 行**。
- 重试成功 → 走既有 `applyAndNotify`（`attempt.accept` + `succeed`，只推进一次下游）。
- 重试耗尽 → `ChannelResult.status = UNKNOWN`，`reason = RETRY_EXHAUSTED`，
  经 `PaymentResultApplier` 落到 `payment.markUnknown("RETRY_EXHAUSTED")`，
  并发 `payment.retry_exhausted` 计数，**不进 FAILED**（FR-007）。
- 通信失败期间（内联重试循环中）**不落库**，支付对外保持 `PROCESSING`。

### 理由

- 与 ADR-0013 的内联重试天然一致：循环体就是同一 attempt 的重放。
- 不新增 attempt 行，「一次支付尝试的重试历史」集中在一行，便于观测。

### 影响

瞬时失败期间支付保持 `PROCESSING`，**不立即可见为失败**——运营需以「重试中」理解该状态。

---

## ADR-0015：UNKNOWN 真实收敛时长的度量方式

**状态**: **Accepted**

### 背景

`PaymentUnknownResolutionService` 曾以 `Duration.ZERO` 记录 `payment.unknown.duration`，
因为领域聚合未携带「进入 UNKNOWN 的时刻」。`BaseEntity.updatedAt` 会被后续保存覆盖，无法还原。

### 决策

- 在 `payments` 增加 `entered_unknown_at` 列，在所有 `markUnknown` 入口（超时扫描、重试耗尽、业务结论不明）写入。
- 收敛时以 `now - enteredUnknownAt` 产出真实时长。

### 理由

这是能产出**真实**时长的字段最少的方式；复用 `updatedAt` 会产出错误数据。

### 影响

`Payment` 领域新增 `enteredUnknownAt` 字段 + `rehydrate` 签名扩展；`payments` 新增 1 列。

---

## 超时口径（2026-08-29 负责人裁决 · 全服务统一）

| 层级 | 超时 | 适用 | 落地位置 |
|---|---|---|---|
| 出站 **RPC**（服务间 OpenFeign） | **1s**（connect 1s / read 1s） | order→catalog/payment、payment→order/fulfillment/ledger、refund→payment/entitlement、fulfillment→entitlement、reconciliation→payment/refund、settlement→merchant/reconciliation | 各服务 `application.yml`：`spring.cloud.openfeign.client.config.default.connect-timeout/read-timeout: 1000` |
| 对外 **HTTP**（渠道 / 外部系统） | **1.5s** | `payment-service` 渠道调用 | `payment.channel.http-timeout-ms: 1500` |

理由：内部 RPC 链路短、可预期，1s 足够且能快速失败避免连接池耗尽；
外部渠道网络不可控，给到 1.5s 容忍抖动。两者都**必须显式配置**，禁止依赖框架默认值（Constitution §V.6）。

---

## 负责人裁决记录（2026-08-29）

| ADR | 一句话决策 | 裁决 |
|---|---|---|
| 0012 | 错误分类由**双响应码**决定；通信码非 `SUCCESS`（含超时）一律内联重试；`UNKNOWN` 状态不重试 | **修订后接受** |
| 0013 | **重试不落库**，在本次请求内直接退避重试；删除 `next_retry_at` / `findRetryableDue` / 调度器 | **修订后接受** |
| 0014 | 重试在**同一 attempt** 重放，不新建 attempt | **接受** |
| 0015 | 新增 `entered_unknown_at` 列度量真实收敛时长 | **接受** |
| — | 超时口径：出站 RPC **1s**、对外 HTTP **1.5s** | **接受（新增）** |
