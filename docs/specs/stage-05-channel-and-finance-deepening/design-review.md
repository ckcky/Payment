# Stage 05 Design Review

**审查对象**：`docs/specs/stage-05-channel-and-finance-deepening/stage-design.md`（v0.1 Draft）+ 其覆盖的六面能力（渠道 / 账务 / 对账结算 / 可靠性 / 可观测 / 测试）
**审查依据**：**当前仓库真实代码**（`payment-service` / `ledger-service` / `reconciliation-service` / `settlement-service` / `order-service` / `deployment/schema`）、spec 030、ADR-0072~0076、Constitution v2.3.0
**审查日期**：2026-09-19
**审查性质**：**只读审查**。本轮不写业务代码、不改数据库、不改 API、不新增依赖、不新增服务、不改现有 ADR。
**事实来源优先级**：`代码 > Schema > 测试 > L0 文档 > L2 Spec > L3 ADR > stage-design.md`。
> 凡本文件与 `stage-design.md` 不一致处，**一律以代码为准**，并在 §11 记为冲突。

---

## 1. Review Summary

### 1.1 结论

**`stage-design.md` 的方向判断成立，可以作为阶段输入；但其中 3 处「现状」描述需要修正，且其建议的 Feature 顺序需要插入一个前置收口轮。**

一句话：**领域模型约束（`Transaction 1:N Payment`、`Payment 1:1 PaymentAttempt`）在当前代码中「事实上成立」，但既没有被数据库结构强制，也没有被文档统一表述；同时发现 1 处与已修复缺陷同源的遗留缺陷（账本幂等键双重前缀），必须在任何新能力之前收口。**

### 1.2 审查判定

| 面 | 判定 | 说明 |
|---|---|---|
| 领域模型 | ⚠️ **有条件通过** | 关系成立，但「1:1」无结构强制、文档自相矛盾（C-02/C-03） |
| Payment / PaymentAttempt 职责分离 | ✅ 通过 | 两层职责清晰，同事务落库，ArchUnit INV-4/INV-5 有门禁 |
| 状态机 | ⚠️ **有条件通过** | 迁移入口唯一、终态吸收正确；但 `UNKNOWN` 缺终态出口、`ACCEPTED` 语义悬空（C-06/C-10） |
| 渠道架构（Router/Registry） | ✅ 通过 | 确定性、前后向分离、fail-fast 均落实；1 处防御性死代码 |
| 回调 | ❌ **不通过（接真实渠道前）** | 验签恒放行、金额不校验、渠道流水号不校验归属（C-04/C-05） |
| 账务总账 | ❌ **不通过** | 账本幂等键双口径且其中一条是**已修复缺陷的遗留**（C-01，最高优先级） |
| 对账 | ⚠️ **有条件通过** | 四核对完整；结算事实仅取「已匹配项」是隐含耦合（C-13），SETTLEMENT sourceId 用数值 id（C-08） |
| 结算 | ⚠️ **有条件通过** | 门禁 fail-closed 正确；**负净额的会计处理未定义**（C-07） |
| 可靠性 | ✅ 通过（含 2 项已识别缺口） | 11 类故障走查结果全部自洽，无「猜成败」路径 |
| 可观测 / 测试 | ✅ 通过（stage-design 描述准确） | 7 条告警规则、五层测试、ArchUnit 8/8 核实无误 |

### 1.3 `stage-design.md` 现状描述的修正（3 处）

| # | stage-design 原文 | 代码事实 | 影响 |
|---|---|---|---|
| **A-1** | §4.1「幂等：幂等键（`PAYMENT:<key>` / `REFUND:<key>` / `SETTLEMENT:<batchId>`）」 | 支付侧有**两个**口径：`PAYMENT:{idempotencyKey}`（同步路径）与 `PAYMENT:PAYMENT:{paymentNo}`（回调/收敛路径） | 把「双口径」写成单一口径，会掩盖 C-01 |
| **A-2** | §4.3「记账失败……靠重试 / 对账 / G3 的待记账清单兜底」 | 无重试机制；`FeignLedgerPostingGateway` **吞掉异常**只记指标+日志；对账侧 `MISSING_POSTING`（BLOCKER）**能发现不能修** | 「靠重试兜底」不成立，须改写为「靠对账发现 + 人工补记」 |
| **A-3** | §3.1 第 2 条「`ChannelResult`……无凭证载体」 | 正确；但未指出 `ChannelResult.accepted()` **已携带** `channelReference` 而在 UNKNOWN 分支被丢弃 | 遗漏一处真实缺陷（C-10 关联） |

其余现状条目（17 reactor 条目、端口 8081~8091 含 8085 空号、5 科目、11 类审计差异、7 条告警规则、ArchUnit 8/8、H2 + Testcontainers 0 处使用）**逐条核实无误**。

---

## 2. Domain Model Validation

### 2.1 `Transaction 1:N Payment` —— ✅ 成立（但 Transaction 不是本服务的聚合）

**证据链**：

| 证据 | 位置 | 说明 |
|---|---|---|
| `payments.transaction_id` + `idx_payments_txn_seq (transaction_id, attempt_seq)` | `deployment/schema/03-payment-schema.sql` | 一个 `transaction_id` 下可有多行 `payments`，`attempt_seq` 区分 |
| `attemptSeq = countByTransactionId(cmd.transactionId()) + 1` | `PaymentPersistence.java:73` | 第二笔起序号递增 |
| `idempotencyKey = "payment:" + orderNo + ":" + routedChannelCode + ":" + attemptSeq` | `PaymentPersistence.java:74` | **换渠道 ⇒ 新 Payment**（这是「渠道切换」的实现基础） |
| `private int attemptSeq = 1` | `Payment.java:35` | 聚合内持有 |

**关键结论**：**渠道切换 = 同一 Transaction 下新建一个 Payment**，而不是「同一 Payment 下多个 attempt」。这与用户给定的最高优先级约束**完全一致**，无需修改模型。

**但必须点明一个建模事实**：**`Transaction` 在 payment-service 内不是聚合根、没有实体、没有表**——它只是 `payments.transaction_id` 一列（`String`），值由 order-service 侧生成并传入（`CreatePaymentCommand.transactionId()`）。因此：

- `Transaction 1:N Payment` 是**跨服务逻辑关系**（order 拥有 Transaction，payment 拥有 Payment），不是 payment-service 内部的聚合关系；
- 「Transaction 的 `N`」在 payment-service 侧只能通过 `countByTransactionId` **推导**，无法约束（例如 order 侧传错 `transactionId`，payment 侧会静默多建一笔 `attemptSeq=1`）；
- 因此**「一交易最多一笔有效支付」这条业务规则，在本服务内没有任何强制点**。当前它靠 order-service 的业务流程保证。

> **是否需要人工决策**：否（不改模型）。但需在 L0 文档显式写明「Transaction 为 order 侧聚合、payment 侧仅持有引用」，避免后续误以为可在 payment 侧加 Transaction 约束。

### 2.2 `Payment 1:1 PaymentAttempt` —— ⚠️ 事实上成立，但**无结构强制**且**文档自相矛盾**

**支持「1:1」的证据**：

| 证据 | 位置 |
|---|---|
| `Payment.currentAttemptId` 是**单值**（`Long`，非集合） | `Payment.java:37` |
| 幂等命中时回放**唯一**的 PAYMENT attempt，取不到即抛 `INTERNAL_ERROR` | `PaymentPersistence.java:138-142` |
| `findPaymentAttempt` 用 `.filter(TYPE_PAYMENT).findFirst()` | `ChannelAttemptRecorderImpl.java:86-90` |
| `resolveRecordedChannel` 取 `.findFirst()` | `ChannelQueryService` |
| 重试**不新建 attempt 行**，只在同一行 `retryCount++` | `PaymentRetryService.java:71-91` + ADR-0013/0014 |
| `PaymentResultApplier` 只推 payment，attempt 侧由 `converge` 推 | `PaymentResultApplier.java:31-37` |

**反对「1:1 被强制」的证据**：

| 证据 | 位置 | 说明 |
|---|---|---|
| `payment_attempts` **没有** `UNIQUE(payment_no, attempt_type)` | `03-payment-schema.sql` | 只有 `KEY idx_attempts_payment_no` / `KEY idx_attempts_payment_type`（**普通索引，非唯一**） |
| `payment_attempts` 用 `attempt_type` 同时承载 `PAYMENT` 与 `REFUND` | `PaymentAttempt.java:20-21` | 使「1:1」严格说是「Payment 1:1 PAYMENT-type attempt」，**MySQL 不支持 partial unique index**，无法用一条 DDL 表达 |
| 文档表述冲突 | `technical-solution.md §4.2` 写「`Payment : PaymentAttempt = 1:1（支付尝试）+ 1:N（退款尝试）`」；`systems/payment-service.md §2.1` 写「`Payment (1) ─ (N) PaymentAttempt`」 | 两处 L0 文档**互相矛盾** |

**判定**：
- **代码行为**：唯一一个 PAYMENT-type attempt，1:1 成立；
- **结构强制**：**不成立**——任何一条 `INSERT INTO payment_attempts` 都可以为一个 `payment_no` 造出第二条 `PAYMENT` 行，`ChannelQueryService.resolveRecordedChannel` 的 `.findFirst()` 会**静默取第一条**，产生「查询走 A、记账走 B」的幽灵缺陷（同 `SpringChannelRegistry` 注释里描述的那类失败模式）；
- **测试覆盖**：`PaymentStateMachineTest` 覆盖状态机，但**没有**「同一 paymentNo 两条 PAYMENT attempt ⇒ 应被拒绝」的测试。

**候选方案**：

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A. 应用层强断言（推荐）** | 在 `ChannelAttemptRecorderImpl.openPaymentAttempt` 内先 `findPaymentAttempt(paymentNo).isPresent()` ⇒ 抛 `INTERNAL_ERROR`；补一条单测 | 零 schema 变更、零迁移风险、立即可测 | 并发下仍可能双写（需配合 `PaymentPersistence` 的同一事务 + 唯一键兜底） |
| B. Schema 加唯一约束 | 拆表（`payment_attempts` / `refund_attempts`）或加生成列 `uk(payment_no, attempt_type)` | 结构级强制，最彻底 | **数据库结构变更 = 人类决策边界**；涉及存量数据、迁移脚本、4 处代码改动 |
| C. 只改文档 | 统一 L0 表述为「1:1（PAYMENT）/ 1:N（REFUND）」 | 零成本 | 不解决 `.findFirst()` 的静默取首风险 |

**推荐**：**A（立即） + C（立即）**；**B 单独立项**（若下一阶段要接真实渠道并做严肃对账，B 的价值会上升）。
**是否需要人工决策**：**B 需要**（Schema 变更）；A/C 不需要。

### 2.3 四场景走查（模型层验证）

#### 场景 A —— 首次支付

| 步骤 | 代码路径 | 数据结果 |
|---|---|---|
| 1. 选路 | `PaymentApplicationService.resolveChannelCode` → `ChannelRouter.route(RouteContext)`，**在建单之前** | 无写入 |
| 2. 建单 | `PaymentPersistence.insertPending`：生成 `idempotencyKey=payment:{orderNo}:{code}:1` → `payments` 插入（`PENDING`）→ `limitGate.acquire` → `openPaymentAttempt` → `payments` 更新为 `PROCESSING`（`current_attempt_id` 已设） | `payments` 1 行 `PROCESSING`；`payment_attempts` 1 行 `PENDING` |
| 3. 调渠道 | `retryService.chargeWithRetry(ChargeRequest(paymentNo, attemptId, amount, currency, routedCode))` —— **在事务之外** | 无写入（重试期间不落库） |
| 4. 应用结果 | `applyAndPersist`：① `converge(attempt)` → ② `applyPayment(payment)` → ③ 同事务 save 两表 | `SUCCEEDED` / `FAILED` / `UNKNOWN` |
| 5. 后置 | SUCCESS ⇒ MQ 或同步 Feign 通知履约 → `postPaymentCapture` | order 侧 + ledger |

**结论**：链路自洽。**唯一观察点**：步骤 3 在事务外是**有意设计**（避免 DB 连接被网络调用占用，`PaymentPersistence` 类注释 P0-3），代价是「建单已提交、渠道未调用」的中间态存在，由 `TimeoutScanner` 30s 后转 `UNKNOWN` 兜底。可接受。

#### 场景 B —— 渠道切换

| 步骤 | 代码路径 | 数据结果 |
|---|---|---|
| 1. 第二次下单（同 `transactionId`） | `countByTransactionId` 返回 1 → `attemptSeq = 2` | — |
| 2. 幂等键 | `payment:{orderNo}:{新渠道码}:2` | 与首笔**不同** ⇒ 新建 |
| 3. 建单 | 新 `payments` 行 + 新 PAYMENT attempt 行 | `payments` 2 行（`attempt_seq` 1/2） |

**结论**：✅ 与「`Transaction 1:N Payment`」一致。**首笔支付单不被改写、不被复用**，符合「事实不回滚」。

**但存在一个语义缺口**：**首笔 Payment 若停留在 `UNKNOWN`（例如首渠道超时），切换渠道后两笔 Payment 同时「活着」**——首笔仍会被 `ChannelQueryService` 继续查询收敛。若首笔最终收敛为 `SUCCEEDED`，则**同一 Transaction 下有 2 笔成功的 Payment**。当前系统：
- 记账：两笔各自记账（幂等键不同）⇒ **CUSTOMER_CASH 双倍借记**；
- 结算：对账事实按 `reference` 匹配 ⇒ 若渠道账单只有一笔，另一笔成 `PLATFORM_ONLY`；
- 谁负责判定「这是重复支付、需要退一笔」？——**没有代码负责**（`closeByOrderCancelled` 只处理订单取消，不处理「换渠道后旧单成功」）。

> **记为 C-11。** 严重度 🟠。这不是新阶段引入的，是**当前已存在的跨渠道切换语义空洞**，且 stage-design 未识别。

#### 场景 C —— 同渠道超时

| 步骤 | 代码路径 | 数据结果 |
|---|---|---|
| 1. `charge` 返回 `transportCode=TIMEOUT` | `ChannelResult.timeout()` ⇒ `Status.UNKNOWN`，`retryable()=true` | 无写入 |
| 2. 内联重试 | `chargeWithRetry` 循环，退避序列，**同 attempt 同渠道重放** | 无写入 |
| 3. 重试耗尽 | `withReason("RETRY_EXHAUSTED")` + `payment.retry_exhausted` 指标 | — |
| 4. 应用 | `applyAndPersist`：`converge` → UNKNOWN 分支 → `attempt.markUnknown`；`applyPayment` → `payment.markUnknown` | `payments.status=UNKNOWN`、`entered_unknown_at=now`；`payment_attempts.status=UNKNOWN`、`retry_count=N`、`error_type=TRANSIENT` |
| 5. 30s 后 | `TimeoutScanner` 只扫 `PROCESSING` ⇒ **已 UNKNOWN 的不重复处理** | — |
| 6. 15s 周期 | `ChannelQueryScheduler` → `queryRound()` → `resolveRecordedChannel`（**按 attempt.channel_code，经 registry，绝不过 Router**）→ `queryStatus` | `query_attempts++` |
| 7. 收敛 | 非 UNKNOWN ⇒ `resolution.resolve(...)` → `PaymentResultProcessor.applyAndNotify` | 终态 + 记账 + 通知履约 |

**结论**：✅ 全链路无「猜成败」。**唯一缺口**：步骤 6 的 `queryAttempts` 达上限后停止自动查询 ⇒ 支付**永久停留 `UNKNOWN`**，且 `Payment.close()` 不允许从 `UNKNOWN` 关闭（只有 `closeByOrderCancelled` 可以）⇒ **无终态出口**。见 C-06。

#### 场景 D —— 重复 Callback

| 步骤 | 代码路径 | 数据结果 |
|---|---|---|
| 1. 第一次 SUCCESS 回调 | `ChannelCallbackController` → `PaymentCallbackService.handleCallback` → `processor.applyAndNotify`：`converge` 推 attempt、`applyPayment` 返回 `true` | 状态迁移 + 记账 + 通知履约 |
| 2. 第二次同回调 | `applyPayment`：`transitionTo(SUCCEEDED)` 命中 `status == target` ⇒ 返回 `false` | `changed=false` ⇒ **不记账、不通知履约** |
| 3. 计数 | `payment.duplicate_callback` | — |

**结论**：✅ 幂等吸收正确，记账与履约**恰好一次**（`PaymentCaptureLedgerPostingTest.duplicateSuccessCallbackDoesNotRepostLedger` 已钉死）。

**但注意两点**：
1. 第二次回调**仍会执行** `attemptRecorder.converge` 与 `attemptRecorder.save`（`applyAndNotify` 无条件收敛 attempt）⇒ 产生一次**无业务意义的 UPDATE**（乐观锁 `version` 递增）。不是缺陷，但会让「`version` 变化」不再等价于「状态变化」，排障时勿误读。
2. 第二次回调的 `channelReference` **若与第一次不同**，`converge` 会尝试 `accept(newRef)`；由于 attempt 已是 `SUCCEEDED`，`accept()` 返回 `false` 被吸收 ⇒ **不会覆盖**。✅ 安全。

---

## 3. Payment / PaymentAttempt Validation

### 3.1 职责边界是否成立

| 维度 | `Payment`（支付层） | `PaymentAttempt`（渠道层） | 判定 |
|---|---|---|---|
| 语义 | 平台侧支付意图与资金动作状态 | 单次渠道交互的历史记录 | ✅ 清晰 |
| 状态机 | `PENDING/PROCESSING/SUCCEEDED/FAILED/UNKNOWN/CLOSED` | `PENDING/ACCEPTED/SUCCEEDED/FAILED/UNKNOWN` | ✅ 两套，职责不同 |
| 独有字段 | `attemptSeq`、`currentAttemptId`、`queryAttempts`、`enteredUnknownAt`、`failureReason` | `channelCode`、`channelReference`、`retryCount`、`errorType`、`attemptType`、`requestedAt/respondedAt` | ✅ 无冗余 |
| 写入口 | `PaymentRepository`（`PaymentPersistence` 持有） | `ChannelAttemptRecorder`（**唯一**入口，ArchUnit INV-5 拦 `application..` 直接调 `PaymentAttemptRepository.save`） | ✅ 门禁有效 |
| 事务 | 两者在**同一本地事务**（`PaymentPersistence.applyAndPersist`，INV-5 澄清「分层 ≠ 拆事务」） | 同左 | ✅ 正确 |
| 渠道解析 | 不感知渠道 | 持有 `channel_code`，反向路径据此解析（**不走 Router**，INV-6） | ✅ 正确 |

**结论**：两层划分**成立且必要**。它是「渠道切换 = 新 Payment」与「同渠道重试 = 同 attempt」两个规则能同时成立的结构基础。

### 3.2 三处需要显式化的语义

#### （1）「渠道层成功 / 支付层失败」的持久化组合

`applyAndPersist` 的顺序是 **先 `converge(attempt)` 再 `applyPayment(payment)`**，两者**各自独立判断**：

```java
attemptRecorder.converge(attempt, result);      // 渠道层：attempt 按 result 迁移
boolean changed = PaymentResultApplier.applyPayment(payment, result);  // 支付层：终态吸收可能返回 false
```

因此存在**真实可产生的组合**：

| 场景 | `payment_attempts.status` | `payments.status` | 是否记账 | 谁能发现 |
|---|---|---|---|---|
| 已 `FAILED` 的 payment 收到迟到 `SUCCESS` 回调 | **`SUCCEEDED`** | `FAILED`（终态吸收） | ❌ 不记账 | 只能靠对账（`STATUS_MISMATCH` / `PLATFORM_ONLY`） |
| 已 `SUCCEEDED` 的 payment 收到迟到 `FAILURE` 回调 | **`FAILED`** | `SUCCEEDED`（终态吸收） | 已记过 | 只能靠对账 |

**这是 ADR-0007「终态吸收」的必然结果，不是 bug**。但**当前没有任何文档写明这个口径**，且**对账侧的匹配键是 `reference`（支付单号）而非「渠道流水号」**，因此这两行数据在报表里会呈现为「同一支付单渠道成功、平台失败」。

> **记为 C-11 的一部分。** 处置建议：**不改行为**，在 L0 `systems/payment-service.md` 与 `business-standards.md` 显式写明「`payment_attempts` 记录渠道事实、`payments` 记录平台事实，两者可合法不一致，差异由对账处置」。

#### （2）`ChannelResult.accepted()` 的渠道受理流水号被丢弃

| 证据 | 位置 |
|---|---|
| `accepted(channelReference, reason)` 携带渠道受理流水号，`deriveStatus` ⇒ `Status.UNKNOWN` | `ChannelResult.java:64-66`、`86-94` |
| `converge` 的 `UNKNOWN` 分支：`case UNKNOWN -> attempt.markUnknown(result.reason());` —— **既不 `accept()` 也不 `backfillChannelReference()`** | `ChannelAttemptRecorderImpl.java:69` |
| `backfillChannelReference` 在全仓**只被** `RefundAttemptSettlementService:104` 调用，不在 `converge` 内 | grep 结果 |
| 异步退款受理正是走 `accepted()` | `AbstractMockChannelAdapter.java:227` |

**影响**：异步受理返回的渠道受理流水号**不落 `payment_attempts.channel_reference`**。对 mock 无影响（`QueryStatusRequest` 只用 `paymentNo`），但接真实渠道后：
- 退款异步受理期间，`channel_reference` 为 `NULL`，渠道对账无法按受理流水号回溯；
- `uk_attempts_channel_reference` 的唯一性保护在此期间**对该行不生效**。

> **记为 C-10。** 严重度 🟠。修复成本极低（UNKNOWN 分支加 `if (result.channelReference() != null) attempt.backfillChannelReference(result.channelReference());`），但**必须先确认 `backfillChannelReference` 的允许状态集**（当前：`channelReference==null && status ∈ {PENDING, ACCEPTED, UNKNOWN}`）。

#### （3）`PaymentAttemptStatus.ACCEPTED` 的停留路径

`accept()` 只允许 `PENDING → ACCEPTED`；`converge` 在 `SUCCESS`/`FAILURE` 分支里 `accept` 后**立刻** `succeed()`/`fail()`，在同一事务内完成两次赋值 ⇒ **DB 只会看到最终值**。

因此 `ACCEPTED` 在**当前代码里没有任何路径能让它停留在 DB**（除测试直接调用）。它是一个**为真实渠道预留但尚未启用**的状态。

同时存在**反向缺口**：`UNKNOWN → ACCEPTED` **不被允许**（`accept()` 只接受 `PENDING`）。支付宝场景 `WAIT_BUYER_PAY → businessUnknown`（spec 030 §3.2 G 明确映射为 `businessUnknown`「不推进」）会让 attempt 落 `UNKNOWN`；此后若渠道推送「已受理」通知，**无法回到 `ACCEPTED`**。

> **记为 C-10。** 需人工确认：`ACCEPTED` 是保留（并在 spec 030 启用）还是应删除。

### 3.3 幂等重放的完整性

| 路径 | 行为 | 判定 |
|---|---|---|
| 显式 `idempotencyKey` 命中 | 返回既有 payment + 既有 attempt，`created=false`，**不重复限额预占**（注释已说明理由） | ✅ |
| 服务端生成键命中 | 同上 | ✅ |
| `findPaymentAttempt` 取不到 | 抛 `INTERNAL_ERROR`（**不静默回落到重新路由**） | ✅ 正确且重要——避免了「幂等命中却换渠道」 |
| `PaymentApplicationService:168` 的 `pending.attempt() == null \|\| ...getChannelCode() == null` | **防御性死代码**：`insertPending` 保证 attempt 非空（否则抛异常），且 `openPaymentAttempt` 的 `channelCode` 来自路由结果 | ⚪ 代码异味（不构成缺陷） |

---

## 4. State Machine Review

### 4.1 `Payment` 状态机

```
PENDING ──start(attemptId)──► PROCESSING ──succeed()──► SUCCEEDED ──close()──► CLOSED
                                 │  └────fail(reason)──► FAILED ────close()──► CLOSED
                                 │  └────markUnknown()──► UNKNOWN
                                 │                          │
                                 │            succeed()/fail()（UNKNOWN → 终态）
                                 │
              closeByOrderCancelled(): PENDING/PROCESSING/UNKNOWN ──► CLOSED
                                        SUCCEEDED ──► 拒绝（返回 false，事实不回滚）
                                        CLOSED    ──► 幂等吸收（返回 false）
```

| # | 检查项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 迁移入口唯一 | ✅ `private transitionTo(...)`，所有 public 方法经它 | `Payment.java:141-157` |
| 2 | 终态吸收迟到冲突 | ✅ `isTerminal()` = `SUCCEEDED/FAILED/CLOSED` ⇒ 返回 `false` | `Payment.java:159-163` |
| 3 | 幂等重复不误判为迁移 | ✅ `status == target` ⇒ `false` | `Payment.java:142-144` |
| 4 | 非法迁移必须抛错 | ✅ `PENDING` 未经 `start` 直接 `succeed()` ⇒ `STATE_TRANSITION_VIOLATION` | `Payment.java:155-156` |
| 5 | `UNKNOWN` 不猜成败 | ✅ `markUnknown` 只从 `PROCESSING` 进入，且只有权威结果能离开 | `Payment.java:102-109` |
| 6 | 进入 `UNKNOWN` 时刻被记录 | ✅ `enteredUnknownAt = Instant.now()`，供 `payment.unknown.duration` 度量 | `Payment.java:106` |
| 7 | `CLOSED` 可被迟到结果穿透？ | ✅ 不可（`CLOSED ∈ isTerminal`） | 同上 |
| 8 | **`UNKNOWN` 有无终态出口？** | ❌ **无**（`close()` 只接受 `SUCCEEDED/FAILED`；`closeByOrderCancelled()` 依赖订单取消） | `Payment.java:112-119` |

**发现**：

- **[C-06] `UNKNOWN` 无自动终态出口。** `queryAttempts` 达上限后，支付永久停留 `UNKNOWN`。当前唯一的收口是：① 渠道回调；② 人工 `POST /payments/{ref}/resolve`；③ 订单取消（`closeByOrderCancelled`）。这与 `stage-design §6.2 B2` 的判断一致，且**B2 建议的「分级升级 + 人工队列」是正确方向**，但必须**明确否决**任何「超时即 FAILED」的自动终态化（违反 Constitution §V.7）。
- **[观察] `markUnknown` 无法刷新 `enteredUnknownAt`。** 已 `UNKNOWN` 再 `markUnknown` ⇒ `status == target` ⇒ `false`，不刷新时刻。这**是正确的**（收敛时长应以首次进入计），但意味着「二次超时」不会被观测到。若需要该维度，应另加字段而非复用 `enteredUnknownAt`。
- **[观察] `closeByOrderCancelled` 与 `CLOSED` 的资金语义未文档化。** 若支付在 `PROCESSING/UNKNOWN` 时被订单取消 ⇒ `CLOSED`，此后渠道回调 `SUCCESS` 被 `CLOSED` 吸收 ⇒ **渠道实际扣款、平台 `CLOSED`、不记账**。这是**真实的资金差异**，只能靠对账 `PLATFORM_ONLY` 发现。`Payment.java:121-127` 的注释只讲了「已 `SUCCEEDED` 不关闭（surplus 退回）」，**没有覆盖这个反向场景**。

### 4.2 `PaymentAttempt` 状态机

```
PENDING ──accept(ref)──► ACCEPTED ──succeed()──► SUCCEEDED
   │                        │  └──fail(reason)──► FAILED
   ├──succeed()/fail()/markUnknown()（PENDING 直达）
   └──markUnknown()──► UNKNOWN ──succeed()/fail()──► SUCCEEDED / FAILED
```

| # | 检查项 | 结果 | 证据 |
|---|---|---|---|
| 1 | 迁移入口唯一 | ✅ 领域方法 + `converge` 统一编排 | `PaymentAttempt.java`、`ChannelAttemptRecorderImpl.java:53-71` |
| 2 | 终态吸收 | ✅ `accept()` 对非 `PENDING` 返回 `false`；`succeed()` 对已终态返回 `false` | `PaymentAttempt.java` |
| 3 | `converge` 顺序固定 | ✅ `setErrorType → accept(if ref) → succeed/fail/markUnknown`，注释标注「顺序不能变（plan §B4）」 | `ChannelAttemptRecorderImpl.java:48-50` |
| 4 | 回填渠道流水号受限 | ✅ `backfillChannelReference` 仅在 `channelReference==null && status ∈ {PENDING,ACCEPTED,UNKNOWN}` | `PaymentAttempt.java:113` |
| 5 | **`ACCEPTED` 可停留在 DB 吗？** | ❌ 无路径（同一事务内 `accept` 后立刻终态化） | §3.2（3） |
| 6 | **`UNKNOWN → ACCEPTED` 允许吗？** | ❌ 不允许（`accept()` 只接受 `PENDING`） | 同上 |
| 7 | `UNKNOWN` 分支丢 `channelReference` | ❌ 见 §3.2（2） | `ChannelAttemptRecorderImpl.java:69` |
| 8 | `retryCount` 落库时机 | ✅ 请求内联重试期间不落库，收敛时一次性写 `retries` 次 | `PaymentPersistence.java:126-128` |

### 4.3 两个状态机的一致性

| 维度 | 结论 |
|---|---|
| 是否同事务 | ✅ 同一本地事务（`applyAndPersist`），不会出现「payment 已终态 / attempt 未落库」 |
| 是否可能合法不一致 | ✅ 可能（终态吸收），见 §3.2（1），**需文档化** |
| 反向解析渠道的依据 | ✅ **恒为 `attempt.channel_code`**，绝不用 `payment` 状态或 Router（INV-6） |
| 退款 attempt 是否共用表 | ✅ 共用（`attempt_type=REFUND`），但**导致 §2.2 的 1:1 无法用 DDL 表达** |

---

## 5. Channel Architecture Review

审查对象：`ChannelRegistry`（`SpringChannelRegistry`）+ `ChannelRouter`（`ConfiguredChannelRouter`）+ 三条反向路径。

### Q1. 选路是否发生在建单之前？

✅ **是**。`PaymentApplicationService.createPaymentIntentWithRouting:164` 先 `resolveChannelCode(cmd)`，再 `paymentPersistence.insertPending(cmd, routedChannelCode)`。类注释明确「MUST NOT 先落库再改写」。**这意味着幂等键、`payment_attempts.channel_code`、`payments` 行三者使用同一个最终渠道码，不存在「建单用 A、请求发往 B」的窗口**。

### Q2. 显式指定渠道是否零干预？

✅ **基本是，但有一处「非零干预」**：`explicit()` 会**校验是否已注册**（未注册抛 `INVALID_ARGUMENT`），且 **`DOWN` 状态抛 `CHANNEL_UNAVAILABLE`（409）**。这是**有意的**（FR-029 不静默回落 / FR-034 不篡改意图），但严格说「零干预」应表述为「**不改变调用方意图，但会拒绝不可用意图**」。文档口径需统一。

### Q3. 自动选路的确定性？

✅ **是**。`selectAuto` 的排序键为三元组 `(degradedRank, priority, channelCode)`，**全部来自静态配置**：无随机数、无时间、无进程级计数器。同优先级由字典序兜底。**这是可复现的**，符合 INV-3 / FR-021。

### Q4. 不可用渠道如何处理？

| 场景 | 行为 | 判定 |
|---|---|---|
| 自动选路遇到 `DOWN` | 排除出候选集 | ✅ |
| 自动选路遇到 `DEGRADED` | 保留候选，`degradedRank=1` 排在所有 `UP` 之后 | ✅ |
| 显式指定 `DOWN` | `409 CHANNEL_UNAVAILABLE`，**不改选** | ✅ 正确（不篡改意图） |
| 显式指定 `enabled=false` | **放行**（只记 `explicit_disabled` 指标 + WARN） | ✅ 语义自洽（`enabled` = 「别自动挑我」） |
| 候选集为空 | `409 NO_AVAILABLE_CHANNEL` + WARN 含完整规则快照 | ✅ 可排障 |

### Q5. 反向路径是否绝不复用 Router？

✅ **是**。三条反向路径（退款 / 主动查询 / 超时扫描）的渠道来源：

| 路径 | 渠道来源 | 证据 |
|---|---|---|
| 主动查询 | `resolveRecordedChannel(payment)` 读 PAYMENT attempt 的 `channel_code`，经 **`ChannelRegistry`** 解析；取不到抛 `INTERNAL_ERROR` | `ChannelQueryService` |
| 重试 | `PaymentRetryService` **不持有渠道单例**，每次按 `request.channelCode()` 经 registry 解析；类注释「绝不调 Router」 | `PaymentRetryService.java:30-33`、`72` |
| 超时扫描 | 不调渠道（只做状态迁移） | `TimeoutScanner.java:43-61` |

**这是本架构最值得肯定的部分**：前向选路与反向解析在**类型层面**（两个不同的协作者）就分开了，不依赖约定。

### Q6. 幂等重复时用哪个渠道？

✅ **用库内 attempt 的 `channel_code`**（`PaymentApplicationService:168-170`）。且 `findPaymentAttempt` 取不到会**抛 `INTERNAL_ERROR`**，不会回落到重新路由 —— **这一点非常关键**，否则「同 key 重放」可能换到另一个渠道。

> ⚪ **代码异味**：`pending.attempt() == null || pending.attempt().getChannelCode() == null` 是死代码（`insertPending` 保证非空）。

### Q7. 选路结果是否可追溯？

⚠️ **部分可追溯**：

| 追溯需求 | 现状 |
|---|---|
| 这笔支付走的哪个渠道？ | ✅ `payment_attempts.channel_code`（但 `payments` 表**无渠道列**，必须 join） |
| 为什么走这个渠道？ | ✅ `payment.routing` 指标（`result` + `routed` 标签）+ INFO 日志（`basis=auto(priority)` / `basis=explicit`）+ traceId |
| 当时有哪些候选、谁被排除？ | ⚠️ 只有 `preview()` 的**当前快照**（`GET` dry-run 端点），**历史时刻的规则快照不落库**。事后无法回答「当时为什么没选 Wechat」 |
| 配置变更历史 | ❌ 无（配置文件无版本化） |

**建议**：这是可接受的取舍（避免为选路加审计表），但**必须在文档写明**「选路决策可查当前规则，历史规则不可回溯」，避免审计时误判。

### Q8. 染色 / 模态是否参与选路？

✅ **不参与**（ADR-0076 R3 明确）。`RouteContext` 只有 `(amountMinor, currencyCode, requestedChannelCode)`，且 `amountMinor` / `currencyCode` **当前未使用**（javadoc 显式标注「误认为已生效是常见误读」——**这个标注做得很对**）。

**后果（有意接受）**：染色 `SANDBOX` + `WECHAT`（无真实实现）⇒ 400；demo 页选沙箱时自动选 `ALIPAY`。`stage-design §3.4 C-3` 已记录。

**遗留观察**：`RouteContext` 的 `amountMinor` / `currencyCode` 是**为未来条件规则预留的签名**。若下一阶段要加「大额走某渠道」，需要新 ADR（属架构变化），不能靠「填上已有字段」实现。

---

## 6. Callback Review

审查对象：`ChannelCallbackController` + `ChannelCallbackSignatureFilter` + `PaymentCallbackService` + `PaymentResultProcessor`。

### Q1. 验签是否生效？

❌ **不生效**。`ChannelCallbackSignatureFilter.verifySignature()` **恒返回 `true`**，回调一律放行。这是**负责人 2026-08-30 的明确决议**（ADR-0025「预留函数、空实现就行」），且骨架（路径匹配 / 原始 body 读取 / 可重复读包装 / 拒绝分支）**完整保留**，接入时只需实现一个方法。

**判定**：**已接受的风险**，前提是**不暴露公网**。`stage-design §1.3` 标注为 🔴 且已注明前提 —— **描述准确**。

> ⚠️ 但必须强调：**spec 030 一旦接入支付宝沙箱并做内网穿透（`notify_url` 必须公网可达），这个前提就被打破**。因此 `stage-design §6.3 B-5` 把「验签空实现」列为 🔴 **接入真实渠道的前置条件**是**正确的**，且**优先级应高于 B-5 当前的排序**。

### Q2. 过滤器路径覆盖是否完整？

| 路径 | 是否被 `ChannelCallbackSignatureFilter` 覆盖 |
|---|---|
| `/internal/payments/*/channel-callback` | ✅ |
| `/internal/refunds/*/channel-callback` | ✅ |
| `/internal/channels/alipay/notify`（spec 030 新增） | ❌ **不在覆盖内**（有意：RSA2 专用端点自带验签） |

**判定**：设计合理（两套协议、两套验签机制），但**必须在文档与测试中显式声明**「`/internal/channels/alipay/notify` 不经 HMAC 过滤器，其安全性由端点自身的 RSA2 验签保证」，否则后续维护者会误以为「所有回调都过同一个过滤器」。

### Q3. 原始 body 是否可重复读？

✅ 是。过滤器读取 `rawBody` 后换上 `CachedBodyHttpServletRequest` 再放行。类注释解释了**为什么必须这样**（否则下游 `@RequestBody` 拿到已消费的流），并记录了**为什么用 `FilterRegistrationBean` 显式注册**（`@Component` 自动注册会让 MockMvc 绕过过滤器 ⇒ 假绿）。

> **这是全仓质量最高的注释之一**，把两个最容易踩的坑固化了下来。

### Q4. 重复回调是否幂等？

✅ 是。见 §2.3 场景 D。计数 `payment.duplicate_callback`。

### Q5. 乱序回调是否安全？

✅ 是（终态吸收）。但**「已 `CLOSED`（订单取消）后到 `SUCCESS`」是唯一会丢资金事实的场景**，见 §4.1 与 C-11。

### Q6. 回调金额是否校验？

❌ **不校验**。`ChannelCallbackRequest.amountMinor` 的 javadoc 明确写「当前仅落观测不做拦截（金额校验属于对账能力，见 Feature 004）」，而 `PaymentResultProcessor` 记账时**恒用 `payment.getAmountMinor()`**（平台金额）。

**影响**：
- ✅ **不会记错金额**（记账用平台金额，渠道回传金额不参与）；
- ❌ **不会发现渠道金额与平台金额不符** ⇒ 伪造/错误金额的回调可以**推进状态到 `SUCCEEDED`**，而金额差异只能等 T+1 对账 `AMOUNT_MISMATCH` 发现；
- ⚠️ spec 030 的支付宝 notify 端点**已包含** `total_amount` 字符串比较校验（§3.2 G），但**既有 JSON 回调路径没有** ⇒ **两条回调路径校验口径不一致**。

> **记为 C-04。** 严重度 🟠。**建议在 spec 030 内一并收口**（两条路径同口径），否则会留下「新端点严、老端点松」的长期不一致。

### Q7. 回调是否校验渠道流水号的归属？

❌ **不校验**。`ChannelResult.success(channelReference)` 携带的 `channelReference` 直接交给 `converge` → `accept(ref)`，**没有校验**：
1. 该 `ref` 是否已被**其他支付单**使用（`uk_attempts_channel_reference` 会挡住「同 ref 第二条」，但挡不住「把 B 单的 ref 写到 A 单」）；
2. 该 `ref` 与 attempt 已记录的 `channel_reference` 是否一致。

**影响**：若渠道回调被伪造或串号，可把 A 单标记为成功并绑定 B 单的渠道流水号 ⇒ 对账时 A、B 两单都会异常。**当前唯一保护是「不暴露公网 + 渠道侧可信」**。

> **记为 C-05。** 严重度 🟠（与 C-04 同源，可一并修复）。

### Q8. 回调是否有幂等键 / 防重放窗口？

| 项 | 现状 |
|---|---|
| 幂等 | ✅ 靠终态吸收（非显式幂等键） |
| 防重放 | ❌ 过滤器读取 `X-Channel-Timestamp` 但**未使用**（因验签恒通过）。`SignatureVerifier` 的防重放窗口在接入时才生效 |

**判定**：与 Q1 同源，**接真实渠道前必须补齐**。

---

## 7. Ledger Review

### Q1. 借贷平衡在哪里校验？

✅ **聚合根构造期**。`Posting` 构造函数即调 `requireBalanced()`，不平衡抛 `LEDGER_UNBALANCED` 且**不落任何分录**。且 `rehydrate` 也走构造函数 ⇒ **持久化重建同样校验**。`LedgerEntry` 构造期校验 `amountMinor > 0`。

**这是正确的「数据质量门禁」设计**：不平衡不是业务错误，是程序缺陷，必须拒绝而非降级。

### Q2. 幂等键口径是否统一？—— ❌ **不统一（最高优先级缺陷）**

**两个口径并存**：

| 路径 | 调用点 | 网关前缀 | **最终 `idempotency_key`** |
|---|---|---|---|
| 同步 charge（`deferChannel=false`） | `PaymentApplicationService.java:222` 传 `payment.getIdempotencyKey()` | `FeignLedgerPostingGateway:42` 加 `"PAYMENT:"` | **`PAYMENT:payment:{orderNo}:{code}:{seq}`** |
| 回调 / UNKNOWN 收敛 | `PaymentResultProcessor.java:188` 传 `"PAYMENT:" + payment.getPaymentNo()` | 同上再加一次 | **`PAYMENT:PAYMENT:{paymentNo}`** |

**这是「G5 双重前缀」缺陷的遗留 —— 而该缺陷在退款侧已被修复，支付侧被漏掉**：

```
RefundResultProcessor.java:130-132
    // G5 双重前缀修复：调用方只传 PMRF，"REFUND:" 前缀统一由出站网关添加
    ledgerGateway.postRefundCapture(refund.getRefundNo(), refund.getRefundNo(), ...)
```

对照支付侧：`PaymentResultProcessor.java:188` 仍传 `"PAYMENT:" + paymentNo`，`FeignLedgerPostingGateway` 再加 `"PAYMENT:"` ⇒ **`PAYMENT:PAYMENT:PM...`**。

**影响**：

| # | 影响 | 严重度 |
|---|---|---|
| 1 | **账本幂等保护变成「路径相关」**：同一支付单，走同步路径与走回调路径产生**不同**的幂等键。若两条路径都尝试记账，账本**不会去重** | 🔴 |
| 2 | 当前**未实际发生**双记账，因为唯一的防线是 `PaymentResultApplier.applyPayment` 的终态吸收（返回 `false` ⇒ 不记账）。**纵深防御从「两层」退化为「一层」** | 🔴 |
| 3 | `PaymentCaptureLedgerPostingTest:61` 断言的 `PAYMENT:{paymentNo}` 是**回调路径**的键，并被 javadoc 写成「幂等键格式 `PAYMENT:<paymentNo>`（Feature 015 / C2）」⇒ **测试把偏差路径当成了规范**，同步路径的键**没有任何测试断言** | 🟠 |
| 4 | 排障困难：按 `paymentNo` 查账本需要试两种键前缀 | 🟡 |

**候选方案**：

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A. 统一到「调用方只传业务键，前缀由网关加」（推荐）** | `PaymentResultProcessor:188` 改为传 `payment.getPaymentNo()`（与退款侧 G5 修复完全同构）；`PaymentApplicationService:222` 改为传 `payment.getPaymentNo()` | 与已修复的退款侧**口径一致**；键变为 `PAYMENT:{paymentNo}`，与测试断言一致；**零 schema 变更** | 存量已记账的键不迁移（`PAYMENT:payment:...` 的历史 posting 保留，新记账用新键）⇒ 需确认「同一支付单不会既用旧键又用新键」 |
| B. 统一到「调用方传完整键，网关不加前缀」 | 删掉网关的前缀拼接，两个调用点各自传完整键 | 键构造显式 | 与退款侧不一致；改动面更大 |
| C. 保持现状，只加注释说明 | — | 零成本 | **不可接受**：把已知缺陷固化为设计 |

**推荐**：**A**。并在修复时**必须**补两条测试：① 同步路径的账本键 = `PAYMENT:{paymentNo}`；② 同一支付单在两条路径下产生**同一个**键（用 `RecordingLedgerGateway` 断言）。
**是否需要人工决策**：**否**（这是缺陷修复，非架构变更）。但**必须确认存量数据的处理口径**（旧键的 posting 是否需保留为历史事实 ⇒ 建议保留，不迁移）。

### Q3. 分录是否不可变？

✅ 是。`LedgerEntry` 除 `setId`（持久化回填）外无任何 setter；全仓**无** `UPDATE ledger_entries` / `DELETE` 路径；更正靠反向分录（冲正）。`LedgerEntry` javadoc 明确「已提交分录 MUST NOT UPDATE/DELETE」。

### Q4. 记账失败怎么办？

⚠️ **「发现得到、修不了」**：

| 环节 | 现状 | 证据 |
|---|---|---|
| 异常处理 | `catch (RuntimeException ex)` ⇒ 记 `ledger.posting_failed` 指标 + ERROR 日志，**不 rethrow、不落库** | `FeignLedgerPostingGateway.java:51-56` |
| 「待记账清单」 | ❌ **不存在**（`stage-design §4.2 G3` 已正确识别为缺口） | — |
| 重试 | ❌ 无自动重试 | — |
| 事后发现 | ✅ `CertificateAuditor` 产出 `MISSING_POSTING`（**BLOCKER**） | `AuditDifferenceKind.java:8`、`33` |
| 事后修复 | ⚠️ 需人工发起调账（`SUPPLEMENT`），**无自动补记** | `AuditApplicationService.adjust` |

**修正 `stage-design §4.3`**：「靠重试 / 对账 / G3 的待记账清单兜底」应改为「**靠对账 `MISSING_POSTING` 发现 + 人工调账补记；无自动重试、无待记账载体**」。见 §1.3 A-2。

### Q5. 记账与支付成功是否同事务？

❌ **否，且这是有意的**。记账是**跨服务同步 RPC**，在 `applyAndPersist` 事务**之外**（`PaymentResultProcessor:183-190`）。一致性窗口由 `PAYMENT:{key}` 幂等键 + 对账兜底。

**判定**：符合「禁 2PC/XA」（Constitution §V.6），可接受。**但窗口内的失败无载体**（Q4）。

### Q6. 科目表是否够用？

| 科目 | ID | 实际使用情况 |
|---|---|---|
| `CUSTOMER_CASH` | 1 | ✅ 支付借方 / 退款贷方 / 结算不涉及 |
| `MERCHANT_PAYABLE` | 2 | ✅ 支付贷方 / 退款借方 / 结算借方 |
| `PLATFORM_FEE_REVENUE` | 3 | ⚠️ **实际恒空**——所有调用点传 `feeMinor = 0`（`PaymentApplicationService:223`、`PaymentResultProcessor:189`） |
| `SETTLEMENT_PAYABLE` | 4 | ✅ 结算贷方 |
| `SUSPENSE` | 5 | ✅ 审计挂账 |

**发现**：
- `PLATFORM_FEE_REVENUE` **已建模、已实现分录分支、但无任何路径产生非 0 手续费**。`stage-design §4.2 G4` 只提「科目扩展」，**未提「已建模科目长期空转」**。这不是缺陷，但意味着**手续费链路从未被端到端验证**（分录模板的 `A = N + F` 只在 `F=0` 时被测试过）。
- `SETTLEMENT_PAYABLE` 的负债**只在结算成功时冲销**，而负净额场景不记账（见 §9 / C-07）⇒ 可能残留。

### Q7. 是否有「账」的视图？

| 能力 | 现状 | 证据 |
|---|---|---|
| 全局借贷平衡 | ✅ `GET /internal/ledger/balance` ⇒ `{balanced, diffByCurrency}` | `LedgerController.java:82-86` |
| 单科目余额 | ⚠️ `BalanceChecker.accountBalance(accountId, currency)` **已实现**，但**无 HTTP 端点** | `BalanceChecker.java:41-52` |
| 试算平衡表 | ❌ 无 | — |
| 期间 / 关账 | ❌ 无（`postings` **无 `period` 列**） | `09-ledger-schema.sql` |
| 按来源追溯 | ✅ `GET /internal/ledger/entries?sourceType=&sourceId=` | `LedgerController.java:88-97` |
| 全部 posting（审计用） | ✅ `GET /internal/ledger/postings/all?sourceType=`（上限 1000） | `LedgerController.java:70-80` |
| 重复 posting 检测 | ⚠️ `ledger_entries` **无 `(source_type, source_id)` 唯一约束** ⇒ 只能靠审计发现 `DUPLICATE_POSTING`，无法阻止 | `09-ledger-schema.sql` |

**结论**：`stage-design §4.1` 的描述**准确**（「有分录无账」）。补充一点：`accountBalance` **已存在但未暴露** ⇒ G1 的实现成本比 stage-design 估计的**更低**（至少「单科目余额」端点只是加一个 `@GetMapping`）。

---

## 8. Reconciliation Review

### Q1. 匹配键是什么？有无串账风险？

⚠️ **单键 `reference`，无商户维度** ⇒ `stage-design §5.2 R2 / N1` 的判断**正确且重要**。

| 证据 | 位置 |
|---|---|
| `refs = TreeSet(platforms.keySet() ∪ channels.keySet())`，键 = `reference` | `ReconciliationMatching.java:31-33` |
| `PlatformFact` / `ChannelStatement` / `Match` **均无 `merchantId`** | 三个 record 定义 |
| ADR-0023 已记录该缺口 | stage-design §1.3 引用 |

**判定**：🟠 **真实串账风险**。当前缓解只有「单商户演示前提」。R2 的推荐方案（匹配键改 `(merchantId, reference)`）**需要跨服务 DTO 变更 ⇒ 人类决策边界**，判断正确。

### Q2. 事实读取失败如何处理？

✅ **上抛，不落半成品**。`fetchWithMetric` 捕获后记 `reconciliation.fact_read_failed` 指标 + WARN，然后 **`throw ex`**；且事实读取**在建批之前**完成（ADR-0021）⇒ 该周期可安全重跑。

**这是正确的「fail fast 不留脏数据」实现**。

### Q3. 幂等如何保证？

✅ 靠**数据库唯一约束** `uk_reconciliation_batches_period`（非进程内内存登记），并发/重启后撞键则回查返回首批次。`insertNew` 捕获 `DuplicateKeyException` 后回查。

### Q4. 差异能否自动处置？

❌ **全部人工**。`resolveDifference` 只记录 `resolutionNote` + `resolvedBy` + `resolvedAt`，并把批次推进到 `PROCESSING`。

`stage-design §5.2 R4`（差异处置策略化）方向正确，且**红线设定正确**（自动处置 MUST NOT 改原始事实、MUST 经 ledger 平衡分录、MUST 受「累计 ≤ 差异额」约束）。注意：`AuditApplicationService.adjust` **已实现**「累计 ≤ 差异额」的硬规则（`if (amount != amountMinor) throw ADJUST_AMOUNT_EXCEEDED`），R4 可直接复用。

### Q5. 结算事实来源是什么？—— ⚠️ **隐含耦合**

```java
// ReconciliationApplicationService.settlementSummary(period)
List<ReconciliationSettlementFact> facts = batch.getMatches().stream()   // ← 只有「已匹配」的
        .map(m -> new ReconciliationSettlementFact(m.reference(), m.type(), m.amountMinor(), m.currencyCode()))
        .toList();
long unresolved = batch.unresolvedDifferenceCount();
```

**三层耦合**：

| # | 耦合 | 影响 |
|---|---|---|
| 1 | 结算事实**只取 `matches`**，不含 `PLATFORM_ONLY` / `CHANNEL_ONLY` | 未匹配的平台事实**不进入结算** ⇒ 商户少结算 |
| 2 | 批次不存在时**抛 `NOT_FOUND`** | **结算无法独立于对账**（无对账批次 = 不能结算） |
| 3 | `unresolvedDifferenceCount > 0` ⇒ `SettlementEligibility` 拒绝 | 有任一未解决差异 ⇒ 全商户全周期不结算（**不是按笔拦截**） |

**判定**：耦合 1 + 3 **组合起来是自洽的**（有差异就不结算，所以「只取 matches」不会漏钱）。**但这是一个强假设**：一旦 R4「差异处置策略化」引入「自动挂账后放行」，耦合 1 就会**立刻变成漏洞**（差异被挂账 ⇒ 未解决数为 0 ⇒ 放行 ⇒ 但该笔事实不在 `matches` 里 ⇒ **静默漏结算**）。

> **记为 C-13。** 严重度 🟠。**必须与 R4 同批处理**：R4 实施前须先把 `settlementSummary` 的口径改为「**全部已确认事实 − 已处置差异的净额影响**」，而不是「matches」。
> `stage-design §5.2 R4` **未识别这个依赖**，需补。

### Q6. 会计四核对是否完整？

✅ 完整。`AuditApplicationService.runBatch` 按 `scope` 分派四个审计器：

| 核对 | 审计器 | 差异类型 |
|---|---|---|
| 账证 | `CertificateAuditor` | `MISSING_POSTING` / `ORPHAN_POSTING` / `AMOUNT_MISMATCH` / `CURRENCY_MISMATCH` / `DIRECTION_MISMATCH` / `DUPLICATE_POSTING` |
| 账账 | `LedgerAuditor` | `BALANCE_BREAK` / `ACCOUNT_RECON_BREAK` / `CROSS_LEDGER_MISMATCH` |
| 账实 | `RealAuditor` | `LEDGER_VS_STATEMENT_BREAK` |
| 账表 | `ReportAuditor` | `REPORT_MISMATCH` |

共 **11 类**（与 `stage-design §5.1` 一致）✅，三级 severity（`BLOCKER`/`MAJOR`/`MINOR`）✅，5 态状态机 ✅。

**一处值得肯定的设计**：`CertificateAuditor` 的**方向核对**按资金科目符号判断（支付借方为正 / 退款贷方为负），且 **`SETTLEMENT` 明确排除**（不走客户资金科目）—— 这个例外被显式写出，避免了误判。

### Q7. 账单来源是否真实？

⚠️ 本地 CSV fixture，`{dir}/{period}.csv` 未命中**显式回退** `sample.csv` + `reconciliation.statement_fallback` 指标 + WARN（**不静默**）。

**判定**：回退**带指标与 WARN** 是合格的做法，但 `stage-design §5.2 R1` 的红线**必须强调**：真实账单接入后，回退 MUST 收紧为 **fail fast**（否则「假对账」）。

### Q8. 跨账核对键是否合规？

❌ **`SETTLEMENT` posting 的 `sourceId` 是数值 ID，违反 ADR-0063**。

| 证据 | 位置 |
|---|---|
| `ledgerPostingGateway.postSettlement(batch.getIdempotencyKey(), batch.getId(), batch.getNetMinor(), ...)` | `SettlementApplicationService.java:248-249` |
| `AuditApplicationService.auditFacts` 注释：「ledger 侧 SETTLEMENT posting 的 sourceId 是批次 `id`」 | `SettlementApplicationService.java:224-225` |
| ADR-0063 要求：跨系统关联一律用业务单号，**禁数值 ID** | `technical-solution` / ADR-0063 |

**判定**：这是**已知且有意**的（注释写明），但与 ADR-0063 直接冲突。`batch.batchNo`（`SB + 雪花`）**已存在且可用** ⇒ 修复成本低。

> **记为 C-08。** 严重度 🟡（不影响资金正确性，影响可追溯性与规范一致性）。**需人工决策**：改（则存量 `SETTLEMENT` posting 的 `source_id` 与新数据不一致，跨账核对需兼容两种键）或不改（则须在 ADR-0063 里显式登记例外）。

---

## 9. Settlement Review

### Q1. 资格判定是否充分？

✅ `SettlementEligibility.evaluate(merchantActiveAndEligible, unresolvedDifferenceCount)`：
- 商户 `ACTIVE` + `settlementEligible`；
- 未解决差异数 = 0。

**纯领域函数、无副作用**（易测）。**但粒度是「商户 + 周期」整体**，不是按笔 —— 见 Q5 的耦合 3。

### Q2. 审计门禁是否 fail-closed？

✅ **是**。`AuditApplicationService.settlementGate(period)` 返回 `ALLOW` / `BLOCK`；`AuditGateClient` 侧把不可达归一化为异常 ⇒ fail-closed。`stage-design §5.1` 描述准确。

**但有一处有意的宽松**：

```java
// 无审计批次时仅校验借贷平衡（无证据 ≠ 违规）
for (AuditScope scope : List.of(CERTIFICATE, LEDGER, ALL)) {
    Optional<AuditBatch> batch = auditRepository.findBatchByPeriodAndScope(period, scope);
    batch.ifPresent(b -> ...);   // ← 批次不存在时静默跳过
}
```

即：**没有跑过审计 ⇒ 不拦截**（只校验全局借贷平衡）。这是有意的（否则任何新周期都无法结算），但**必须文档化**——否则「门禁 fail-closed」会被误读为「没有证据也拦」。

### Q3. 净额公式是否正确？

✅ `netMinor = income − refund + Σ signedAdjustment`（`SettlementBatch.compute`）。`income`/`refund` 由 `facts` 按 `type` 过滤求和；`adjustment` 由 `ACTIVE` 调整项的**带符号合计**（`CREDIT` 取正、`DEBIT` 取负）。

**金额一律 `long` 最小货币单位，禁浮点** ✅。`income < 0 || refund < 0` 抛 `AMOUNT_INVARIANT_VIOLATION` ✅。

### Q4. 是否真实出款？

❌ **否，有意**。`createBatch` 内 `batch.execute()` → `batch.markUnknown("mock settlement payout unknown")` ⇒ **强制 `UNKNOWN`**。`resolveBatch` 依权威结果收敛。

**判定**：符合「UNKNOWN 不猜成败」。`stage-design §5.2 R3` 的「出款边界显式化」方向正确。

### Q5. 状态机是否正确？

✅ 迁移入口唯一（`transitionTo`）、终态吸收、`close()` 只接受 `SUCCEEDED/FAILED`、`UNKNOWN` 经权威结果收敛。

⚪ **代码异味**：`SettlementBatch` 同时有 `compute(...)`（public）与 `calculate(...)`（public），`compute` 只被 `calculate` 调用 ⇒ `compute` 应降为 `private`。

### Q6. 记账是否完整？—— ❌ **负净额的会计处理未定义**

```java
case "SUCCEEDED" -> {
    batch.succeed();
    if (batch.getNetMinor() > 0) {
        ledgerPostingGateway.postSettlement(...);   // 记账
    } else {
        metrics.counter("settlement.ledger_skip_nonpositive_net", ...);   // ← 只打指标
        log.info("结算净额非正，跳过记账 batchId={} net={}", ...);
    }
}
```

**问题**：`netMinor ≤ 0` 时**不记账**。而 `SettlementBatch.compute` **明确允许负净额**（注释：「净额可为负（MVP 不拒绝，仅递增 `settlement.negative_net` 由编排层关注）」）。

**后果分析**（以 `net < 0` 为例，即退款 > 收入）：

| 科目 | 期望 | 实际 |
|---|---|---|
| `CUSTOMER_CASH` | 借：已收全额 | 正确（支付时已记） |
| `MERCHANT_PAYABLE` | 贷：收入 − 退款（可能为负 ⇒ 应变成**借方余额**，即商户欠平台） | **支付时贷 income；退款时借 refund ⇒ 净额正确，但从未被结算冲销** |
| `SETTLEMENT_PAYABLE` | 贷：net | ❌ **不记** ⇒ 负债未确认 |

⇒ **`MERCHANT_PAYABLE` 会残留一笔未被结算的余额**，且 `SETTLEMENT_PAYABLE` 缺失对应负债。账面上「平台欠商户」或「商户欠平台」的金额**永远停留在过渡状态**，无科目承载。

同时：`AuditDifferenceKind.CROSS_LEDGER_MISMATCH` 的定义是「结算批次净额与该批次 ledger posting 不符」⇒ 一个 `net < 0` 的 `SUCCEEDED` 批次**永远会被判为跨账不符**（因为无 posting）。`recheck` 也永远无法通过。

> **记为 C-07。** 严重度 🟠。**候选方案**：

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A. 负净额记反向分录（推荐）** | `net < 0` 时记 `DEBIT SETTLEMENT_PAYABLE \|net\|` = `CREDIT MERCHANT_PAYABLE \|net\|`（借贷对调，金额取绝对值，仍满足 `amount > 0`） | 会计语义完整；`CROSS_LEDGER_MISMATCH` 可对平；零 schema 变更 | 需确认「平台向商户追偿」是否为业务认可语义 ⇒ **需人工决策** |
| B. 负净额禁止结算 | `SettlementEligibility` 增加 `netMinor > 0` 前置校验，`net ≤ 0` 直接拒绝建批 | 简单、不会产生悬空余额 | 商户本期无法结算 ⇒ 业务上不可接受（退款多的商户被卡死） |
| C. 负净额挂 `SUSPENSE` | 走审计挂账通道 | 复用既有机制 | 语义错位（挂账是「差错款」，不是「正常负净额」） |

**推荐**：**A**，且**必须人工确认**（涉及资金语义，属人类决策边界）。

---

## 10. Reliability Review

### 10.1 11 类故障走查

> 每行按 **故障 → 当前状态 → 数据库状态 → 系统下一步 → 重试/补偿 → 最终状态** 走查。
> 「当前状态」= 故障发生瞬间的内存态；「数据库状态」= 该时刻已提交的持久化事实。

| # | 故障 | 当前状态 | 数据库状态 | 系统下一步 | 重试/补偿 | 最终状态 |
|---|---|---|---|---|---|---|
| **F1** | 渠道 RPC **超时**（`transportCode=TIMEOUT`） | `payment=PROCESSING`（内存）；`attempt=PENDING` | `payments=PROCESSING`；`payment_attempts=PENDING`（渠道调用前已提交） | `PaymentRetryService.chargeWithRetry` **内联退避重放**（**不落库**）；`retryable()` 只看 `transportCode` ⇒ 超时可重试 | 同 attempt、同渠道、同幂等键重放（ADR-0014）；退避序列 `retryBackoff` | 某次成功 ⇒ `SUCCEEDED`；全部超时 ⇒ `RETRY_EXHAUSTED` + `payment=UNKNOWN`、`attempt=UNKNOWN`、`retry_count=N`、`error_type=TRANSIENT` |
| **F2** | 渠道**通信失败**（连接拒绝 / 5xx，非超时） | 同 F1 | 同 F1 | 同 F1（`transportCode != SUCCESS` 一律可重试，ADR-0012） | 同 F1 | 同 F1 |
| **F3** | 渠道**业务拒绝**（`transport=SUCCESS`，`business=DECLINED`） | `payment=PROCESSING` | `payments=PROCESSING` | `applyAndPersist`：`converge` 推 attempt（`accept(ref)` → `fail`）、`applyPayment` 推 payment → `FAILED` | **不重试**（业务拒绝是明确结论，FR-006）；不记账；不通知履约 | `payment=FAILED`、`attempt=FAILED`、`error_type=HARD`、`retry_count=0` |
| **F4** | 渠道返回**「已受理未终局」**（`accepted(ref)`） | `payment=PROCESSING` | `payments=PROCESSING` | `ChannelResult.accepted` ⇒ `deriveStatus` ⇒ `Status.UNKNOWN`；`converge` 走 UNKNOWN 分支 ⇒ `attempt.markUnknown` | **`channelReference` 被丢弃**（C-10）；无重试 | `payment=UNKNOWN`、`attempt=UNKNOWN`、**`channel_reference=NULL`**（缺陷） |
| **F5** | 重试**耗尽**后仍不确定 | `payment=PROCESSING` | `payments=PROCESSING` | `withReason("RETRY_EXHAUSTED")` → `applyAndPersist` | 交棒给 ① 主动查询 ② 回调 ③ 对账 ④ 人工 `resolve` | `payment=UNKNOWN`、`attempt=UNKNOWN`、`retry_count=N` |
| **F6** | `UNKNOWN` **主动查询**（含查询耗尽） | `payment=UNKNOWN`、`attempt=UNKNOWN`、`query_attempts=k` | 同左 | `ChannelQueryScheduler`（15s）→ `queryRound()` 扫 `UNKNOWN` 且 `queryAttempts < max` → `resolveRecordedChannel`（**按 `attempt.channel_code`，绝不过 Router**）→ `queryStatus` → `recordQueryAttempt()` | 非 UNKNOWN ⇒ `resolution.resolve(...)` → `PaymentResultProcessor.applyAndNotify`（**记账 + 通知履约**）；达上限 ⇒ 停止自动查询 | `SUCCEEDED` / `FAILED`；**或永久 `UNKNOWN`**（C-06，无终态出口） |
| **F7** | 支付成功后**记账 RPC 失败** | `payment=SUCCEEDED`（事务已提交） | `payments=SUCCEEDED`；**ledger 无 posting** | `FeignLedgerPostingGateway` **吞异常** ⇒ 仅 `ledger.posting_failed` 指标 + ERROR 日志 | **无自动重试、无待记账载体**（C-09）；T+1 `CertificateAuditor` 产出 `MISSING_POSTING`（BLOCKER）⇒ 人工调账（`SUPPLEMENT`）补记 | 补记后账证相符；未补记则持续 BLOCKER 并**阻塞结算**（`settlementGate` BLOCK） |
| **F8** | 支付成功后**履约通知失败**（MQ `commit` 失败） | `payment=SUCCEEDED` | `payments=SUCCEEDED`；**半消息留在 Redis**（`mq:half:{topic}:{msgId}` + `ZADD mq:half:idx`） | `HalfMessageScanner` 按 `prepare` 超时摘取 → **回查真相表**（`payments`）→ 判定 COMMIT ⇒ 补投 | 回查次数上限后 `toDlq`（`mq:dlq:{topic}` + `mq.dead_letter` 指标） | 订单侧收到 `payment.succeeded`；或进 DLQ 需人工重放。**支付事实不受影响（INV-1）** |
| **F9** | **重复回调**（同一 SUCCESS 回调两次） | 第一次 ⇒ `SUCCEEDED`；第二次 ⇒ `PROCESSING`? 否 ⇒ 已 `SUCCEEDED` | `payments=SUCCEEDED`（第一次） | `applyAndNotify`：`applyPayment` 命中 `status == target` ⇒ `changed=false` | 不记账、不通知履约；计 `payment.duplicate_callback` | `SUCCEEDED`，**记账与履约恰好一次**（测试已钉死）。⚠️ attempt 仍被无条件 `save`（一次无意义 UPDATE） |
| **F10** | **迟到冲突回调**（已 `FAILED` 后到 `SUCCESS`） | `payment=FAILED`（终态） | `payments=FAILED` | `transitionTo(SUCCEEDED)` ⇒ `isTerminal()` ⇒ 返回 `false` | 不记账、不通知履约 | `payments=FAILED` / **`payment_attempts=SUCCEEDED`**（渠道层被收敛）；**渠道实际已扣款** ⇒ 资金差异，仅能靠对账 `STATUS_MISMATCH`/`PLATFORM_ONLY` 发现（C-11） |
| **F11** | **订单取消 × 支付状态** | 见下 | 见下 | `Payment.closeByOrderCancelled()` | — | 见下 |

**F11 细分**：

| 子场景 | 迁移 | 数据库状态 | 后续渠道回调 | 最终状态 |
|---|---|---|---|---|
| 11a 取消时 `SUCCEEDED` | **拒绝**（返回 `false`，状态不变） | `payments=SUCCEEDED` | 已被吸收 | `SUCCEEDED`；order 侧走 **surplus 原路退回** |
| 11b 取消时 `PROCESSING` / `UNKNOWN` | ⇒ `CLOSED` | `payments=CLOSED`、`failure_reason="order cancelled"` | **`CLOSED` 吸收 SUCCESS** ⇒ 不记账 | ⚠️ **渠道扣款成功、平台 `CLOSED`、不记账** ⇒ 资金差异，仅能靠对账发现 |
| 11c 取消时 `PENDING` | ⇒ `CLOSED` | `payments=CLOSED` | 同上 | 同上 |

**F11b 是本轮走查发现的最严重资金语义空洞**（记为 C-11）：`Payment.java:121-127` 的注释只覆盖了「已 `SUCCEEDED` 不关闭」，**未覆盖「关闭后渠道成功」**。`closeByOrderCancelled` 的语义是「拒收后续回调」，但渠道**已经扣款**这一事实无法被拒收。

### 10.2 补充：并发竞争（非 11 类之一，但必须走查）

| 竞争 | 保护 | 判定 |
|---|---|---|
| `TimeoutScanner` 与回调同时推进 | `payments.version` 乐观锁 + `transitionTo` 的状态判断 | ✅ 一方失败，另一方成功；失败方抛 `STATE_TRANSITION_VIOLATION`（若非法）或返回 `false`（若终态） |
| 两个并发 `createPaymentIntent`（同幂等键） | `uk_payments_idempotency_key` + `insertNew` 捕获 `DuplicateKeyException` 回查 | ✅ |
| 两个并发 `createPaymentIntent`（无幂等键，同交易） | ⚠️ `countByTransactionId` **读后写**，两个并发请求会算出**同一个 `attemptSeq`** ⇒ 幂等键相同 ⇒ 第二条撞唯一键回查 ⇒ 返回**同一笔支付** | ⚠️ **行为安全但语义可疑**：「用户连点两次下单」被合并为一笔（可能符合预期，也可能不符）。**当前无测试覆盖**，且 H2 无法验证真实并发 ⇒ 属 T1（Testcontainers）的目标场景 |
| 两个并发渠道调用（同 attempt） | 无保护（但 `PaymentPersistence` 保证一次请求内只调一次） | ⚪ 当前无路径 |

### 10.3 可靠性结论

- ✅ **全链路无「猜成败」**：`deriveStatus` 只在 `transport==SUCCESS && business.isConclusive()` 时给 `FAILURE`，其余非成功一律 `UNKNOWN`。
- ✅ **重试判定只看通信码**（`retryable()` = `transportCode.isRetryable()`），业务拒绝不重试。
- ✅ **终态吸收**在 `Payment` / `PaymentAttempt` / `Refund` / `SettlementBatch` / `ReconciliationBatch` 五处一致实现。
- ✅ **消息通道半消息协议**实现完整（prepare → 本地事务 → commit；`HalfMessageScanner` 回查真相表补投/丢弃；DLQ + 计数 TTL）。
- ❌ **两处缺口**：`UNKNOWN` 无终态出口（C-06）；后置失败（记账 / 履约 / 退款通知）**均无持久化台账**（C-09，`stage-design B4` 已识别）。
- ✅ `stage-design §6.1` 的现状表**逐条核实准确**，包括「Redis 全丢系统仍正确，只需人工重放」（因三条记账链路同步、`payment.mq.enabled=false` 可回落同步 Feign）。

---

## 11. Design Conflicts

> 格式：**问题 / 证据 / 影响 / 候选方案 / 推荐方案 / 是否需要人工决策**
> 严重度：🔴 阻断（接真实渠道前必须解决）/ 🟠 高 / 🟡 中 / ⚪ 观察

### C-01 🔴 账本幂等键双口径（G5 双重前缀在支付侧遗留）

- **问题**：同一支付单，同步路径产生 `PAYMENT:payment:{orderNo}:{code}:{seq}`，回调/收敛路径产生 `PAYMENT:PAYMENT:{paymentNo}`；账本幂等保护变成路径相关。
- **证据**：`PaymentApplicationService.java:222`（传 `idempotencyKey`）× `FeignLedgerPostingGateway.java:42`（加 `"PAYMENT:"`）；`PaymentResultProcessor.java:188`（传 `"PAYMENT:" + paymentNo`）× 同一网关再加一次；对照 `RefundResultProcessor.java:130-132` 已修复 G5（注释原文「G5 双重前缀修复」）；`PaymentCaptureLedgerPostingTest.java:61` 只断言回调路径的键。
- **影响**：纵深防御从两层退化为一层（唯一防线是终态吸收）；若两条路径都尝试记账，账本不去重 ⇒ **潜在双记账 / `CUSTOMER_CASH` 双倍借记**；排障需试两种键。
- **候选方案**：A 统一为「调用方只传业务键、前缀由网关加」（与退款侧同构）；B 网关不加前缀、调用点传完整键；C 仅加注释。
- **推荐**：**A**（零 schema 变更、与已修复侧一致、与测试断言一致）。补两条测试：同步路径的键 = `PAYMENT:{paymentNo}`；同一支付单两条路径产生同一键。
- **是否需要人工决策**：**否**（缺陷修复）。但需确认存量旧键 posting 的处理口径（建议保留为历史事实，不迁移）。

### C-02 🟠 `Payment : PaymentAttempt` 基数文档自相矛盾 + 无结构强制

- **问题**：`technical-solution.md §4.2` 写「1:1（支付尝试）+ 1:N（退款尝试）」，`systems/payment-service.md §2.1` 写「1:N」；且 schema 无 `UNIQUE(payment_no, attempt_type)`。
- **证据**：`technical-solution.md §4.2`；`systems/payment-service.md §2.1`；`03-payment-schema.sql`（仅 `KEY idx_attempts_payment_no` / `idx_attempts_payment_type`）；`ChannelQueryService.resolveRecordedChannel` 与 `ChannelAttemptRecorderImpl.findPaymentAttempt` 均 `.findFirst()`。
- **影响**：L0 层矛盾 ⇒ `DOCUMENTATION_DRIFT`；`.findFirst()` 静默取首 ⇒ 若出现第二条 PAYMENT attempt，会出现「查询走 A、记账走 B」的幽灵缺陷。
- **候选方案**：A 应用层强断言（`openPaymentAttempt` 前查重 + 单测）；B schema 加唯一约束（需拆表或生成列）；C 只改文档。
- **推荐**：**A + C 立即**；**B 单独立项**（若下一阶段做严肃对账，价值上升）。
- **是否需要人工决策**：**B 需要**（数据库结构变更）；A / C 不需要。

### C-03 🟠 `payment_attempts` 混装 PAYMENT / REFUND 两类 attempt

- **问题**：`attempt_type` 使「1:1」严格说是「Payment 1:1 PAYMENT-type attempt」，而 MySQL **不支持 partial unique index**，无法用一条 DDL 表达。
- **证据**：`PaymentAttempt.java:20-21`（`TYPE_PAYMENT` / `TYPE_REFUND`）；`ChannelAttemptRecorderImpl.openPaymentAttempt` / `openRefundAttempt` 写同一张表。
- **影响**：C-02 的方案 B 被迫升级为「拆表」（更重的 schema 变更）。
- **候选方案**：A 保持单表 + 应用层断言；B 拆为 `payment_attempts` / `refund_attempts`。
- **推荐**：**A**（拆表收益不足以抵消迁移成本与回归风险）。
- **是否需要人工决策**：否（若选 A）。

### C-04 🟠 回调金额不校验（且两条回调路径口径不一致）

- **问题**：既有 JSON 回调路径不校验 `amountMinor`；spec 030 的支付宝 notify 端点**已包含** `total_amount` 校验 ⇒ 两条路径口径不一。
- **证据**：`ChannelCallbackRequest.java:16-17` javadoc「当前仅落观测不做拦截」；`PaymentResultProcessor` 记账恒用 `payment.getAmountMinor()`；spec 030 §3.2 G（`out_trade_no`/`total_amount` 字符串比较）。
- **影响**：错误金额的回调可推进状态到 `SUCCEEDED`；金额差异只能 T+1 对账发现；长期存在「新端点严、老端点松」。
- **候选方案**：A 在 spec 030 内一并收口（两条路径同口径）；B 单独立项。
- **推荐**：**A**（避免留下长期不一致）。
- **是否需要人工决策**：**是**——「金额不符时拒绝回调还是仅记差异」是业务口径（建议：**拒绝推进 + 记差异 + 保留原状态**，即 `businessFailure` 语义）。

### C-05 🟠 回调渠道流水号不校验归属

- **问题**：回调携带的 `channelReference` 直接 `accept(ref)`，不校验是否属于本支付单、是否与其他支付单冲突。
- **证据**：`ChannelCallbackController` → `PaymentCallbackService` → `PaymentResultProcessor` → `converge` → `accept(ref)`；`uk_attempts_channel_reference` 只挡「同 ref 第二条」。
- **影响**：伪造/串号回调可把 A 单绑定 B 单的渠道流水号 ⇒ 对账两单都异常。当前唯一保护是「不暴露公网」。
- **候选方案**：A 校验 `ref` 与 attempt 已记录的 `channel_reference` 一致（若已有）且未被其他 `payment_no` 占用；B 不做（依赖渠道可信）。
- **推荐**：**A**，与 C-01/C-04 同批落地（都在「回调入站校验」这一层）。
- **是否需要人工决策**：否（属缺陷修复）。

### C-06 🟠 `UNKNOWN` 支付无自动终态出口

- **问题**：`queryAttempts` 达上限后停止自动查询；`Payment.close()` 只接受 `SUCCEEDED/FAILED`；唯一出口是渠道回调 / 人工 `resolve` / 订单取消。
- **证据**：`Payment.java:112-119`（`close()`）、`ChannelQueryService`（查询上限）、`TimeoutScanner` 只扫 `PROCESSING`。
- **影响**：支付永久停留 `UNKNOWN`；无分级升级、无人工队列；`payment.unknown` 告警会长期挂起。
- **候选方案**：A `stage-design §6.2 B2`（分级升级：`payment.unknown_age` 分桶指标 → 告警 → 人工队列）；B 自动终态化（**明确否决**，违反 Constitution §V.7）。
- **推荐**：**A**，且 MUST 在文档中**显式否决 B**。
- **是否需要人工决策**：否（方向已明确）；但分级阈值与人工队列载体需确认。

### C-07 🟠 负净额结算的会计处理未定义

- **问题**：`netMinor ≤ 0` 时不记账，但 `SettlementBatch.compute` 允许负净额 ⇒ `MERCHANT_PAYABLE` 残留未被结算的余额、`SETTLEMENT_PAYABLE` 缺失对应负债；且该批次**永远被判 `CROSS_LEDGER_MISMATCH`**（无 posting 可比）。
- **证据**：`SettlementApplicationService.java:247-253`（`net > 0` 才记账，否则只打 `settlement.ledger_skip_nonpositive_net`）；`SettlementBatch.java:55-56` 注释「净额可为负（MVP 不拒绝）」；`AuditDifferenceKind.CROSS_LEDGER_MISMATCH` 定义。
- **影响**：负净额商户的资金事实无科目承载；`recheck` 永远无法通过；账表核对持续报错。
- **候选方案**：A 负净额记反向分录（`DEBIT SETTLEMENT_PAYABLE` = `CREDIT MERCHANT_PAYABLE`，取绝对值，仍满足 `amount > 0`）；B 负净额禁止结算（商户被卡死）；C 挂 `SUSPENSE`（语义错位）。
- **推荐**：**A**。
- **是否需要人工决策**：**是**——「平台向商户追偿」是否为业务认可语义（涉及资金语义，属人类决策边界）。

### C-08 🟡 `SETTLEMENT` posting 的 `sourceId` 用数值 ID（违反 ADR-0063）

- **问题**：`postSettlement(key, batch.getId(), ...)` ⇒ 账本 `source_id` 是数值 id；而 `batch.batchNo`（`SB+雪花`）已存在可用。
- **证据**：`SettlementApplicationService.java:248-249`；同文件 `224-225` 注释「ledger 侧 SETTLEMENT posting 的 sourceId 是批次 `id`」（已知且有意）；ADR-0063 要求禁数值 ID。
- **影响**：跨账核对键与全仓规范不一致；可追溯性下降（需先查库拿 id 才能查账）。
- **候选方案**：A 改为 `batchNo`（存量需兼容两种键）；B 不改，在 ADR-0063 登记例外。
- **推荐**：**A**（`batchNo` 已在 `SettlementBatch` 中生成并落库，改动局部）。
- **是否需要人工决策**：**是**——存量 `SETTLEMENT` posting 的键兼容策略（属数据/规范口径）。

### C-09 🟠 后置失败无持久化台账（记账 / 履约 / 退款通知）

- **问题**：`FeignLedgerPostingGateway`、`PaymentResultProcessor`、`RefundResultProcessor` 均**吞异常**，只记指标 + 日志；无「待记账 / 待补偿」表；无自动重试。
- **证据**：`FeignLedgerPostingGateway.java:51-56`（catch 不 rethrow）；`PaymentResultProcessor.java` 通知分支 try/catch 忽略；`RefundResultProcessor.postLedger` / `notifyOrder` 同样 catch。
- **影响**：失败只能靠 T+1 对账发现（`MISSING_POSTING` BLOCKER）；「哪一笔没记上」无载体；人工补发。
- **候选方案**：A `stage-design §4.2 G3`（`pending_postings` 表）+ `§6.2 B4`（关键后置 RPC 失败台账）；B 不做。
- **推荐**：**A**，且明确红线：**台账是补偿辅助，资金事实恒以 `postings` 为准**。
- **是否需要人工决策**：**是**（新增关键资金表）。

### C-10 🟠 渠道受理流水号被丢弃 + `ACCEPTED` 状态语义悬空

- **问题（两处，同源）**：① `converge` 的 `UNKNOWN` 分支不调用 `accept` / `backfillChannelReference` ⇒ `ChannelResult.accepted()` 携带的渠道受理流水号**不落库**；② `accept()` 只接受 `PENDING` ⇒ `UNKNOWN → ACCEPTED` 不被允许，而 `ACCEPTED` 在当前代码中**无任何路径能停留**。
- **证据**：`ChannelAttemptRecorderImpl.java:69`（`case UNKNOWN -> attempt.markUnknown(...)`，无 accept）；`ChannelResult.java:64-66`、`86-94`（`accepted` ⇒ `Status.UNKNOWN`，携带 ref）；`AbstractMockChannelAdapter.java:227`（异步退款受理用 `accepted`）；`backfillChannelReference` 仅被 `RefundAttemptSettlementService:104` 调用。
- **影响**：接真实渠道后，退款异步受理期间 `channel_reference` 为 `NULL` ⇒ 渠道对账无法按受理流水号回溯、`uk_attempts_channel_reference` 对该行失效；`UNKNOWN → ACCEPTED` 缺口使支付宝 `WAIT_BUYER_PAY` 场景无法表达「已受理」。
- **候选方案**：A UNKNOWN 分支补 `backfillChannelReference`；B 允许 `UNKNOWN → ACCEPTED`；C 删除 `ACCEPTED`（承认其为预留态）。
- **推荐**：**A 立即**（成本极低）；**B / C 需先确认 `ACCEPTED` 是否在 spec 030 启用**（若启用则 B，若不启用则 C 并删除状态）。
- **是否需要人工决策**：**是**（B/C 涉及状态机变更）。

### C-11 🟠 渠道切换 / 订单取消造成的资金语义空洞

- **问题（两个子场景，同一根因：渠道已扣款但平台不记账）**：
  - **11-B（渠道切换）**：首笔 Payment 停留 `UNKNOWN` 时切换渠道新建第二笔；若首笔随后收敛为 `SUCCEEDED`，则**同一 Transaction 下两笔成功支付** ⇒ 各自记账 ⇒ `CUSTOMER_CASH` 双倍借记；**无代码负责判定「重复支付需退一笔」**。
  - **11-F11b（订单取消）**：支付 `PROCESSING/UNKNOWN` 时订单取消 ⇒ `CLOSED` ⇒ 迟到 `SUCCESS` 回调被吸收 ⇒ 渠道扣款成功、平台 `CLOSED`、不记账。
- **证据**：`PaymentPersistence.java:73-74`（换渠道 ⇒ 新幂等键 ⇒ 新 Payment）；`Payment.java:128-139`（`closeByOrderCancelled` 语义为「拒收后续回调」）；`Payment.java:121-127` 注释**只覆盖**「已 `SUCCEEDED` 不关闭」；`PaymentResultApplier` 终态吸收。
- **影响**：真实资金差异，只能靠对账 `PLATFORM_ONLY` / `STATUS_MISMATCH` 事后发现；当前**无文档写明**该口径。
- **候选方案**：A **不改行为，改文档**（在 L0 + `business-standards.md` 显式写明「渠道事实与平台事实可合法不一致，差异由对账处置」，并把 11-B 登记为已知业务缺口）；B 新增「换渠道前先关闭旧 Payment」的编排规则（**行为变更**）；C 对账侧新增「同 Transaction 多笔成功」专项差异类型。
- **推荐**：**A + C**（文档化 + 可检测）；**B 需人工决策**（涉及支付编排行为变更）。
- **是否需要人工决策**：**是**（B 涉及行为变更；A/C 不需要）。

### C-12 🟠 `QueryStatusRequest` 传平台 `transactionId` 而非渠道交易号

- **问题**：`record QueryStatusRequest(String paymentNo, String transactionId, String idempotencyKey)` 的 `transactionId` 是**平台侧**交易号，不是渠道返回的交易流水号。
- **证据**：`QueryStatusRequest.java`；对照真实渠道（支付宝 `alipay.trade.query` 用 `out_trade_no` = `paymentNo`，尚可用；但若渠道要求 `trade_no`，当前契约无法表达）。
- **影响**：mock 场景无影响；接真实渠道后若渠道查询 API 需要渠道交易号，**契约不足**。
- **候选方案**：A 在 spec 030 的统一契约中补 `channelReference` 入参（ADR-0075 已在改 `ChargeRequest`，可一并覆盖 `QueryStatusRequest` / `RefundRequest`）；B 单独立项。
- **推荐**：**A**（spec 030 是改契约的唯一窗口）。
- **是否需要人工决策**：否（属 spec 030 范围）。

### C-13 🟠 结算事实口径与「差异处置策略化」存在隐藏依赖

- **问题**：`settlementSummary` 只取 `batch.getMatches()`。当前与「未解决差异 = 0 才可结算」组合起来自洽；但 R4「自动挂账后放行」一旦实施，会变成**静默漏结算**。
- **证据**：`ReconciliationApplicationService.java:186-189`（`facts = batch.getMatches().stream()`）；`SettlementEligibility.evaluate(..., unresolvedDifferenceCount)`；`stage-design §5.2 R4` 未识别该依赖。
- **影响**：R4 实施后，被挂账/调账处置的差异对应的事实**不在 `matches` 里** ⇒ 不进结算 ⇒ 商户少收钱且无人发现。
- **候选方案**：A 先把口径改为「全部已确认事实 − 已处置差异的净额影响」再实施 R4；B 保持 `matches` 但禁止 R4 的自动放行。
- **推荐**：**A**，并在 `stage-design §5.2 R4` 补「前置依赖：结算事实口径改造」。
- **是否需要人工决策**：否（顺序约束）。

### C-14 🟠 Resilience4j 无 ADR 支撑且与 ADR-0021 冲突

- **问题**：`circuitbreaker.enabled=true` 已在 payment-service 引入，ADR-0021 明确「当前不引入熔断」。
- **证据**：`stage-design §1.3` / `§6.2 B1` / backlog #5；`ConfiguredChannelRouter` 注释「FR-034 尾注不读 CircuitBreaker 状态」（说明路由刻意不依赖它）。
- **影响**：运维误以为存在该保护层；且它**不在任何设计文档的保障链条内**。
- **候选方案**：A 移除（推荐，`stage-design` 已论证）；B 保留并补 ADR。
- **推荐**：**A**（或 B，但必须补 ADR 并回写 L0）。
- **是否需要人工决策**：**是**（架构取舍）。

### C-15 🟠 退款幂等键自指

- **问题**：`RefundOrder.idempotencyKey = refundNo`（每次新雪花）⇒ 幂等回放分支是**死代码**；终态后同参提交 = 新建退款。
- **证据**：`order-service/src/main/java/com/payment/order/domain/RefundOrder.java:56`（`this.idempotencyKey = this.refundNo;`）；`payment-service/.../refund/application/RefundApplicationService.java:62-64`（`transactionRefundNo != null ? transactionRefundNo : idempotencyKey`）。
- **影响**：`findByIdempotencyKey` 的回放路径永不命中；实际只有「在途守卫」（`lockForIntake` + 累计额度）生效。
- **候选方案**：A 改为调用方提供（order 侧按 `transactionNo + paymentNo + 请求序列` 派生）；B 保持现状并在文档写明「同参重放 = 新建退款」。
- **推荐**：**A**（`stage-design §6.2 B3` 判断正确），但**属行为变更**。
- **是否需要人工决策**：**是**（资金路径行为变更）。

### C-16 🟡 并发建单下 `attemptSeq` 读后写

- **问题**：`attemptSeq = countByTransactionId(...) + 1` 是读后写；两个并发请求会算出同一序号 ⇒ 同一幂等键 ⇒ 第二条撞唯一键回查 ⇒ **返回同一笔支付**。
- **证据**：`PaymentPersistence.java:73`。
- **影响**：行为安全（不会双扣），但语义可疑（「连点两次下单」被合并为一笔）；**无测试覆盖**，且 H2 无法验证真实并发。
- **候选方案**：A 在 T1（Testcontainers-MySQL）落地后补并发测试，确认行为是否符合预期；B 改用 `payments` 行的 `UNIQUE(transaction_id, attempt_seq)` 显式约束（schema 变更）。
- **推荐**：**A 先测**，再决定是否需要 B。
- **是否需要人工决策**：否（先测）。

### C-17 ⚪ 观察项（不构成冲突，但应记录）

| # | 项 | 证据 |
|---|---|---|
| a | `PaymentApplicationService:168` 的 `attempt() == null` 判断是死代码 | `insertPending` 保证非空 |
| b | `SettlementBatch.compute()` 应为 `private` | 只被 `calculate()` 调用 |
| c | `PLATFORM_FEE_REVENUE` 长期空转（所有调用点 `feeMinor=0`）⇒ 手续费分录模板从未被非零验证 | `PaymentApplicationService:223`、`PaymentResultProcessor:189` |
| d | `BalanceChecker.accountBalance()` 已实现但无 HTTP 端点 | `BalanceChecker.java:41-52` |
| e | 重复回调会无条件 `save(attempt)` ⇒ 一次无意义 UPDATE（`version` 递增） | `PaymentResultProcessor` |
| f | `RouteContext.amountMinor` / `currencyCode` 当前未使用（javadoc 已显式标注，做得好） | `RouteContext.java:8-12` |
| g | 选路决策可查「当前规则」，**历史规则快照不可回溯** | `ConfiguredChannelRouter.preview()` |
| h | 文档漂移 D-1~D-6（`stage-design §1.4` 已列） | `payment-service.md:52`（悬空 `§3.11`）、`:281`/`:302`（两个 `### 3.10`）、`adr/README.md:38`/`:128`（ADR-0075/0076 未登记）等 |

### 11.1 冲突汇总

| 严重度 | 数量 | 编号 |
|---|---|---|
| 🔴 阻断 | 1 | C-01 |
| 🟠 高 | 12 | C-02, C-03, C-04, C-05, C-06, C-07, C-09, C-10, C-11, C-12, C-13, C-14, C-15 |
| 🟡 中 | 3 | C-08, C-16 |
| ⚪ 观察 | 8 | C-17 a~h |

**需人工决策的冲突（6 项）**：C-02(B)、C-04、C-07、C-08、C-10(B/C)、C-11(B)、C-14、C-15。

---

## 12. Required Changes

> 分级：**Blocker**（接真实渠道前必须完成）/ **High**（本阶段内应完成）/ **Medium**（可排后）
> 「触碰人类决策边界」= 需先获负责人确认。

### 12.1 Blocker（接真实渠道前 MUST）

| # | 变更 | 理由 | 归属 Feature | 触碰边界 |
|---|---|---|---|---|
| **B1** | 统一账本幂等键口径：`PaymentResultProcessor:188` 改为传 `paymentNo`；`PaymentApplicationService:222` 改为传 `paymentNo`；补两条键断言测试 | C-01，纵深防御退化 + 潜在双记账 | 030（前置修复） | ❌ |
| **B2** | 回调入站校验：`amountMinor` 与平台金额一致性 + `channelReference` 归属校验（两条回调路径同口径） | C-04 / C-05，伪造回调可翻转资金状态 | 030 | ⚠️ 金额不符的处置口径需确认 |
| **B3** | 验签从「恒放行」改为真实实现（至少支付宝 notify 端点的 RSA2 必须真实生效） | `stage-design §6.3 B-5`；一旦内网穿透即打破「不暴露公网」前提 | 030 | ❌ |
| **B4** | `converge` 的 UNKNOWN 分支补 `backfillChannelReference` | C-10①，成本极低、影响渠道对账键 | 030 | ❌ |
| **B5** | `QueryStatusRequest` / `RefundRequest` 补渠道交易号入参 | C-12，真实渠道查询契约不足 | 030（ADR-0075 契约改造） | ❌ |
| **B6** | 文档收口：D-1~D-6 六项漂移 + ADR-0075/0076 登记进 `adr/README.md` 两张表 + traceability | 漂移会让后续 AI/人误读现状 | 第 0 步（docs-only，可直推 master） | ❌ |

### 12.2 High（本阶段内应完成）

| # | 变更 | 理由 | 归属 Feature | 触碰边界 |
|---|---|---|---|---|
| **H1** | `Payment : PaymentAttempt` 基数口径统一（改 L0 文档为「1:1（PAYMENT）/ 1:N（REFUND）」）+ `openPaymentAttempt` 应用层查重断言 | C-02 / C-03 | 030 或独立小项 | ❌ |
| **H2** | `pending_postings` 表 + 记账失败落台账 + 补偿重试入口 | C-09；`stage-design §4.2 G3` | 031 | ⚠️ 新增关键资金表 |
| **H3** | 负净额结算的会计处理（推荐反向分录） | C-07；否则 `MERCHANT_PAYABLE` 残留 + `CROSS_LEDGER_MISMATCH` 永不平 | 031 或 036 | ⚠️ 资金语义 |
| **H4** | 结算事实口径改造（`matches` → 「全部已确认事实 − 已处置差异净影响」）**先于** R4 | C-13；否则 R4 会静默漏结算 | 032（前置） | ❌ |
| **H5** | `UNKNOWN` 分级升级（`payment.unknown_age` 分桶 → 告警 → 人工队列），并**显式否决**自动终态化 | C-06 | 034 | ❌ |
| **H6** | 渠道事实 / 平台事实不一致口径写入 L0 + `business-standards.md`；对账侧新增「同 Transaction 多笔成功」检测 | C-11（A+C） | 032 / 034 | ❌（B 方案才需要） |
| **H7** | 支付宝 notify 端点纳入「密钥 / 完整报文不入日志」的显式约束与测试 | `stage-design §7.2 O3`；Constitution §Security.4 | 035 | ❌ |
| **H8** | Testcontainers-MySQL 渐进落地（先覆盖 ledger 幂等 / settlement 批次唯一 / payment 幂等键三类并发场景） | `stage-design §8.2 T1`；H2 覆盖不到真库并发 | 033 | ⚠️ 测试载体变更 |
| **H9** | schema 迁移可重放（`01-order-schema.sql`、`016-refund-channel-attempt.sql` 两处方言问题） | `stage-design §8.2 T2`；阻塞 reset 与 CI | 033 | ❌ |

### 12.3 Medium（可排后）

| # | 变更 | 理由 | 归属 Feature |
|---|---|---|---|
| **M1** | `SETTLEMENT` posting `sourceId` 改用 `batchNo`（含存量键兼容策略） | C-08 | 032 |
| **M2** | 差异处置策略化（R4）+ 自动处置留 `audit_adjustments` 痕迹 | `stage-design §5.2 R4` | 032 |
| **M3** | 真实账单来源接入 + fail fast（禁静默回退 `sample.csv`） | `stage-design §5.2 R1` | 032 |
| **M4** | 对账事实补 `merchantId`（N1） | C-02 / `stage-design §5.2 R2` | 032 |
| **M5** | Resilience4j 去留裁决（推荐移除） | C-14 | 034 |
| **M6** | 退款幂等键治理（B3） | C-15 | 034 |
| **M7** | 后置 RPC 失败台账（复用 H2 模式） | `stage-design §6.2 B4` | 034 |
| **M8** | SLO 落地（Recording Rule + 看板 + 错误预算告警） | `stage-design §7.2 O1` | 035 |
| **M9** | 告警规则补齐 5 类（结算 UNKNOWN / 记账失败 / 限额泄漏 / DLQ / 染色拒绝） | `stage-design §7.2 O2` | 035 |
| **M10** | ArchUnit 补运行时 RPC 环规则（或明确接受并立 ADR） | `stage-design §8.2 T3` | 033 |
| **M11** | 代码异味清理：C-17 a/b/d/e | 可读性 | 030 顺带 |
| **M12** | 并发建单 `attemptSeq` 行为测试（依赖 H8） | C-16 | 033 |

---

## 13. Human Decisions Required

> 按 Constitution §Governance「人类决策边界」整理。**AI MUST NOT 自行执行**以下任一项。

| # | 决策项 | 边界类型 | 出处 | 与本审查的关系 |
|---|---|---|---|---|
| **H1** | **ADR-0075 / ADR-0076 由 Proposed 转 Accepted** | 重大架构变化 + 新增依赖 | spec 030 §10；`stage-design §9.3 H1` | 030 实现的前置门 |
| **H2** | `payment_attempts.channel_mode` 加列 + 增量迁移脚本 | Database Schema Migration | ADR-0076 R4；`stage-design §9.3 H2` | 030 前置 |
| **H3** | 引入 `alipay-sdk-java`（34.3 MB + ≥9 已知 CVE） | 新增依赖 / 重大架构变化 | ADR-0076 R6；`stage-design §9.3 H3` | 030 前置 |
| **H4** | **回调金额不符的处置口径**（拒绝推进 + 记差异 / 仅记差异 / 忽略） | 资金路径行为口径 | **本轮新增（C-04）** | 决定 B2 的实现语义 |
| **H5** | **负净额结算的会计语义**（平台向商户追偿是否成立） | 资金语义 | **本轮新增（C-07）** | 决定 H3 的方案 |
| **H6** | `SETTLEMENT` posting `sourceId` 改 `batchNo` 的**存量键兼容策略** | 数据/规范口径 | **本轮新增（C-08）** | 决定 M1 |
| **H7** | `PaymentAttemptStatus.ACCEPTED` 去留（保留并在 spec 030 启用 / 删除） | 状态机变更 | **本轮新增（C-10②）** | 决定 C-10 的 B/C |
| **H8** | 「换渠道前先关闭旧 Payment」是否纳入编排（**行为变更**） | 资金路径行为变更 | **本轮新增（C-11-B）** | C-11 的 B 方案 |
| **H9** | `Payment : PaymentAttempt` 是否上 Schema 级唯一约束（可能需拆表） | 数据库结构变更 | **本轮新增（C-02-B / C-03-B）** | C-02 的 B 方案 |
| **H10** | 退款幂等键口径变更（B3，行为变更） | 资金路径行为变更 | `stage-design §9.3 H4` | 决定 M6 |
| **H11** | 对账事实补 `merchantId`（跨服务 DTO 变更） | API Breaking Change | `stage-design §9.3 H5` | 决定 M4 |
| **H12** | ledger 新增余额表 / `period` 列 | 新增关键资金表 | `stage-design §9.3 H6` | 决定 H2 / 031 |
| **H13** | Resilience4j 去留 | 架构取舍 | backlog #5；`stage-design §9.3 H7` | 决定 M5 |
| **H14** | SLO 目标值确认 | 非功能目标 | `stage-design §9.3 H8` | 决定 M8 |
| **H15** | 阶段（stage-05）命名与 Feature 编号分配 | 计划权威 | `stage-design §9.3 H9` | 决定本文档 §14 的矩阵生效 |
| **H16** | 是否放宽「不引入 Testcontainers」现状（Docker 与 CI 一致性） | 测试载体变更 | Constitution §Engineering.3；`stage-design §9.3 H10` | 决定 H8 |

**最小裁决集（进入实现前必须拿到结论的 6 项）**：**H1、H2、H3、H4、H15、H16**。
其余可在对应 Feature 立项时再裁决。

---

## 14. Final Recommendation

### 14.1 对 `stage-design.md` 的结论

**通过，但需修订后生效。**

| 项 | 判定 |
|---|---|
| 方向（渠道纵深 / 账务可审计 / 工程欠账） | ✅ **正确**，与本轮代码走查结论一致 |
| 现状评估（§1） | ✅ 基本准确，**3 处需修正**（§1.3 A-1/A-2/A-3） |
| 目标态（§2~§8） | ✅ 方向正确，**2 处需补前置依赖**（R4 依赖结算口径改造 = C-13；B2 需显式否决自动终态化 = C-06） |
| Feature 拆分（§9.2） | ⚠️ **顺序需调整**——须在 031 之前插入「030 前置收口」（本文 §12.1 的 B1~B6） |
| 人类决策清单（§9.3） | ✅ 准确，**需补 5 项**（H4~H8） |
| 风险总表（§9.4） | ✅ 准确 |

### 14.2 建议的执行顺序（4 个门）

```
门 0 ── 文档收口（B6）+ 最小裁决集（H1/H2/H3/H4/H15/H16）
          │  docs-only，可直推 master；不依赖任何裁决
          ▼
门 1 ── 030 前置收口（B1~B5）
          │  纯缺陷修复 + 契约补参；B1/B4 零 schema 变更，可立即做
          │  ⚠️ B2/B3 依赖 H4 裁决
          ▼
门 2 ── Feature 030 实现（统一契约 + 类型化凭证 + 染色 + 模态落库 + 支付宝沙箱 + notify 端点）
          │  依赖门 1 完成 + ADR-0075/0076 转 Accepted
          ▼
门 3 ── 031~035（账务纵深 / 对账纵深 / 测试基础设施 / 可靠性加固 / 可观测）
```

**关键理由**：
1. **B1 必须在 030 之前**——030 要改 `ChannelResult` / `ChargeRequest` 契约，会触及 `PaymentResultProcessor` 与 `PaymentApplicationService` 两个账本调用点。若先做 030 再修 B1，会在同一批文件上改两次，且**存在把 `PAYMENT:PAYMENT:` 键写进新代码的风险**。
2. **B4 成本极低但影响渠道对账键**——030 引入真实渠道后，异步受理的 `channel_reference` 是渠道对账的主键，不能等到 031 再补。
3. **031（ledger 纵深）必须在 030 之后**——030 会新增 `channel_mode` 列与 notify 端点，账务侧需要先看到「真实的、有模态的渠道事实」，才知道余额/期间视图要承载什么维度。
4. **033（测试基础设施）应与 031 并行**——031/032 会新增真库唯一约束与并发路径，H2 覆盖不到；先建 Testcontainers 基础设施能避免「写完再补测试」的返工。

### 14.3 本阶段「不做」的明确清单（防止范围蔓延）

- ❌ 真实出款 / 银行对接 / 多币种清分 / 税费分账（`stage-design §5.2 R3` 已明确）。
- ❌ K8s / Service Mesh / API 网关 / 新中间件 / 分库分表 / CQRS / Event Sourcing（`stage-design §2.4`）。
- ❌ 任何「超时即失败」的自动终态化（违反 Constitution §V.7）。
- ❌ 为「渠道切换造成的双成功」新增自动退款编排（属行为变更，须先裁决 H8）。
- ❌ 把 `stage-design` 的 `【目标】` 项写入 L0 文档（会造成虚假合规）。

---

## 附 A — Feature 实施矩阵（自 030 起）

> 编号为**建议**，实际分配须经 H15 裁决；`030` 为已存在的 spec（四件套齐备，待实现）。
> 列：Feature / 前置依赖 / 核心目标 / 涉及服务 / 涉及数据库 / 风险 / 验证方式

### 第 0 步（非 Feature，先做）

| Feature | 前置依赖 | 核心目标 | 涉及服务 | 涉及数据库 | 风险 | 验证方式 |
|---|---|---|---|---|---|---|
| **0 · 文档收口**（docs-only） | 无 | 修 D-1~D-6 六项漂移；ADR-0075/0076 登记进 `adr/README.md` 索引 + `traceability.md`；统一 `Payment:PaymentAttempt` 基数表述（H1 的文档部分）；写入「渠道事实 / 平台事实可合法不一致」口径（H6 的文档部分） | 无（仅 `docs/`） | 无 | 低（改错文档会传播错误；须逐条比对代码） | ① 全部相对链接可达；② `payment-service.md` 无悬空锚点、无重复 `### 3.10`；③ 基数表述在 `technical-solution.md` 与 `systems/payment-service.md` 一致 |

### 030 前置收口（建议作为 030 的 `tasks.md` 前若干任务，或独立 `fix/` 分支）

| Feature | 前置依赖 | 核心目标 | 涉及服务 | 涉及数据库 | 风险 | 验证方式 |
|---|---|---|---|---|---|---|
| **030-P · 一致性收口** | 门 0 完成；H4 裁决（B2 语义） | B1 统一账本幂等键口径（两调用点 + 两条测试）；B2 回调金额与渠道流水号校验（两条路径同口径）；B4 `converge` UNKNOWN 分支回填 `channelReference`；C-17 a/b/d/e 异味清理 | payment-service | 无 schema 变更 | ① 改幂等键可能影响存量 posting 的查询口径；② 校验收紧可能拒绝掉当前被接受的合法回调（须先用 E2E 回归） | ① 单测断言同步路径与回调路径产生**同一** `PAYMENT:{paymentNo}` 键；② 伪造金额/串号回调被拒且原状态不变；③ 异步受理后 `channel_reference` 非空；④ 既有 `mvnw clean verify` 零回归 |

### 主 Feature 序列

| Feature | 前置依赖 | 核心目标 | 涉及服务 | 涉及数据库 | 风险 | 验证方式 |
|---|---|---|---|---|---|---|
| **030 · channel-contract-dye-sandbox** | 门 0；**030-P**；ADR-0075/0076 → **Accepted**（H1）；H2 / H3 / H7 裁决 | 统一渠道契约（四组结构化字段 + `channelExtra`）；类型化 `PayCredential`；`X-Dye-Tag` 全链路染色（入站读与出站写**同一批**）；`payment_attempts.channel_mode` 落库 + 反向三路径按记录还原模态；单 Adapter 双模态（`AlipayChannelAdapter`，MOCK 分支 MUST `super`）；`AlipayGateway` 端口收口 SDK（`application/**` MUST NOT 依赖 `com.alipay.sdk`）；支付宝 RSA2 notify 端点（**必须返回纯文本 `success`**）；demo 沙箱开关 | payment-service（主）、`common-core`、`common-dto`、`mock-channel-web`（demo 页） | `payment_attempts` **加列** `channel_mode VARCHAR(16) NOT NULL DEFAULT 'MOCK'`（schema 三处齐备）；增量迁移 `030-...sql` | ① 染色只做一半 ⇒ 重演「全线 403」先例（ADR-0034 教训）；② 沙箱不可复现 ⇒ 真机验证被跳过、假绿；③ SDK 34.3 MB + ≥9 CVE 供应链风险；④ 沙箱超时 10s 与全局 1.5s 两档并存易误配 | ① 契约单测：四家参数映射表驱动 + 6 处测试桩 / 4 处构造点零改动编译；② 响应头回写 `X-Dye-Tag` + MDC `dyeMode` + 跨服务透传（单测 + E2E）；③ 沙箱单的退款/查询/超时扫描断言 `channel_mode=SANDBOX` 被正确还原；④ **固定签名向量**离线单测钉死签名/验签/参数排序；⑤ 手工 live（**未验证范围 MUST 显式记录，不得默认通过**）；⑥ ArchUnit：`application/**` 不依赖 `com.alipay.sdk` |
| **031 · ledger-account-view** | 030 完成（账务需承载真实渠道事实）；H12 裁决 | 科目余额视图（G1）；期间与试算平衡 + 关账（G2）；`pending_postings` 待记账清单 + 补偿重试（G3，含 C-09 与 H3 负净额会计） | ledger-service、payment-service（写台账）、settlement-service（写台账） | 新增 `pending_postings` 表；`postings` **加列** `period`；余额表（若选方案②） | ① 余额表与分录漂移（须同事务更新 + 试算平衡校验）；② 负净额会计语义（H5）；③ 新增关键资金表 | ① 试算平衡表借贷恒等（各币种）；② 关账后 `POST /postings` 拒绝新分录；③ 待记账清单可查可补偿；④ 负净额批次产生反向分录且 `CROSS_LEDGER_MISMATCH` 可对平；⑤ `mvnw clean verify` 全绿 |
| **032 · reconciliation-real-statement** | 031 完成（余额/期间视图是账表核对基础）；H11 裁决 | **先做 C-13 结算事实口径改造**；再 R1 真实账单来源（fail fast，禁静默回退）；R2 商户维度（N1，匹配键改 `(merchantId, reference)`）；R4 差异处置策略化（含 M1 `SETTLEMENT` sourceId 改 `batchNo`） | reconciliation-service、settlement-service、payment-service（`confirmed-facts` 出参）、`common-dto` | `reconciliation_batches` / `audit_*`（可能加列）；`SettlementApplicationService` 的 sourceId 口径 | ① **跨服务 DTO 变更（API Breaking）**；② 口径改造未先做 ⇒ R4 静默漏结算；③ 真实账单格式差异 ⇒ 解析失败须 fail fast | ① 结算事实 = 全部已确认事实 − 已处置差异净影响（构造「挂账后放行」用例断言不漏结算）；② 真实来源不可用时显式失败，**不**回退 `sample.csv`；③ 跨商户同号事实不串账；④ 差异可重复识别、可查询、可处置，原始事实不被改写 |
| **033 · test-infrastructure** | 无强依赖（**建议与 031/032 并行**）；H16 裁决 | T1 Testcontainers-MySQL 渐进（先建基类 → 先切并发/唯一键三类 → 再逐模块）；T2 schema 迁移可重放 + CI 门禁；T3 ArchUnit 运行时 RPC 环规则（或立 ADR 明确接受）；M12 并发建单 `attemptSeq` 行为测试 | 全模块（构建层）、`deployment/architecture-tests`、`deployment/schema` | 无（测试载体与迁移脚本） | ① Docker 可用性与 CI 一致性是真实阻塞（Constitution 已标注）；② 方言差异（约束/时区/JSON）⇒ 假绿；③ 迁移改动可能破坏存量库 | ① ledger 幂等唯一键冲突、settlement 批次唯一约束、payment 幂等键三类并发场景在 MySQL 上通过；② 迁移在**空库**与**存量库**各重放一次结果一致；③ ArchUnit 新规则能拦住已知的 order ↔ payment 运行时环；④ `mvnw clean verify` 全绿 |
| **034 · reliability-hardening** | 031 完成（复用台账模式）；H13 / H10 / H8 裁决 | C-14 熔断去留（推荐移除）；H5 `UNKNOWN` 分级升级（**显式否决自动终态化**）；M6 退款幂等键治理；M7 后置 RPC 失败台账（复用 031 的 `pending_postings` 模式）；H6 对账侧「同 Transaction 多笔成功」检测 | payment-service、order-service、reconciliation-service | 可能新增失败台账表（若与 `pending_postings` 不同源） | ① 熔断移除可能改变出站行为（须确认无实际依赖）；② 退款幂等键变更是**资金路径行为变更**；③ 换渠道编排变更（H8）影响下单流程 | ① `payment.unknown_age` 分桶指标 + 告警 + 人工队列可见；② 同参重放返回首次结果（**当前是死代码，改后必须由测试钉死**）；③ 后置失败可在台账查询并重试；④ 同 Transaction 双成功被对账识别为专项差异；⑤ 既有可靠性测试零回归 |
| **035 · observability-slo** | 031/032/034 产出新指标；H14 裁决 | M8 SLO 落地（Recording Rule + 看板 + 错误预算告警，4 项：同步查询 P99 ≤ 500ms / 同步命令 P99 ≤ 1s / 资金入口可用性 ≥ 99.9% / 对账达成率 ≥ 99%）；M9 补齐 5 类告警（结算 UNKNOWN 堆积 / `ledger.posting_failed` 堆积 / 限额在途泄漏 / DLQ 增长 / 染色非法值拒绝率）+ 每条的 runbook 处置动作；H7 密钥 / 完整报文不入日志的显式约束与测试 | 全服务（配置为主）、payment-service（密钥日志约束） | 无 | ① SLO 目标值是 `[目标]`，MUST 先确认（H14）；② 告警阈值过低会噪声、过高会漏报；③ 脱敏未重新引入时，「密钥不入日志」只能靠显式约束 + 测试而非通用脱敏 | ① 4 项 SLO 可在 Grafana 查询且与错误预算告警联动；② 12 条告警规则全部有 runbook；③ 沙箱适配器与 notify 端点的日志断言不含密钥/完整报文 |

### 附 A.1 依赖关系图

```
[0 文档收口] ──┬──► [030-P 一致性收口] ──► [030 渠道契约/染色/沙箱]
               │                                   │
               │                                   ├──► [031 ledger 纵深] ──┬──► [032 对账/结算纵深]
               │                                   │                       │
               └───────────────────────────────────┴──► [033 测试基础设施] ─┘
                                                                           │
                                          [034 可靠性加固] ◄───────────────┤
                                          [035 可观测 SLO]  ◄──────────────┘
```

### 附 A.2 与本审查冲突清单的映射

| 冲突 | 落地位置 |
|---|---|
| C-01 | 030-P（B1） |
| C-02 / C-03 | 0（文档）+ 030-P / 030（应用层断言）；Schema 方案待 H9 |
| C-04 / C-05 | 030-P（B2） |
| C-06 | 034（H5） |
| C-07 | 031（H3） |
| C-08 | 032（M1） |
| C-09 | 031（H2）+ 034（M7） |
| C-10 | 030-P（B4，①）+ 030 / 待 H7（②） |
| C-11 | 0（文档）+ 032/034（检测）+ 待 H8（编排） |
| C-12 | 030（B5） |
| C-13 | 032（**前置**） |
| C-14 | 034（H13） |
| C-15 | 034（H10） |
| C-16 | 033（M12） |
| C-17 a/b/d/e | 030-P |
| D-1~D-6 | 0（B6） |

---

## 附 B — 审查方法与局限

**方法**：以代码为唯一事实来源，逐文件读核心聚合 / 应用服务 / 基础设施实现 / Schema / 测试，再与 `stage-design.md` 逐条对照；对每处结论给出 `文件:行号` 证据。

**已读代码范围**（`payment-service`）：`Payment`、`PaymentAttempt`、`PaymentStatus`、`PaymentAttemptStatus`、`PaymentApplicationService`、`PaymentPersistence`、`PaymentResultProcessor`、`PaymentResultApplier`、`PaymentUnknownResolutionService`、`PaymentCallbackService`、`ChannelCallbackController`、`ChannelCallbackSignatureFilter`、`ChannelCallbackRequest`、`PaymentController`、`PaymentRetryService`、`TimeoutScanner`、`ChannelQueryScheduler`、`ChannelQueryService`、`ChannelAttemptRecorderImpl`、`ChannelResult`、`ChargeRequest`、`QueryStatusRequest`、`RefundRequest`、`PaymentChannel`、`ChannelRouter`、`ConfiguredChannelRouter`、`RouteContext`、`ChannelRegistry`、`SpringChannelRegistry`、`AlipayChannelAdapter`、`AbstractMockChannelAdapter`（引用）、`LedgerPostingGateway`、`FeignLedgerPostingGateway`、`RefundApplicationService`、`RefundResultProcessor`、`RefundFeignLedgerPostingGateway`、`PaymentCaptureLedgerPostingTest`；`ledger-service`：`Posting`、`LedgerEntry`、`LedgerSourceType`、`Account`、`LedgerPostingService`、`BalanceChecker`、`LedgerController`；`reconciliation-service`：`ReconciliationMatching`、`ReconciliationApplicationService`、`ReconciliationBatch`、`DifferenceType`、`AuditApplicationService`、`AuditDifferenceKind`、`CertificateAuditor`；`settlement-service`：`SettlementApplicationService`、`SettlementBatch`、`SettlementEligibility`、`ConfirmedFactGate`；`common-redis-mq`：`TransactionalProducer`；`order-service`：`RefundOrder`（幂等键）；`deployment/schema`：`03-payment-schema.sql`、`09-ledger-schema.sql`、`10-audit-schema.sql`。

**局限（诚实标注）**：

1. **未运行测试**：本审查未执行 `mvnw clean verify` / E2E，所有行为结论来自静态阅读。**未验证**：并发行为（H2 无法覆盖）、真实 MySQL 的唯一约束触发路径。
2. **未读全量**：`LedgerAuditor` / `RealAuditor` / `ReportAuditor` 内部实现、`RefundAttemptSettlementService`、`LimitGate` / 限额域、`order-service` 支付编排、`fulfillment` / `entitlement` 未逐行审查；§8 Q6 的「四核对完整」结论基于 `AuditApplicationService` 的分派逻辑与 `AuditDifferenceKind` 的 11 类定义，**未验证每个审计器的判定正确性**。
3. **未读 spec 030 四件套全文**：§3.2 的目标态描述取自 `stage-design §3.2` 的转述；**spec 030 的 `tasks.md` / `plan.md` 未逐条核对**，因此 030-P 的落点（作为 030 的前置任务还是独立分支）需在 030 立项时确认。
4. **`attemptSeq` 并发行为**（C-16）为**推理结论**，未经实测。
5. **`stage-design.md` 的 §1.4 漂移清单**（D-1~D-6）**未逐条复核**（仅抽查 D-1/D-2/D-3 的路径存在性），其余条目沿用其结论。

**下一步建议**：本审查完成后，请先就 §13 的**最小裁决集（H1、H2、H3、H4、H15、H16）**给出结论；随后按 §14.2 的 4 个门推进。**在裁决落地前，不进入任何代码实现。**
