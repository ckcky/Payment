# Acceptance: 028 支付两层结构 + 渠道路由

对照 [spec.md](spec.md) 的 INV-1~INV-6 与 SC-001~SC-013。**全部满足才可合并。**

> 断言口径（全篇）：渠道归属**一律读 `payment_attempts.channel_code` 列**，**不以渠道引用的字符串形态作为验收标准**（spec §3 US2 尾注）。

## INV 不变量（硬门禁）

- [ ] **INV-1** 未触碰回调成功链路：扣库存仍由 order-service 发起，payment-service 无新增库存动作
- [ ] **INV-2** 未引入自动换渠道：构造渠道失败场景，断言**不产生第二张支付单**、旧单保留 FAILED、不调用 `Payment.close()`
- [ ] **INV-3** 路由确定性：同一 `RouteContext` 连续 100 次 `route()` 结果一致
- [ ] **INV-4** `application/channel/**` 无 `infra/channel/**` 依赖（ArchUnit 断言）
- [ ] **INV-5** `payments` 只由 payment 层写、`payment_attempts` 只由渠道层写；payment 层无 `PaymentAttemptRepository` 直接依赖
- [ ] **INV-6** 退款 / 重试 / 主动查询的渠道取自 attempt 记录，**禁止重新路由**

## SC-001 构建全绿

- [ ] `mvn -o clean verify -fae` 全绿
- [ ] `architecture-tests` 边界门禁通过（含 T43/T44 两条新规则）

## SC-002 两层结构（ADR-0072）

- [ ] `infra/channel/` 存在抽象基类 + 3 个渠道实现 + `MockChannelAdapter`
- [ ] `PaymentChannel.channelCode()` 在端口上，4 个测试桩已补实现
- [ ] payment 层无 `PaymentAttemptRepository` 直接依赖
- [ ] `payment_attempts` 写入口经渠道层端口（`ChannelAttemptRecorder`）
- [ ] 代码评审确认：`payments` 与 `payment_attempts` 仍在**同一本地事务**内（分层 ≠ 拆事务）

## SC-003 退款渠道归属（INV-6）

- [ ] 对 `channel_code=WECHAT` 的已成功支付发起退款 → 退款 attempt 行 `channel_code == 'WECHAT'`
- [ ] `PaymentRefundService` 中无硬编码 `"mock"`；`RefundRequest.channelCode` 被实际读取
- [ ] `refund()` 引用无硬编码 `"mock-refund-ref-"` 字面量
- [ ] 构造「Router 会选另一渠道」的配置，退款**仍回原渠道**
- [ ] 存量 `channel_code='mock'` 的 REFUND 行**未被回填**（FR-007）

## SC-004 US2 主路径

- [ ] 不传 `channelCode` 发起支付 → `payment_attempts.channel_code` == 配置 `priority` 最小的 `enabled` 渠道
- [ ] 全链路不变：订单 PAID、交易 SUCCEEDED、库存扣减、履约与权益、账本平衡

## SC-005 US3 显式优先

- [ ] 显式传 `channelCode=DOUYIN`（即使 `enabled=false`）→ 支付单落在 DOUYIN
- [ ] 指标 `payment_routing_total{result="explicit"}` +1
- [ ] 显式传 `enabled=false` 渠道时有 warn 指标

## SC-006 无可用渠道

- [ ] 全部 `enabled=false` 且不指定渠道 → `409 NO_AVAILABLE_CHANNEL`
- [ ] `payments` 与 `payment_attempts` **无新增行**（不允许部分写入）

## SC-007 确定性（INV-3）

- [ ] 单测：同一 `RouteContext` 连续 100 次 `route()` 结果完全一致
- [ ] 代码评审确认：选路路径无随机数 / 时间 / 进程级计数器

## SC-008 启动强校验（FR-032）

- [ ] 单测：重复 `channelCode` 装配 → Bean 创建失败并打印重复 code
- [ ] 单测：非法 `priority` / 未知渠道码 / 全渠道 `enabled=false` / 非法 `availability.status` → **启动失败**且含合法取值清单

## SC-009 分层门禁

- [ ] ArchUnit 断言通过（INV-4 / INV-5）

## SC-010 反向不重新路由

- [ ] 退款 / 重试 / 查询路径的渠道来源断言为 attempt 记录值
- [ ] 三处改造点已核实：`PaymentRefundService` / `ChannelQueryService` / `PaymentRetryService`

## SC-011 可观测

- [ ] 三类路径各触发一次，`payment_routing_total` 对应取值各计一次
- [ ] 路由 INFO 日志含 `routedCode` 与 `traceId`（MDC）
- [ ] `no_available_channel` 有对应 WARN

## SC-012 零回归

- [ ] 既有支付 / 退款 / 可靠性 / 集成 / E2E 测试**零改动**通过
- [ ] 10 个 `new MockChannelAdapter(...)` 测试文件未被修改
- [ ] `MockChannelAdapterScenarioTest` 断言的错误消息文本继续有效

## SC-013 文档漂移消除

- [ ] `payment-service.md` §2.1「必须已注册到渠道 Registry/Router」与实现一致
- [ ] `payment-service.md` §3 渠道抽象、§3.2 请求表（`channelCode` 可选）、§4.3 退款渠道口径 与实现一致
- [ ] `technical-solution.md` §3.1/§3.6 两层描述、§4.3 时序图与基数 与实现一致
- [ ] `docs/operations/runbook.md` 配置项与排障入口 与实现一致
- [ ] `spec 015 §8` 第 1 条已标注「由 ADR-0073 取代」

## 演示（批次 G）

- [ ] `routing.html` 可从 `portal.html` 进入，无后端代理新建
- [ ] `scenario-routing.sh` 六场景全绿（S1~S6），断言读 `channel_code` 列
- [ ] 演示开关仅 `demo` profile 注册；重启后回到配置值
- [ ] `run-all.sh` 既有断言不受影响

## 总体

- [ ] 无安全类改动（内部端点鉴权口径沿既有约定，本 spec 不涉及）
- [ ] 无新增中间件 / 无新增第三方依赖（Constitution）
- [ ] CHANGELOG 已更新
- [ ] 两份 ADR 状态已升为 Accepted
