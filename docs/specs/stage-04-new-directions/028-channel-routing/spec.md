# Spec: 028-channel-routing（payment-service 两层结构重构 + 支付渠道路由）

**版本**：1.0
**日期**：2026-09-16
> **Status**: Implemented — [spec.md](spec.md) / [plan.md](plan.md) / [tasks.md](tasks.md) / [acceptance.md](acceptance.md) 已闭环；ADR-0072/0073 已 Accepted，代码已实现（批次 A–G 全绿、`mvn -o clean verify -fae` 16 模块 BUILD SUCCESS，已 `--no-ff` 合并 master） <!-- Draft | In Review | Approved | In Development | Implemented | Deprecated | Superseded | Not Implemented -->
**分支**：`docs/spec-028-channel-routing`（纯文档）→ 实现期另开 `feature/028-channel-routing`
**决策**：
- [ADR-0072](../../../adr/0072-two-layer-channel-architecture.md)（🟡 Proposed）—— payment-service 两层结构：payment 支付层 / channelAttempt 渠道层
- [ADR-0073](../../../adr/0073-channel-routing.md)（🟡 Proposed）—— 支付渠道路由：注册表 + 规则化确定性选路

> 本 Spec 承载**两项互相依赖**的结构变更。ADR-0072 定结构（渠道层成为真正的层 + 渠道身份），ADR-0073 定选路规则；
> 两者必须有同一份 spec 落地——先拆三渠道再回头收口，等于把同一批文件动两遍，中间态比现状更别扭。

## 1. 背景与目标

### 1.1 背景：两个「文档许诺了、代码从未实现」的缺口

**缺口 A（结构）**：文档里始终有「payment 层 / 渠道层」的**原则**，但从未定义**结构**。

| 出处 | 原文 | 性质 |
|---|---|---|
| ADR-0054 决策 2 | 「payment **层编排支付指令**：渠道支付落 `payment_attempts` + 记账」 | 职责动作 |
| `payment-service.md` §2 | 「**Payment ≠ Channel**：核心 Payment 领域只依赖 `PaymentChannel` 接口，不依赖 `infra/channel` 具体实现」 | 边界原则 |
| `technical-solution.md` §4.3 | `P->>P: 创建 Payment + PaymentAttempt（本地事务）` | 现状描述 |

没有职责边界、没有表归属、没有实现族、没有两层交互契约。**现状是符合已写文档的**——问题不在实现跑偏，而在文档从未定义结构。

**缺口 B（路由）**：`payment-service.md` §2.1 写 `channelCode`「**必须已注册到渠道 Registry/Router**」——**这两样东西在代码里都不存在**。

### 1.2 代码现状（2026-09-16 核对真实代码）

| # | 事实 | 证据 |
|---|---|---|
| S1 | `PaymentPersistence`（application 层）在**同一事务**内写 `payments` + `payment_attempts` 两张表 | `PaymentPersistence.insertPending()` / `applyAndPersist()` |
| S2 | `PaymentResultApplier.apply(Payment, PaymentAttempt, ChannelResult)` 一个方法**同时推进两个聚合**的状态机 | `PaymentResultApplier.java:20` |
| S3 | 渠道实现层**只有 1 个文件** `MockChannelAdapter`，无任何 per-channel 形状 | `infra/channel/` 仅此一文件 |
| S4 | 渠道端口层仅 1 接口 + 4 DTO + 1 回调接口；`PaymentChannel` **无渠道身份方法** | `application/channel/` 共 6 个文件 |
| S5 | **退款尝试的 `channel_code` 硬编码 `"mock"`** | `PaymentRefundService.java:89` |
| S6 | `RefundRequest.channelCode` 是**死字段**；退款引用硬编码 `"mock-refund-ref-"` | `MockChannelAdapter.java:201-226` |
| S7 | 支付侧渠道身份靠调用方传入的 `ChargeRequest.channelCode()` | `MockChannelAdapter.channelPrefix(request.channelCode())` |
| S8 | `channelCode` 必填，决策权在 order-service | `CreateOrderPaymentRequest(@NotBlank String channelCode)` |
| S9 | 幂等键含 `channelCode`：`payment:{orderNo}:{channelCode}:{attemptSeq}` | `PaymentPersistence.java:47` |
| S10 | 全仓约 30 个测试文件直接 `new MockChannelAdapter(...)` 注入；约 10 个测试桩只实现 `PaymentChannel` | `PaymentTestStack.appService(PaymentChannel)` 等 |
| S11 | 熔断（Resilience4j）只包 Feign 出站 RPC，不是渠道健康 | `payment-service/pom.xml`、`application.yml` |

> **S5 是数据正确性缺陷**：WECHAT 支付单退款后，退款尝试行记录的渠道是 `"mock"`。当前只有单一 Mock 渠道所以不触发测试失败，属**潜伏缺陷**——拆出三渠道即刻暴露，并污染退款对账事实。

### 1.3 目标

1. **让「渠道层」成为真正的层**：渠道层拥有 `PaymentAttempt` 聚合与 `payment_attempts` 表的写入口；payment 层不再直接操作 attempt；
2. **让「一个渠道」成为真实概念**：一个渠道一个 Adapter 实现 + 渠道身份 `channelCode()`；
3. **让「选路」成为平台能力**：调用方可不指定渠道，由 payment-service 按规则选出；
4. **修正渠道事实**：退款尝试的渠道归属取自原始支付记录，消除硬编码 `"mock"`；
5. **守住已拍板不变量**：不改 INV-1（扣库存归属）、INV-2（换渠道由调用方发起）、幂等键结构与状态机语义；
6. **消除文档漂移**：`payment-service.md` §2.1 的「Registry/Router」从悬空描述变成事实；`technical-solution` 的两层描述与实际结构一致。

### 1.4 为什么一起做

- 结构（ADR-0072）与路由（ADR-0073）**动的是同一批文件**（`PaymentChannel` / `infra/channel/**` / `PaymentPersistence` / 4 个应用服务），分两次做等于动两遍；
- 路由的前置正是「渠道有身份」——不做结构，注册表没有 key 可挂；
- 先拆三渠道、后收口结构，中间态（三渠道实现却仍由 payment 层写 attempt、退款渠道仍硬编码）比现状更别扭。

## 2. 硬性不变量（不可破坏）

- **INV-1（回调成功链路，spec 015）**：回调成功 → 支付层更新支付单 → RPC 通知订单层 → 订单层一次性推进「订单 PAID + 交易 SUCCEEDED + **确认扣库存**」。**扣库存必须由 order-service 发起，不得移到 payment-service。** 本 Spec 完全不触碰此链路。
- **INV-2（换渠道，spec 015）**：用户换支付方式 → **以同一订单号新建一张支付单**；旧支付单保留 FAILED、不调用 `Payment.close()`。**渠道切换的决策权属于调用方。**
  > 推论（硬红线）：**Router 绝不在渠道失败后自动改选其他渠道**。一期不做自动降级不是「暂缓」，而是**与 INV-2 互斥**。
- **INV-3（路由确定性，本 Spec 新增）**：相同 `RouteContext` MUST 返回相同 `channelCode`。一期**禁止随机数与进程级计数器参与选路**。
  > 依据 S9：幂等键含 `channelCode`。若路由漂移，同一订单重试会落到不同渠道 → 不同幂等键 → 拆出多张支付单，破坏「重复请求不得产生第二次资金动作」。
- **INV-4（分层，本 Spec 新增）**：`ChannelRouter` **只产出 `channelCode` 字符串**，不接触渠道协议；渠道协议实现留在 `infra/channel/**`。`application/channel/**` MUST NOT 依赖 `infra/channel/**`（构建期 ArchUnit 断言）。
- **INV-5（写入口归属，本 Spec 新增）**：`payments` 表**只由 payment 层写**；`payment_attempts` 表**只由渠道层写**。payment 层 MUST NOT 直接依赖 `PaymentAttemptRepository`。
  > **边界澄清**：这是**职责分层**，不是拆事务。两层仍共享同一本地事务——`payments` 与 `payment_attempts` 状态必须同时迁移，否则出现 `payment=SUCCEEDED / attempt=PENDING` 之类的永久不一致。
- **INV-6（反向按记录解析，本 Spec 新增）**：退款 / 重试 / 主动查询 MUST 使用 `payment_attempts.channel_code` 记下的渠道，**禁止重新路由**。
  > 资金安全红线：退款换渠道 = 钱退错地方。`attempt_type=REFUND` 行的渠道 MUST 取自被退支付单的生效支付渠道（该 `payment_no` 下 `attempt_type=PAYMENT` 那行），**禁止硬编码**（消除 S5）。

## 3. 关键用户故事

### US1 - 渠道层成为真正的层，三渠道成真（Priority: P1，结构前置）

**As** 架构维护者，**I want** 渠道层拥有自己的聚合、表与实现族，**so that** payment 层不必也不该知道外部渠道如何实现，且渠道事实在库层面正确。

**Why this priority**：这是 ADR-0072 的核心，也是 US2/US3 的前提——渠道没有身份，注册表就没有 key。

**Independent Test**：`infra/channel/` 下存在 4 个渠道类（抽象基类 + 3 实现），`PaymentChannel.channelCode()` 在端口上；对一个 WECHAT 支付单发起退款，断言退款 attempt 行的 `channel_code == 'WECHAT'`（而非 `'mock'`）。

**Acceptance Scenarios**：

1. **Given** 三个渠道 Adapter 已装配，**When** 查询 `ChannelRegistry.registeredCodes()`，**Then** 返回 `{ALIPAY, WECHAT, DOUYIN, MOCK}`（含兼容用的 MOCK）。
2. **Given** 一笔 `channel_code=WECHAT` 的已成功支付，**When** 发起退款，**Then** `payment_attempts` 中 `attempt_type=REFUND` 那一行的 `channel_code == 'WECHAT'`，且渠道引用带 `wechat` 前缀。
3. **Given** payment 层代码，**When** 静态检查，**Then** payment 层无任何 `PaymentAttemptRepository` 的直接依赖（INV-5）。
4. **Given** 一次支付调用链，**When** 渠道返回结果，**Then** `PaymentResultApplier` 不再由单一方法同时推进两个聚合——attempt 侧由渠道层收敛、payment 侧由支付层推进。

---

### US2 - 不指定渠道，由平台选路（Priority: P1）

调用方（order-service / 收银台 / 流量脚本）在下单后发起支付时**不再指定渠道**，payment-service 按配置规则选出一个可用渠道并回落最终渠道码。

**Independent Test**：配置 `ALIPAY priority=10 / WECHAT priority=20`，`POST :8083/orders/{id}/payments` body **不带** `channelCode`，断言 `payment_attempts.channel_code == 'ALIPAY'`。

**Acceptance Scenarios**：

1. **Given** `ALIPAY enabled=true priority=10`、`WECHAT enabled=true priority=20`，**When** 不传 `channelCode`，**Then** 支付单落在 ALIPAY，响应 `channelCode=ALIPAY`。
2. **Given** `ALIPAY enabled=false`、`WECHAT enabled=true priority=20`，**When** 不传 `channelCode`，**Then** 支付单落在 WECHAT。
3. **Given** 全部渠道 `enabled=false`，**When** 不传 `channelCode`，**Then** 返回 `409 NO_AVAILABLE_CHANNEL`，**且不产生任何支付单落库**。
4. **Given** 同一上下文，**When** 连续选路 100 次，**Then** 结果完全一致（INV-3）。

> **断言口径（修订）**：渠道归属一律断言 `payment_attempts.channel_code` **列**，**不以渠道引用的字符串形态作为验收标准**。引用前缀只是排障可读性，不承载渠道归属语义。

---

### US3 - 显式指定渠道，Router 零干预（Priority: P1）

调用方显式指定 `channelCode` 时，行为与今天**完全一致**，Router 不介入、不改写。

**Why this priority**：向后兼容与 INV-2 的保障。若这条不成立，既有收银台「换渠道」按钮与 `traffic-gen.sh` 会集体失配。

**Independent Test**：`POST :8083/orders/{id}/payments` body `{"channelCode":"DOUYIN"}`，断言支付单落在 DOUYIN，即使配置里 `DOUYIN enabled=false`。

**Acceptance Scenarios**：

1. **Given** 任意渠道配置，**When** 显式传 `channelCode=DOUYIN`，**Then** 支付单落在 DOUYIN，指标 `result=explicit`。
2. **Given** 显式传 `channelCode=NOT_EXIST`，**When** 发起支付，**Then** 返回 `400 INVALID_ARGUMENT`，错误信息**列出当前已注册渠道清单**（不静默回落）。
3. **Given** 显式传一个 `enabled=false` 的渠道，**When** 发起支付，**Then** **仍按其执行**（`enabled` 语义是「别自动挑我」，不是「禁止使用」），记 warn 指标。

---

### US4 - 路由可观测与可运维（Priority: P2）

运维能从指标与日志看出「这次走了显式指定还是自动选路、选了哪个、有没有选不出来」。

**Independent Test**：触发三类路径（显式 / 自动 / 无可用渠道），断言 `payment_routing_total` 的 `result` 三个标签各计一次。

**Acceptance Scenarios**：

1. **Given** 一次自动选路，**When** 请求完成，**Then** `payment_routing_total{result="routed", routed="ALIPAY"}` 计数 +1。
2. **Given** 一次无可用渠道，**When** 请求完成，**Then** `payment_routing_total{result="no_available_channel"}` +1，并落一条 WARN 日志。
3. **Given** 任意一次路由，**When** 查看日志，**Then** 存在 INFO 含 `requestedCode` / `routedCode` / 决策依据，且携带 `traceId`。

## 4. 功能需求（FR）

### 4.1 两层结构与渠道身份（ADR-0072）

- **FR-001** `PaymentChannel` 端口新增 `String channelCode()`（返回大写渠道码）。**约 10 个测试桩需补一处实现**——这是结构重构的必要成本，不再以「另起 `ChannelProvider` 接口」绕开。
- **FR-002** `payment_attempts` 表的写入口收敛到渠道层：新增 `application/channel/ChannelAttemptRecorder` 端口（创建/收敛一次渠道交互），其实现持 `PaymentAttemptRepository`。
- **FR-003** `PaymentPersistence` 移除 `PaymentAttemptRepository` 依赖：`payments` 表的创建与状态推进由 payment 层负责；`payment_attempts` 的创建与收敛经 FR-002 端口委托给渠道层（**同一本地事务内**，INV-5 边界澄清）。
- **FR-004** `PaymentResultApplier` 拆分为两侧各自的推进：渠道层负责 `attempt` 的收敛（`accept` / `succeed` / `fail` / `markUnknown`），payment 层负责 `payment` 的状态迁移；两者由调用方在同一事务内协调。
- **FR-005** 退款尝试的渠道归属：`PaymentAttempt.refundAttempt(...)` 的 `channelCode` MUST 取自被退支付单的生效支付渠道，**禁止硬编码**（消除 S5）。
  - 生效支付渠道的取数口径：该 `payment_no` 下 `attempt_type=PAYMENT` 且状态为 `SUCCEEDED` 的那一行的 `channel_code`。
- **FR-006** `RefundRequest.channelCode` 从死字段变为**实读**：`AbstractMockChannelAdapter.refund()` 用 `channelCode()` 生成带渠道身份的引用（与 `charge()` 口径一致）。同时移除退款引用的硬编码 `"mock-refund-ref-"`（消除 S6）。
- **FR-007** 存量数据：历史 `attempt_type=REFUND` 行的 `channel_code='mock'` **不回填**（属历史事实，改写会污染审计链）；spec 与 ADR 中明确记录该批数据的存在与成因。
- **FR-008** ArchUnit 新增断言：`application/channel/**` MUST NOT 依赖 `infra/channel/**`（INV-4）；`payment` 包 MUST NOT 直接依赖 `PaymentAttemptRepository`（INV-5）。

### 4.2 渠道实现族

- **FR-009** 新增 `infra/channel/AbstractMockChannelAdapter`（**抽象、非 Bean**），逐字承载 4 件横切行为，三渠道行为一致、不得分叉：

  | # | 行为 | 出处 |
  |---|---|---|
  | 1 | 金额尾数确定性故障注入（11→超时 / 12→无结论 / 15→业务拒绝） | spec 022 / T429 |
  | 2 | 退款「受理 + 异步推送」（`RefundResultListener`） | spec 019 / D7 |
  | 3 | **每 Adapter 独立 `runId`**（UUID 派生） | `MockChannelAdapter.java:66-71` |
  | 4 | `mock-scenario` 严格枚举解析（坏值 FAIL FAST） | ADR-0049 第 2 条 |

- **FR-010** 新增三个渠道实现（`@Component`）：`AlipayChannelAdapter`（`ALIPAY`）、`WechatChannelAdapter`（`WECHAT`）、`DouyinChannelAdapter`（`DOUYIN`）。三者只提供**身份 + 差异**（各自可配 scenario 与退款异步延迟）。
- **FR-011** `MockChannelAdapter` 保留原名与全部既有构造签名（`()` / `(Scenario)` / `(Scenario, long)` / `(String, long)` / Spring 主构造），改为继承基类并声明 `channelCode() = "MOCK"`——**兼容既有约 30 个测试文件与未指定渠道的旧脚本**。
- **FR-012** `runId` MUST 为**每 Adapter 实例独立**（不得由共享配置注入同一值）：若三个 Adapter 共享 `runId`，各自 `refGen` 都从 1 起会撞 `uk_attempts_channel_reference`。
- **FR-013** 金额尾数注入**不允许 per-channel 差异化**（spec 022 的 E2E 依赖全渠道一致）。
- **FR-014** 全局 `payment.channel.mock-scenario` 保留为**默认值**，per-channel 配置可覆盖（向后兼容，`MockChannelAdapterScenarioTest` 断言的消息文本继续有效）。

### 4.3 注册表与路由器（ADR-0073）

- **FR-015** 新增 `application/channel/ChannelRegistry` 端口：`PaymentChannel resolve(String channelCode)`（未注册抛错）、`Set<String> registeredCodes()`。
- **FR-016** 新增 `application/channel/ChannelRouter` 端口：`String route(RouteContext context)` → 返回**已注册**的 `channelCode`；无可用渠道抛 `NoAvailableChannelException`。
- **FR-017** 新增 `application/channel/RouteContext` record：`(long amountMinor, String currencyCode, String requestedChannelCode)`。`amountMinor` / `currencyCode` 一期不参与决策，仅为将来加条件规则预留签名（**并写入注释说明「当前未使用」**，避免被误认为已生效）。
- **FR-018** 新增 `infra/channel/SpringChannelRegistry`：**构造注入 `List<PaymentChannel>`** 构建不可变 Map，**启动期校验 code 非空且唯一**，重复 → Bean 创建失败并给出重复的 code（对齐 ADR-0049）。
- **FR-019** 新增 `infra/channel/ConfiguredChannelRouter`：按 §4.4 配置与 FR-020 顺序选路。

### 4.4 选路算法（确定性）

- **FR-020** `ConfiguredChannelRouter.route()` 决策顺序：
  1. `routing.enabled=false` → **回落旧行为**：`requestedChannelCode` 必填，为空则 `400 INVALID_ARGUMENT`（灰度开关，保证可一键退回今天的行为）；
  2. `requestedChannelCode` 非空 → 校验**已注册**（不校验 `enabled`）→ 原样返回（Router 对显式意图零干预，US3）；
  3. 否则取配置中 `enabled=true` 且 `priority` 最小的渠道；同优先级按 `channelCode` 字典序（保证确定性）；
  4. 候选集为空 → 抛 `BizException(ErrorCodes.NO_AVAILABLE_CHANNEL)` → `409 NO_AVAILABLE_CHANNEL`。
- **FR-021** **确定性约束（INV-3）**：相同 `RouteContext` MUST 返回相同结果。实现中**禁止随机数、时间、进程级计数器**参与决策；该约束需以单测固化（连续 100 次断言一致）。
- **FR-022** Router MUST NOT 在渠道调用失败（`TRANSPORT_ERROR` / `TIMEOUT` / `businessFailure`）后改选其他渠道。失败语义完全沿用现状（进 UNKNOWN / FAILED / 内联重试），路由不参与。
- **FR-023** 选路 MUST 发生在**建单之前**：以最终 `channelCode` 参与幂等键构造与支付单落库，**不得先落库再改写 `channel_code`**（避免「建单用 A、请求发往 B」的不一致窗口）。
- **FR-024（反向路径）** 退款 / 重试 / 主动查询 MUST 经 `ChannelRegistry.resolve(<attempt 记录的 channel_code>)` 取渠道，**禁止调用 Router**（INV-6）。
  > 影响既有 3 处单例注入：`PaymentRefundService` / `ChannelQueryService` / `PaymentRetryService` 由「持有一个 `private final PaymentChannel` 单例」改为「经注册表按 attempt 记录解析」。

### 4.5 调用侧契约

- **FR-025** `payment-service` 的 `CreatePaymentRequest.channelCode`：必填 → **可选**（去 `@NotBlank`）。
- **FR-026** `order-service` 的 `CreateOrderPaymentRequest.channelCode`：去 `@NotBlank`，变可选；`OrderApplicationService.createPaymentForOrder` 允许 null 并透传。
- **FR-027** 响应 `CreatePaymentResponse.channelCode` 语义收口为「**路由后最终渠道**」（而非调用方原始输入），未新增字段。
- **FR-028** 幂等键口径**结构不变**，明确取值来源：`idempotencyKey = "payment:" + orderNo + ":" + <路由后 channelCode> + ":" + attemptSeq`。
  > ⚠️ 必须用**解析后**的 code。若沿用调用方原始（可能为 null）值，会出现 `payment:OR1:null:1` 这类脏键。
- **FR-029** 显式传未注册 `channelCode` → 保持 `400 INVALID_ARGUMENT`，错误信息**列出已注册渠道清单**。
- **FR-030** 收银台链接（`PaymentController` 中缺省 `MOCK` 的行为）重新定义：缺省改为**不传渠道**、交由 Router 决策（对齐 US2）。

### 4.6 配置

- **FR-031** 新增配置挂点（本地 `application.yml`，一期不接 Nacos 覆盖）：

  ```yaml
  payment:
    routing:
      enabled: true          # false = 回落旧行为（channelCode 必填）
      channels:
        ALIPAY: { enabled: true,  priority: 10 }
        WECHAT: { enabled: true,  priority: 20 }
        DOUYIN: { enabled: false, priority: 30 }
        MOCK:   { enabled: true,  priority: 90 }
    channel:
      availability:          # 桩：静态可用性，不做真实探测
        ALIPAY: { status: UP }
        WECHAT: { status: UP }
        DOUYIN: { status: UP }
  ```

- **FR-032** 配置非法 MUST **启动失败**并给出合法取值清单，覆盖：`priority` 缺失或非数字、出现未注册的渠道码、全部渠道 `enabled=false`、`availability.status` 非法值。
- **FR-033** `routing.enabled=false` 时 Router / Registry **不参与**建单路径，行为与今天逐字节一致（可由既有测试原样通过证明）。
- **FR-034** `availability.<CODE>.status = UP | DEGRADED | DOWN`（默认 UP）：
  - **DOWN 排除出候选集**；DEGRADED 保留候选但排序降级；
  - 显式指定 DOWN 渠道 → `409 CHANNEL_UNAVAILABLE`（**明确拒绝，不偷偷改选**——不篡改调用方意图）；
  - 未注册 code → `400 INVALID_ARGUMENT`（与 FR-029 一致）；
  - **不得读取 Resilience4j CircuitBreaker 状态**（S11：熔断是调用后出站保护，可用性是调用前路由输入，两者不互喂）。
- **FR-035** 一期**不做真实可用性探测**：`availability` 是静态配置 + 演示期可覆盖的桩。运行时翻转端点**只在 `demo` profile 注册**（生产 profile 下不存在，而非「存根恒返回 true」）。

### 4.7 兼容与零回归

- **FR-036** `PaymentApplicationService` **保留现有单通道构造重载**，内部包装为「单通道注册表 + 恒等路由」（`route()` 恒返回该通道 code），保证既有测试零改动（S10）。
- **FR-037** 既有 `MockChannelAdapter` 的场景语义（ADR-0049）与 `payment.channel.mock-scenario` 配置语义不变。
- **FR-038** 收银台「换用支付宝 / 抖音支付」按钮行为不变——仍**显式**传 `channelCode`（US3 路径）。
- **FR-039** `deployment/demo/traffic-gen.sh` 新增「不指定渠道」模式开关，**默认关闭**；默认路径行为不变，避免改变既有脚本断言。
- **FR-040** `payment.channel.http-timeout-ms` / `refund-async*` 等既有配置项语义不变。

### 4.8 可观测

- **FR-041** 新增计数器 `payment_routing_total`，标签 `result ∈ {explicit, routed, no_available_channel, unavailable_explicit}`（渠道码基数极小，可安全作为 `routed` 标签）。
- **FR-042** 每次路由落一条 INFO 日志：`requestedCode` / `routedCode` / 决策依据（priority 或 explicit）；经 MDC 携带 `traceId`（复用 spec 021 的 ACCESS 日志体系，不新建日志通道）。
- **FR-043** `no_available_channel` 额外落一条 WARN（配置事故，需要被看见）。
- **FR-044** 新增只读端点 `GET /internal/channels`：返回各渠道 `code / status / priority / enabled`（演示页与排障两用）。
- **FR-045** 新增 dry-run 端点 `GET /internal/channels/route-preview`：返回「此刻不指定渠道会选谁 + 候选排序 + 排除理由」，**不产生任何落库**。

## 5. 演示规划（本轮只写进文档，不实现）

**复用既有能力**：`mock-channel-web`(8091) 已有 `/proxy/{service}/**` 泛代理、`/demo/trace?orderId=` 全链路落库查询、`/demo` 演示控制台、`portal.html` 门户、`/mock-channel/callback` 回调代理。**无需新建后端代理。**

### 5.1 新增件

| 件 | 位置 | 说明 |
|---|---|---|
| 演示页 | `mock-channel-web/.../static/routing.html` | 与 `audit.html` 并列；门户 `portal.html` 加入口 |
| 演示脚本 | `deployment/demo/scenario-routing.sh` | 沿用既有 `scenario-*.sh` 风格：确定性断言、无 sleep、无概率；可选纳入 `run-all.sh` |
| 演示开关 | `POST /internal/channels/{code}/status` | **仅 `demo` profile 注册**；覆盖配置的演示开关，重启回到配置值，避免演示状态泄漏成持久状态 |

### 5.2 演示动线

`portal.html` → `routing.html` → 经 `/proxy/order/.../payments` 建支付 → payment 选路并落 `payment_attempt` → `/demo/trace?orderId=` 看 **`channel_code` 列**与 attempt 记录。

### 5.3 六个可演示场景（观测口径＝读列，不解析字符串）

> **列位置更正（2026-09-16 实现期核对）**：`payments` 表**没有** `channel_code` 列——
> 渠道身份只记在 `payment_attempts.channel_code` 上（一笔支付可有多次尝试，渠道归属是
> 「尝试」的属性而非「支付单」的属性；这也正是本 Spec 把 `payment_attempts` 的写入口
> 提升为渠道层端口的理由，INV-5 / FR-002）。下面 S1/S2 原文误写作「两表 `channel_code`」，
> 现统一更正为**只读 `payment_attempts.channel_code`**，与 `acceptance.md` 的断言口径一致。

| # | 动作 | 输入 | 可观测结果 | 验证 |
|---|---|---|---|---|
| S1 | 只表达支付意图 | 不传 `channelCode` | `payment_attempts.channel_code = ALIPAY`（`attempt_type=PAYMENT`） | US2 |
| S2 | 显式指定优先 | `channelCode=WECHAT` | `payment_attempts.channel_code = WECHAT`，Router 不干预 | US3 |
| S3 | 自动避开停用渠道 | ALIPAY=DOWN，不传渠道 | `payment_attempts.channel_code = WECHAT` | FR-034 |
| S4 | 明确拒绝不偷改 | ALIPAY=DOWN，显式 ALIPAY | `409 CHANNEL_UNAVAILABLE`，**不落 payment_attempt** | FR-034 |
| S5 | 两渠道是独立实现实例 | 分别显式 ALIPAY / WECHAT | 两笔各自落在各自渠道（同金额下行为一致，因 mock 人格仍属同一语义；见 L5） | FR-010 |
| S6 | **退款回原渠道** | 对 S2 的 WECHAT 支付发起退款 | 退款 attempt（`attempt_type=REFUND`）的 `channel_code == WECHAT`，不因 ALIPAY 优先级更高而改道 | INV-6 / FR-005 |

## 6. 验收标准（SC）

- **SC-001** `mvn -o clean verify -fae` 全绿，`architecture-tests` 边界门禁通过（含 FR-008 两条新断言）。
- **SC-002（两层结构，ADR-0072）** `infra/channel/` 存在抽象基类 + 3 个渠道实现；`PaymentChannel.channelCode()` 在端口上；payment 层无 `PaymentAttemptRepository` 直接依赖；`payment_attempts` 写入口经渠道层端口。
- **SC-003（退款渠道归属，INV-6）** 对 `channel_code=WECHAT` 的已成功支付发起退款 → 退款 attempt 行 `channel_code == 'WECHAT'`；`RefundRequest.channelCode` 被实际读取（不再有硬编码 `"mock"` 与 `"mock-refund-ref-"`）。
- **SC-004（US2 主路径）** 不传 `channelCode` 发起支付 → `payment_attempts.channel_code` == 配置 `priority` 最小的 `enabled` 渠道；全链路（订单 PAID、交易 SUCCEEDED、库存扣减、履约与权益、账本平衡）不变。
- **SC-005（US3 显式优先）** 显式传 `channelCode=DOUYIN`（即使 `enabled=false`）→ 支付单落在 DOUYIN，`result=explicit`。
- **SC-006（无可用渠道）** 全部 `enabled=false` 且不指定渠道 → `409 NO_AVAILABLE_CHANNEL`，**`payments` 与 `payment_attempts` 无新增行**（不允许部分写入）。
- **SC-007（确定性 INV-3）** 单测：同一 `RouteContext` 连续 100 次 `route()` 结果完全一致。
- **SC-008（启动强校验 FR-032）** 单测：重复 `channelCode` 装配使 Bean 创建失败；非法 `priority` / 未知渠道码 / 全禁用 / 非法 `status` 均启动失败并含合法取值清单。
- **SC-009（分层门禁 INV-4/INV-5）** ArchUnit 断言通过。
- **SC-010（反向不重新路由 INV-6）** 退款 / 重试 / 查询路径的渠道来源断言为 attempt 记录值；构造「Router 会选另一渠道」的配置，退款仍回原渠道。
- **SC-011（可观测 US4）** 三类路径各触发一次，`payment_routing_total` 对应取值各计一次；路由 INFO 日志含 `routedCode` 与 `traceId`。
- **SC-012（零回归）** 既有支付 / 退款 / 可靠性 / 集成 / E2E 测试**零改动**通过（证明 FR-036 的重载生效、FR-011 保持构造签名）。
- **SC-013（文档漂移消除）** `payment-service.md` §2.1「必须已注册到渠道 Registry/Router」与 §3 渠道抽象、§4 API 契约、`technical-solution.md` §3.1/§4.3（两层描述与基数）、`docs/operations/runbook.md` 配置项，全部与实现一致。

## 7. 已知限制（诚实标注）

| # | 限制 | 影响 | 记录位置 |
|---|---|---|---|
| L1 | 无渠道健康探测。`availability` 是**静态配置 + 演示桩**，渠道故障时需**人工关渠**，平台不会自动摘除 | 渠道故障期间若仍被自动选中会持续失败（表现为 FAILED / UNKNOWN，而非路由层规避） | FR-034 / FR-035 |
| L2 | 无 per-channel 成功率 / 成本 / 延迟统计 | 智能路由无数据源，二期才能立项 | §8 |
| L3 | 显式指定 `enabled=false` 的渠道**不拦截**（有意口径） | 配置关渠挡不住显式调用 | FR-020② |
| L4 | 只有 `priority` 单选，无权重分流 | 无法做灰度 / 流量配比 | §8 |
| L5 | 三个新 Adapter 仍是同一 mock 语义 | `ALIPAY/WECHAT/DOUYIN` 在**协议层**仍等价（结构与身份变真）；接真实渠道 SDK 是独立议题 | §8 |
| L6 | 存量 `attempt_type=REFUND` 行的 `channel_code='mock'` **不回填** | 历史退款尝试的渠道字段不可用于渠道维度统计 | FR-007 |
| L7 | `attemptSeq` 计算与插入非原子（spec 015 既有问题） | 与路由无关，本 Spec 不改变也不修复 | spec 015 §7 L1 |
| L8 | `ChannelRouter` 即便返回结果，`availability` 的 DOWN 判定与路由决策之间无锁 | 并发翻转状态时可能选到刚被置 DOWN 的渠道——属演示桩的固有精度，投产前需换真实探测 | FR-035 |

## 8. 不做（Out of Scope）

- ❌ **不拆微服务、不拆数据源、不拆事务边界**——两层是**职责分层**（INV-5 边界澄清）。
- ❌ **不做自动降级 failover**（渠道失败自动改选）——**与 INV-2 互斥**，属人类决策边界。
- ❌ 不做智能路由（按成功率 / 成本 / 延迟打分）——无数据源（L2）。
- ❌ 不做多商户号 / 通道账号路由——项目无商户号模型。
- ❌ 不做渠道健康探测与自动熔断降级——不给「渠道」概念叠加可靠性语义，是独立议题。
- ❌ 不改 `channelCode` 为枚举（spec 015 FR-022 保持）。
- ❌ 不接入真实渠道 SDK；不实现真实渠道协议。
- ❌ 不做权重分流 / 灰度配比（L4）。
- ❌ 不改状态机语义、金额口径、记账链路、对账抽取口径。
- ❌ 不改 INV-1 的扣库存归属。
- ❌ 不引入 MQ、不引入新中间件、不引入新第三方依赖（Constitution）。
- ❌ **不回填存量退款尝试的渠道字段**（FR-007）。

## 9. 已拍板口径（2026-09-16）

| # | 口径 | 裁决 | 来源 |
|---|---|---|---|
| D1 | 路由决策权 | `channelCode` **可选，不传则路由**；显式优先 | 负责人 2026-09-16 |
| D2 | 一期路由语义 | **只做渠道路由骨架**（选路）；不做自动 failover / 多商户号 / 成功率打分 | 负责人 2026-09-16 |
| D3 | 三渠道拆分 | **三独立实现 + 共享抽象基类**，各有独立 mock 人格（「代码一样也行，但必须分开」） | 负责人 2026-09-16 |
| D4 | 注册表 | **做**（推翻 spec 015 §8 第 1 条「不做注册表」） | 负责人 2026-09-16 |
| D5 | per-channel 可用性 | **做，桩实现**；不做真实探测，演示可 mock | 负责人 2026-09-16 |
| D6 | 与 Resilience4j 关系 | **不复用、不互喂**；熔断状态不参与选路 | 负责人 2026-09-16 |
| D7 | 两层结构 | **确立 payment 层 / channelAttempt 层**，各拥自己的聚合与表写入口；渠道层负责渠道实现与抽象 | 负责人 2026-09-16 |
| D8 | 排期 | **本轮只出设计不动代码**；ADR 标 Proposed 固化口径，实现排下一轮 | 负责人 2026-09-16 |

**待负责人确认**：两份 ADR 的 Proposed 状态是否升为 Accepted（升为 Accepted 即可进入 `/speckit-plan`）。

## 10. 文档与决策影响

| 类型 | 对象 | 动作 |
|---|---|---|
| 新增 ADR | `docs/adr/0072-two-layer-channel-architecture.md`（ADR-0072，🟡 Proposed） | 本轮已创建 |
| 新增 ADR | `docs/adr/0073-channel-routing.md`（ADR-0073，🟡 Proposed） | 本轮已创建。⚠️ **编号避让**：ADR-0071 已被同日立项的 spec 027「用户支付限额」（`0071-user-payment-limit.md`，worktree `docs/spec-027-user-payment-limit`，未 merge）占用，故渠道路由 ADR 顺延为 **0072**、文件号 **0033**。水位 → **ADR-0073** |
| 新增 Spec | `docs/specs/stage-04-new-directions/028-channel-routing/`（本文档） | 本轮已定稿 |
| **Supersede** | spec 015 §8 第 1 条「不做 `Map<ChannelCode, PaymentChannel>` 注册表」 | 实现期在 015 spec 加注「已于 2026-09-16 由 ADR-0073 取代」 |
| **消除漂移** | `payment-service.md` §2.1「必须已注册到渠道 Registry/Router」（悬空描述） | 实现期同步（SC-013） |
| **消除漂移** | `technical-solution.md` §4.3「创建 Payment + PaymentAttempt（本地事务）」与 §3.1/§3.6 的两层描述 | 本轮已同步为「两层职责 + 共享本地事务」 |
| **消除漂移** | `payment-service.md` §3 渠道抽象 / §4 API 契约；`docs/operations/runbook.md` 配置项 | 实现期同步 |
| 保持不变 | spec 015 的 INV-1 / INV-2 / 幂等键结构 / 不做枚举；ADR-0012 双响应码、ADR-0049 场景配置化、ADR-0063 业务单号 | 不改动 |
