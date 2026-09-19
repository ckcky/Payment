# Plan: 028 支付两层结构 + 渠道路由

对应 [spec.md](spec.md)。**本 Plan 描述实现期（`feature/028-channel-routing`）的落地方案**；本轮只产出文档，不落代码。

## 0. 范围与执行顺序

七个批次，**A → B → C → D → E → F → G 严格顺序**（后批次依赖前批次的类型）：

| 批次 | 内容 | 对应 FR | 可独立编译？ |
|---|---|---|---|
| A | 端口补身份 + 实现族拆分 | FR-001、FR-009~FR-014 | ✅（此时旧链路仍可用） |
| B | 两层收口（表写入口归属） | FR-002~FR-008 | ✅ |
| C | 注册表 + 路由器 + 配置 | FR-015~FR-024、FR-031~FR-035 | ✅（路由此时尚未接入主链路） |
| D | 调用侧契约 + 兼容垫片 | FR-025~FR-030、FR-036~FR-040 | ❌ 依赖 C |
| E | 可观测与只读端点 | FR-041~FR-045 | ❌ 依赖 C |
| F | ArchUnit 门禁 + 文档同步 | FR-008、SC-013 | ❌ 依赖 A/B |
| G | 演示件（页面 / 脚本 / 开关） | §5 | ❌ 依赖 C/D |

**批次 A/B 必须同批交付**：A 拆完三个 Adapter 后，若 B 未做，`payment_attempts` 仍由 payment 层写、退款渠道仍硬编码——中间态比现状更别扭（spec §1.4）。

---

## 批次 A：端口补身份 + 实现族拆分

### A1 `PaymentChannel` 端口补 `channelCode()`

`payment-service/.../application/channel/PaymentChannel.java`：新增 `String channelCode();`（返回**大写**渠道码，非空）。

**连带改动（已核实）**：4 个测试内部类需补实现（FR-001）——
`PaymentDeferredChannelTest` / `reliability/ChannelQueryTest`（`StubChannel`）/ `reliability/PaymentRetryTest` / `reliability/ReliabilityMetricsTest`。

### A2 抽出 `AbstractMockChannelAdapter`

新增 `infra/channel/AbstractMockChannelAdapter.java`（**`abstract`，不加 `@Component`**），逐字承载 4 件横切行为（FR-009）：

| # | 行为 | 现状出处（迁移时逐字保留） |
|---|---|---|
| 1 | 金额尾数确定性故障注入（尾数 11→超时 / 12→无结论 / 15→业务拒绝） | `MockChannelAdapter.charge()` 内 |
| 2 | 退款「受理 + 异步推送」（`RefundResultListener`，`setRefundResultListener`） | `MockChannelAdapter.refund()` + `:141` |
| 3 | **每实例独立 `runId`**（`UUID` 派生 12 位）+ `refGen` | `:71-72` |
| 4 | `mock-scenario` **严格枚举解析**（坏值 FAIL FAST，不做容错） | `:75-120` 各构造器 |

**约束**：
- 三渠道行为**不得分叉**；金额尾数注入**不允许 per-channel 差异化**（FR-013，spec 022 的 E2E 依赖）。
- `runId` MUST 每实例独立（FR-012）：不得由共享配置注入同一值——否则三 Adapter `refGen` 均从 1 起会撞 `uk_attempts_channel_reference`。

### A3 三个渠道实现

新增（**均 `@Component`**，只声明「身份 + 差异」，FR-010）：

| 类 | `channelCode()` | 差异点 |
|---|---|---|
| `infra/channel/AlipayChannelAdapter.java` | `ALIPAY` | 可独立配置 scenario / 退款异步延迟 |
| `infra/channel/WechatChannelAdapter.java` | `WECHAT` | 同上 |
| `infra/channel/DouyinChannelAdapter.java` | `DOUYIN` | 同上 |

**新增配置挂点**（在 FR-031 的 `payment.channel` 下扩展，示例口径）：

```yaml
payment:
  channel:
    mock-scenario: SUCCESS        # 全局默认值（FR-014：语义不变）
    adapters:
      ALIPAY: { scenario: SUCCESS }
      WECHAT: { scenario: SUCCESS }
      DOUYIN: { scenario: SUCCESS }
```

子类 Spring 构造器读 `@Value("${payment.channel.adapters.ALIPAY.scenario:${payment.channel.mock-scenario:SUCCESS}}")`——**未配 per-channel 时回落全局默认**，`MockChannelAdapterScenarioTest` 断言的错误消息文本继续有效（FR-014/FR-037）。

### A4 `MockChannelAdapter` 保留身份与签名

`MockChannelAdapter` **保留原名与全部既有构造签名**，改为继承基类并声明 `channelCode() = "MOCK"`（FR-011）：

- 必留签名（已核实 6 个）：`()` / `(Scenario)` / `(Scenario, long)` / `(String, long)` / `(Scenario, long, ...)` / Spring 主构造 `(@Value scenario, ...)`。
- 目的：10 个 `new MockChannelAdapter(...)` 的测试文件与未指定渠道的旧脚本**零改动**（FR-036/SC-012）。

### A5 顺手修正

- 删除 `MockChannelAdapter:160` 附近那条**理由错误的注释**（「便于对账/演示按渠道区分」）——渠道归属在 `channel_code` **列**，引用前缀只承载排障可读性。
- 退款引用**不改**为带渠道前缀（该口径已撤回），但**移除硬编码 `"mock-refund-ref-"`** 见 B3。

---

## 批次 B：两层收口（表写入口归属）

> 边界澄清（INV-5）：这是**职责分层，不是拆事务**。`payments` 与 `payment_attempts` 仍共享同一本地事务。

### B1 新增渠道层写入口端口

新增 `application/channel/ChannelAttemptRecorder.java`（FR-002）：

```java
public interface ChannelAttemptRecorder {
    PaymentAttempt openPaymentAttempt(...);      // 创建一次支付渠道交互
    PaymentAttempt openRefundAttempt(...);       // 创建一次退款渠道交互（渠道取自支付记录，见 B3）
    void converge(PaymentAttempt attempt, ChannelResult result);  // 收敛终态
    PaymentAttempt markUnknown(PaymentAttempt attempt);
}
```

实现放 `infra/persistence/ChannelAttemptRecorderImpl.java`（与 `PaymentAttemptRepository` 同层，`infra → infra` 依赖，不违反 INV-4），持 `PaymentAttemptRepository`。

### B2 `PaymentPersistence` 移除 attempt 依赖

`application/PaymentPersistence.java`（FR-003）：

- **移除** `PaymentAttemptRepository` 字段与 `import`；
- 改持 `ChannelAttemptRecorder`，`insertPending()` / `applyAndPersist()` 中的 attempt 读写**经端口委托**；
- 事务注解与边界**不变**（同一 `@Transactional`）。

### B3 退款尝试渠道归属（消除 S5/S6）

`application/PaymentRefundService.java:89`：`PaymentAttempt.refundAttempt(..., "mock", ...)` 的硬编码 `"mock"` 改为**解析值**（FR-005）：

```
取数口径：该 payment_no 下 attempt_type=PAYMENT 且 status=SUCCEEDED 的那一行的 channel_code
```

若查不到生效支付渠道 → `BizException(ErrorCodes.INTERNAL_ERROR)`（**不静默回落 MOCK**，属数据异常）。

`infra/channel/AbstractMockChannelAdapter.refund()`：改用 `channelCode()` 生成引用，**移除**硬编码 `"mock-refund-ref-"`（FR-006）；`RefundRequest.channelCode` 从死字段变为实读。

> 存量 `attempt_type=REFUND` 且 `channel_code='mock'` 的历史行**不回填**（FR-007，改写历史事实会污染审计链）。

### B4 `PaymentResultApplier` 拆分

`application/PaymentResultApplier.java`（FR-004）：当前 `apply(Payment, PaymentAttempt, ChannelResult)` 一个方法同时推进两个聚合（S2），拆为两侧：

- **渠道侧**：`attempt` 收敛（`accept` / `succeed` / `fail` / `markUnknown`）→ 由渠道层（B1 的自实现）承担；
- **支付侧**：`payment` 状态迁移 → 由 payment 层承担；
- 两者由调用方（`PaymentApplicationService`）在**同一事务内**协调。

**风险点**：拆分后两处状态迁移的**顺序**必须与现状一致（先 attempt 后 payment），否则乐观锁版本号与幂等重放断言会变。以既有测试（`PaymentApplicationServiceTest` 等）作为行为基线，**零改动通过**为准（SC-012）。

---

## 批次 C：注册表 + 路由器 + 配置

### C1 端口（`application/channel/`）

| 新增 | 签名（FR） |
|---|---|
| `ChannelRegistry` | `PaymentChannel resolve(String channelCode)`（未注册抛 `BizException(INVALID_ARGUMENT)`）、`Set<String> registeredCodes()`（FR-015） |
| `ChannelRouter` | `String route(RouteContext context)`；无可用渠道抛 `BizException(ErrorCodes.NO_AVAILABLE_CHANNEL)`（FR-016） |
| `RouteContext` | `record RouteContext(long amountMinor, String currencyCode, String requestedChannelCode)`（FR-017） |

**`RouteContext` 注释要求（FR-017）**：`amountMinor` / `currencyCode` 一期**不参与决策**，必须在 record javadoc 明写「当前未使用，为条件规则预留」，避免被误认为已生效。

### C2 实现（`infra/channel/`）

- `SpringChannelRegistry`（FR-018）：**构造注入 `List<PaymentChannel>`** 建不可变 `Map`，**启动期校验 code 非空且唯一**，重复 → Bean 创建失败并打印重复的 code。

  > 为什么不用手写 `Map`：手写 Map 的失败模式是「加了渠道忘了注册」→ 运行期 500；`List` 注入是编译期就有、启动期就炸。

- `ConfiguredChannelRouter`（FR-019）：实现 C3 的决策顺序。
- `infra/config/RoutingProperties.java`（或等价 `@ConfigurationProperties`）：绑定 FR-031 的 `payment.routing` 与 `payment.channel.availability`，**构造期校验**（FR-032）。

### C3 选路算法（确定性）

`ConfiguredChannelRouter.route()` 决策顺序（FR-020）：

1. `routing.enabled=false` → **回落旧行为**：`requestedChannelCode` 必填，为空 → `400 INVALID_ARGUMENT`；
2. `requestedChannelCode` 非空 → 校验**已注册**（**不校验 `enabled`**）→ 原样返回（US3 零干预）；
3. 否则取 `enabled=true` 且 `priority` 最小者；**同优先级按 `channelCode` 字典序**（保证确定性）；
4. 候选为空 → `BizException(ErrorCodes.NO_AVAILABLE_CHANNEL)`。

叠加 `availability`（FR-034）：`DOWN` 排除出候选集；`DEGRADED` 保留候选但排序降级；显式指定 `DOWN` → `409 CHANNEL_UNAVAILABLE`（**明确拒绝，不偷偷改选**）。

**硬约束**：
- **INV-3（FR-021）**：**禁止随机数、时间、进程级计数器**参与决策；单测连续 100 次断言一致（SC-007）。
- **FR-022**：渠道调用失败后**不得**改选其他渠道（与 INV-2 互斥）。
- **FR-023**：选路 MUST 发生在**建单之前**——以最终 code 参与幂等键与落库，**不得先落库再改写 `channel_code`**。
- **FR-034 尾注**：**不得读取 Resilience4j `CircuitBreaker` 状态**（熔断是调用后出站保护，可用性是调用前路由输入）。
- **FR-035**：一期不做真实探测，`availability` 是静态配置 + 演示桩。

### C4 错误码（`common/common-core`）

`ErrorCodes.java` 新增（FR-016 尾注）：

```java
/** 无可用渠道：全部渠道被禁用/降级，且调用方未指定渠道（Feature 028，HTTP 409）。 */
public static final String NO_AVAILABLE_CHANNEL = "NO_AVAILABLE_CHANNEL";
/** 显式指定的渠道当前不可用：明确拒绝而非静默改选（Feature 028，HTTP 409）。 */
public static final String CHANNEL_UNAVAILABLE = "CHANNEL_UNAVAILABLE";
```

**不新建异常类**——本仓约定错误经 `BizException` + `ErrorCodes` 表达，`payment-service` 现有 0 个自定义异常类。

---

## 批次 D：调用侧契约 + 兼容垫片

### D1 契约放宽

| 文件 | 改动 | FR |
|---|---|---|
| `payment-service/.../api/` 请求 DTO（`CreatePaymentRequest`） | `channelCode` 去 `@NotBlank`，变可选 | FR-025 |
| `order-service/.../` 请求 DTO（`CreateOrderPaymentRequest`） | 去 `@NotBlank`；`OrderApplicationService.createPaymentForOrder` 允许 null 并透传 | FR-026 |
| `CreatePaymentResponse` | `channelCode` 语义收口为「**路由后最终渠道**」，**不新增字段** | FR-027 |
| `PaymentController` 收银台缺省 | 缺省从 `MOCK` 改为**不传渠道**（交 Router 决策） | FR-030 |

### D2 幂等键取值来源（关键）

`PaymentPersistence.java:47`（FR-028）：结构**不变**，但取值来源明确为**路由后**的 code：

```
idempotencyKey = "payment:" + orderNo + ":" + <路由后 channelCode> + ":" + attemptSeq
```

> ⚠️ 必须用解析后的 code。若沿用调用方原始（可能为 null）值，会出现 `payment:OR1:null:1` 这类脏键，且与 D1 的契约放宽叠加。

### D3 反向路径（INV-6）

三处既有单例注入（**已核实，共 3 处**）改为**经 `ChannelRegistry` 按 attempt 记录解析**，**禁止调用 Router**（FR-024）：

| 文件:行 | 现状 |
|---|---|
| `application/PaymentRefundService.java:37` | `private final PaymentChannel channel` |
| `application/reliability/ChannelQueryService.java:33` | 同上 |
| `application/reliability/PaymentRetryService.java:37` | 同上 |

### D4 兼容垫片

- **FR-036**：`PaymentApplicationService` **保留现有单通道构造重载**，内部包装为「单通道注册表 + 恒等路由」（`route()` 恒返回该通道 code）——保证 10 个既有测试零改动。
- **FR-039**：`deployment/demo/traffic-gen.sh` 新增「不指定渠道」开关，**默认关闭**，默认路径行为不变。
- **FR-037/038/040**：场景语义、收银台「换渠道」按钮（仍显式传）、`http-timeout-ms` / `refund-async*` 配置语义均**不变**。

---

## 批次 E：可观测与只读端点

- **FR-041** `BusinessMetrics` 新增计数器 `payment_routing_total`，标签 `result ∈ {explicit, routed, no_available_channel, unavailable_explicit}`（渠道码基数极小，可作 `routed` 标签）。
  > 指标类复用 `common-core` 的 `BusinessMetrics`（本仓 payment-service 无自有 Metrics 类）。
- **FR-042** 每次路由落 INFO：`requestedCode` / `routedCode` / 决策依据；经 MDC 携带 `traceId`（复用 spec 021 的 ACCESS 日志体系，**不新建日志通道**）。
- **FR-043** `no_available_channel` 额外落一条 WARN。
- **FR-044** 新增 `GET /internal/channels` → `code / status / priority / enabled`。
- **FR-045** 新增 `GET /internal/channels/route-preview` → 「不指定会选谁 + 候选排序 + 排除理由」，**零落库**。

端点落 `payment-service/.../api/ChannelAdminController.java`（与既有 `PaymentController` / `ReconciliationFactsController` 并列）。

---

## 批次 F：ArchUnit 门禁 + 文档同步

### F1 门禁（FR-008）

`deployment/architecture-tests/.../ServiceBoundaryTest.java` 新增两条 `noClasses()` 规则（沿用该类的导入方式与 `because(...)` 文案风格）：

| 规则 | 断言 | 对应 |
|---|---|---|
| 端口不碰实现 | `com.payment.payment.application.channel..` MUST NOT 依赖 `com.payment.payment.infra.channel..` | INV-4 |
| 写入口归属 | `com.payment.payment.application..` MUST NOT 依赖 `PaymentAttemptRepository` | INV-5 |

> **范围必须精确到 `application.channel..`**：`application` 包下的 `PaymentPersistence` 本就依赖 `infra.persistence`，不能对 `application` 整体加「不得依赖 infra」规则。

> **防空转**：该类已有 `everyServiceMustActuallyBeImported()` 兜底，新规则自动受其保护。

### F2 文档同步（SC-013）

| 文件 | 动作 |
|---|---|
| `docs/architecture/systems/payment-service.md` | §2.1「必须已注册到渠道 Registry/Router」由**悬空描述**变为事实；§3 渠道抽象补实现族；§3.2 请求表 `channelCode` 必填→可选；§4.3 退款渠道口径 |
| `docs/architecture/technical-solution.md` | §3.1/§3.6 两层描述、§4.3 时序图与基数（ADR-0054 的 `1:1` 已过时） |
| `docs/operations/runbook.md` | 新增 `payment.routing.*` 配置项与排障入口（`/internal/channels`） |
| `docs/specs/stage-03-evolution-consolidation/015-multi-channel-payment/spec.md` | §8 第 1 条加注「已于 2026-09-16 由 ADR-0073 取代」（**本轮已加注**） |
| `docs/adr/0072-two-layer-channel-architecture.md`、`0073-channel-routing.md` | Proposed → Accepted（实现完成时） |

---

## 批次 G：演示件

| 件 | 位置 | 说明 |
|---|---|---|
| 演示页 | `deployment/mock-channel-web/.../static/routing.html` | 与 `audit.html` 并列；`portal.html` 加入口 |
| 演示脚本 | `deployment/demo/scenario-routing.sh` | 沿用既有 `scenario-*.sh` 风格：**确定性断言、无 sleep、无概率**；可选纳入 `run-all.sh` |
| 演示开关 | `POST /internal/channels/{code}/status`（**仅 `demo` profile 注册**） | 覆盖配置的演示开关，**重启回到配置值**，避免演示状态泄漏成持久状态 |

**复用既有能力，不新建后端代理**：`mock-channel-web`(8091) 已有 `/proxy/{service}/**` 泛代理、`/demo/trace?orderId=` 全链路查询、`portal.html` 门户。

**演示动线**：`portal.html` → `routing.html` → 经 `/proxy/order/.../payments` 建支付 → payment 选路并落 `payment_attempt` → `/demo/trace?orderId=` 看 **`channel_code` 列**。

**六个场景**（S1 只表达意图 / S2 显式优先 / S3 自动避开停用 / S4 明确拒绝不偷改 / S5 三渠道各有 mock 人格 / S6 **退款回原渠道**）见 spec §5.3；观测口径**一律读 `channel_code` 列，不解析引用字符串**。

---

## 回归与验收

- **主回归**：`mvn -o clean verify -fae` 全绿（含 `architecture-tests` 的 F1 两条新规则）。
- **零回归证据**：既有支付 / 退款 / 可靠性 / 集成 / E2E 测试**零改动**通过（证明 FR-036 重载与 FR-011 构造签名生效）。
- **demo 实跑**：`start-all.sh` 起栈 → `run-all.sh` 既有断言全绿 → `scenario-routing.sh` 六场景全绿 → `stop-all.sh` 优雅停机。

## 实现期待确认项（不阻塞本轮文档）

1. **per-channel scenario 配置键名**：本 Plan 取 `payment.channel.adapters.<CODE>.scenario`，实现时可对齐 `routing.channels` 结构统一为 `payment.channel.<CODE>.scenario`。
2. **`ChannelAttemptRecorder` 实现落点**：本 Plan 取 `infra/persistence/`（近仓储）；若团队倾向「渠道层自治」，可移 `infra/channel/`——两种都不违反 INV-4。
3. **两份 ADR 的 Accepted 时点**：在实现合并时升格，或本轮由负责人先行核准。
