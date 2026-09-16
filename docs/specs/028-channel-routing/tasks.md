# Tasks: 026 支付两层结构 + 渠道路由

> **状态**：**已实现完成**（批次 A–G 全绿；`mvn -o clean verify -fae` 16 模块 BUILD SUCCESS）。实现于 `feature/028-channel-routing` 分支，`--no-ff` 合并 master。
> 批次顺序 **A → B → C → D → E → F → G** 不可乱序（后批次依赖前批次类型）；A/B 必须同批交付（spec §1.4）。
> 对应 [plan.md](plan.md) / [spec.md](spec.md) / [acceptance.md](acceptance.md)。

## 批次 A：端口补身份 + 实现族拆分

- [x] T1 `application/channel/PaymentChannel` 补 `String channelCode()`（FR-001）
- [x] T2 4 个测试内部类补 `channelCode()` 实现：`PaymentDeferredChannelTest` / `reliability/ChannelQueryTest` / `reliability/PaymentRetryTest` / `reliability/ReliabilityMetricsTest`（FR-001）
- [x] T3 新增 `infra/channel/AbstractMockChannelAdapter`（abstract，非 Bean），逐字承载 4 件横切行为：尾数故障注入 / 退款异步推送 / 每实例独立 `runId` / scenario 严格枚举解析（FR-009、FR-012、FR-013）
- [x] T4 新增 `AlipayChannelAdapter` / `WechatChannelAdapter` / `DouyinChannelAdapter`（`@Component`，只声明身份 + 差异）（FR-010）
- [x] T5 `MockChannelAdapter` 改继承基类、声明 `channelCode()="MOCK"`，**保留全部 6 个既有构造签名**（FR-011）
- [x] T6 新增 `payment.channel.adapters.<CODE>.scenario` 配置，未配回落全局 `payment.channel.mock-scenario`（FR-014、FR-037）
- [x] T7 移除 `MockChannelAdapter` 中理由错误的「按渠道区分」注释（渠道归属在 `channel_code` 列）（plan §A5）
- [x] T8 断言：`MockChannelAdapterScenarioTest` 与既有 10 个 `new MockChannelAdapter(...)` 测试**零改动**通过

## 批次 B：两层收口（表写入口归属）

- [x] T9 新增 `application/channel/ChannelAttemptRecorder` 端口（openPaymentAttempt / openRefundAttempt / converge / markUnknown）（FR-002）
- [x] T10 新增 `infra/persistence/ChannelAttemptRecorderImpl`（持 `PaymentAttemptRepository`）（FR-002）
- [x] T11 `PaymentPersistence` 移除 `PaymentAttemptRepository` 依赖，改经 `ChannelAttemptRecorder` 委托；**事务注解与边界不变**（FR-003、INV-5）
- [x] T12 `PaymentRefundService.java:89` 去硬编码 `"mock"`：退款 attempt 渠道取自该 `payment_no` 下 `attempt_type=PAYMENT` 且 `SUCCEEDED` 行的 `channel_code`；查不到 → `BizException(INTERNAL_ERROR)`，**不静默回落 MOCK**（FR-005）
- [x] T13 `AbstractMockChannelAdapter.refund()` 改用 `channelCode()` 生成引用，移除硬编码 `"mock-refund-ref-"`；`RefundRequest.channelCode` 变实读（FR-006）
- [x] T14 `PaymentResultApplier` 拆分：attempt 侧归渠道层、payment 侧归支付层，由调用方同事务协调；**保持「先 attempt 后 payment」顺序**（FR-004）
- [x] T15 记录：存量 `attempt_type=REFUND` 且 `channel_code='mock'` 的历史行**不回填**（FR-007）
- [x] T16 断言：退款链路（spec 019）与既有集成测试零改动通过

## 批次 C：注册表 + 路由器 + 配置

- [x] T17 新增端口 `application/channel/ChannelRegistry`（`resolve` / `registeredCodes`）（FR-015）
- [x] T18 新增端口 `application/channel/ChannelRouter` + `RouteContext` record；record javadoc 明写 `amountMinor` / `currencyCode` **当前未使用**（FR-016、FR-017）
- [x] T19 新增 `infra/channel/SpringChannelRegistry`：构造注入 `List<PaymentChannel>`，建不可变 Map，**启动期校验 code 非空且唯一**（FR-018）
- [x] T20 新增 `infra/channel/ConfiguredChannelRouter` + 配置绑定类（`payment.routing` / `payment.channel.availability`）（FR-019、FR-031）
- [x] T21 实现决策顺序 ①②③④（enabled=false 回落旧行为 → 显式校验已注册不校验 enabled → priority 最小、同级字典序 → 抛 `NO_AVAILABLE_CHANNEL`）（FR-020）
- [x] T22 单测：同一 `RouteContext` 连续 100 次结果一致（INV-3 / FR-021）
- [x] T23 叠加 `availability`：`DOWN` 排除候选 / `DEGRADED` 排序降级 / 显式指定 `DOWN` → `409 CHANNEL_UNAVAILABLE`；**不读 CircuitBreaker 状态**（FR-034）
- [x] T24 启动强校验：`priority` 缺失或非数字 / 未注册渠道码 / 全渠道 `enabled=false` / `availability.status` 非法 → **启动失败**并给出合法取值清单（FR-032）
- [x] T25 `ErrorCodes` 新增 `NO_AVAILABLE_CHANNEL` / `CHANNEL_UNAVAILABLE`（**不新建异常类**）（FR-016 尾注、plan §C4）
- [x] T26 确认 `routing.enabled=false` 时 Router/Registry 不参与建单路径（FR-033）

## 批次 D：调用侧契约 + 兼容垫片

- [x] T27 `payment-service` `CreatePaymentRequest.channelCode` 去 `@NotBlank` 变可选（FR-025）
- [x] T28 `order-service` `CreateOrderPaymentRequest.channelCode` 去 `@NotBlank`；`createPaymentForOrder` 允许 null 并透传（FR-026）
- [x] T29 `CreatePaymentResponse.channelCode` 语义收口为「路由后最终渠道」，不新增字段（FR-027）
- [x] T30 幂等键取值来源改为**路由后** code（结构不变，避免 `payment:OR1:null:1` 脏键）（FR-028）
- [x] T31 选路 MUST 在建单之前（不得先落库再改写 `channel_code`）（FR-023）
- [x] T32 三处反向路径改造为经 `ChannelRegistry` 按 attempt 记录解析、**禁止调用 Router**：`PaymentRefundService:37` / `reliability/ChannelQueryService:33` / `reliability/PaymentRetryService:37`（FR-024、INV-6）
- [x] T33 显式传未注册 code → `400 INVALID_ARGUMENT`，错误信息**列出已注册渠道清单**（FR-029）
- [x] T34 `PaymentController` 收银台缺省渠道从 `MOCK` 改为不传（交 Router）（FR-030）
- [x] T35 `PaymentApplicationService` 保留单通道构造重载，内部包装为「单通道注册表 + 恒等路由」（FR-036）
- [x] T36 确认：收银台「换渠道」按钮仍显式传 `channelCode`；`traffic-gen.sh` 新增「不指定渠道」开关且**默认关闭**（FR-038、FR-039）
- [x] T37 确认：`http-timeout-ms` / `refund-async*` 配置语义不变（FR-040）

## 批次 E：可观测与只读端点

- [x] T38 `BusinessMetrics` 新增计数器 `payment_routing_total`，标签 `result ∈ {explicit, routed, no_available_channel, unavailable_explicit}`（FR-041）
- [x] T39 每次路由落 INFO（`requestedCode` / `routedCode` / 决策依据），经 MDC 带 `traceId`，复用 spec 021 ACCESS 体系（FR-042）
- [x] T40 `no_available_channel` 额外落 WARN（FR-043）
- [x] T41 新增 `GET /internal/channels` 返回 `code / status / priority / enabled`（FR-044）
- [x] T42 新增 `GET /internal/channels/route-preview`（候选排序 + 排除理由，**零落库**）（FR-045）

## 批次 F：门禁与文档同步

- [x] T43 `ServiceBoundaryTest` 新增规则：`application.channel..` MUST NOT 依赖 `infra.channel..`（INV-4 / FR-008）
- [x] T44 `ServiceBoundaryTest` 新增规则：`application..` MUST NOT 依赖 `PaymentAttemptRepository`（INV-5 / FR-008）
- [x] T45 确认新规则未因导入范围过宽误伤（`PaymentPersistence` 依赖 `infra.persistence` 属既有合法情形）——**范围精确到 `application.channel..`**（plan §F1）
- [x] T46 `payment-service.md` 同步：§2.1 Registry/Router 变事实、§3 渠道抽象补实现族、§3.2 `channelCode` 可选、§4.3 退款渠道口径（SC-013）
- [x] T47 `technical-solution.md` 同步：§3.1/§3.6 两层描述、§4.3 时序图与基数（SC-013）
- [x] T48 `runbook.md` 补 `payment.routing.*` 配置项与 `/internal/channels` 排障入口（SC-013）
- [x] T49 两份 ADR（`0033-two-layer-channel-architecture.md` / `0034-channel-routing.md`）Proposed → Accepted（实现完成时）

## 批次 G：演示件

- [x] T50 新增 `mock-channel-web/.../static/routing.html`；`portal.html` 加入口（spec §5.1）
- [x] T51 新增 `deployment/demo/scenario-routing.sh`（确定性断言、无 sleep、无概率）；评估纳入 `run-all.sh`（spec §5.1）
- [x] T52 新增演示开关 `POST /internal/channels/{code}/status`，**仅 `demo` profile 注册**（FR-035、spec §5.1）
- [x] T53 六场景脚本化：S1 只表达意图 / S2 显式优先 / S3 自动避开停用 / S4 明确拒绝不偷改 / S5 三渠道各有 mock 人格 / S6 退款回原渠道（spec §5.3）

## 收尾

- [x] T54 `mvn -o clean verify -fae` 全绿（含 F1 两条新 ArchUnit 规则）
- [x] T55 既有支付 / 退款 / 可靠性 / 集成 / E2E 测试**零改动**通过（SC-012）
- [ ] T56 demo 实跑（**待有栈环境**：`start-all.sh` 起栈 → `run-all.sh` 全绿 → `scenario-routing.sh` 六场景全绿 → `stop-all.sh`）：`start-all.sh` 起栈 → `run-all.sh` 全绿 → `scenario-routing.sh` 六场景全绿 → `stop-all.sh` 优雅停机
- [ ] T57 CHANGELOG + `--no-ff` 合并 master + 推送
