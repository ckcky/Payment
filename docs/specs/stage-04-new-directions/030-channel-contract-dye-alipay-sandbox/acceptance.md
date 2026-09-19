# Acceptance: 030 聚合支付统一渠道契约 + 全链路染色分流 + 支付宝沙箱接入

> **用途**：实现完工时的验收清单。三条硬门禁（INV）**全绿**才可合并；SC 逐条勾选，**不允许「部分完成」**。
> **配套**：[spec.md](spec.md) · [plan.md](plan.md) · [tasks.md](tasks.md)
> **当前状态**：未开工（本轮只写文档）。

## 0. 合并前置四条

- [ ] `./mvnw -B clean verify -fae` 全绿
- [ ] 本文件的 INV 硬门禁 + SC 清单全部勾选
- [ ] ADR-0075 / ADR-0076 状态已决（Proposed → Accepted，或明确记为「未采纳则本 Feature 回退」）
- [ ] 文档无漂移（`engineering-standards.md` §11 五条 grep 全过）

## 1. INV 硬门禁（任一不过 = 不可合并）

| INV | 内容 | 验证方式 | 通过 |
|---|---|---|---|
| **INV-1** | 金额纪律：内部一律 `amountMinor` + `currencyCode`；「元字符串」只在支付宝适配器内用 `BigDecimal.valueOf(minor, 2).toPlainString()` 换算；**代码中无 `double` / `float` 参与金额** | ① 全仓 grep `amountMinor` 附近的 `double`/`float`；② `AlipayAmountConversionTest` 固定向量 | [ ] |
| **INV-2** | 凭证与密钥不落库、不入 git、不进明文日志 | ① `payment_attempts` 无凭证列；② grep 日志与测试夹具；③ 检查 `application.yml` 只出现 `${ENV}` 占位 | [ ] |
| **INV-3** | 染色不参与选路 | ① 代码评审 `ChannelRouter` 实现无 `DyeContext` 引用；② `DyeNotAffectingRoutingTest` | [ ] |
| **INV-4** | 反向路径按记录解析，禁止重新路由（继承 spec 028 INV-6） | ① 退款 / 查询路径无 `ChannelRouter` 调用；② 构造「Router 会选另一渠道」的配置，退款仍回原渠道 | [ ] |
| **INV-5** | 染色入站读与出站写**同批落地** | `git show --stat <commit>` 中 `DyeFilter.java` 与 `DyeRequestInterceptor.java` 同时出现 | [ ] |
| **INV-6** | `credential != null` ⇒ 渠道仅受理 ⇒ payment 停 `PROCESSING`，不走成功收敛（不记账 / 不通知 order） | 单测 + 集成：沙箱下单后 payment 状态与 ledger / order 无副作用 | [ ] |
| **INV-7** | `application/**` MUST NOT 依赖 `com.alipay.sdk`（SDK 收口在 `AlipayGateway` 后） | `architecture-tests` ArchUnit 断言 | [ ] |
| **INV-8** | 不静默降级：非法染色值 / 染色 SANDBOX 但真实模式未启用 / 未支持的 `PaymentScene` → `400 INVALID_ARGUMENT` | 三条单测 + 三条集成断言（且**均不建单**） | [ ] |

## 2. SC 验收清单

### 契约与凭证

- [ ] **SC-002（契约兼容）** `PaymentScene` / `Goods` / `CallbackUrls` / `Payer` / `PayCredential` 就位；三个请求 record 保留兼容构造器；**既有 6 处测试桩、4 处构造点、21 个 `ChannelResult` 引用文件零改动编译**
- [ ] **SC-003（金额纪律）** `amountMinor=1 → "0.01"`；`amountMinor=100000000 → "1000000.00"`；无 `double`/`float`
- [ ] **SC-004（凭证）** 沙箱下单 `payUrl` 为签名 URL；`payment_attempts` 无凭证列；日志无完整签名 URL / 私钥 / `client_secret`
- [ ] **SC-005（凭证即待付款）** `credential != null` 时不记账、不通知 order；payment 停 `PROCESSING`；notify 到达后才收敛 `SUCCEEDED`

### 染色

- [ ] **SC-006（染色透传）** 带 `X-Dye-Tag: SANDBOX` 从 demo 页经 `/proxy` → order → payment，最终 `payment_attempts.channel_mode = 'SANDBOX'`；不带头时为 `'MOCK'`
- [ ] **SC-007（fail fast）** 非法染色值 → `400` + 指标；染色 SANDBOX 而 `enabled=false` → `400`；**二者均不建单**（`payments` 无新增行）
- [ ] **SC-008（染色不入路由）** 同一 `RouteContext` 在两种染色下选出**同一** `channelCode`
- [ ] **SC-010（入站出站同批）** 两文件同批存在；单测断言 order-service 侧读出头、Feign 出站写下头

### 模态落库与反向

- [ ] **SC-011（模态落库）** 新列三处 schema 齐备；**全新库**与**存量库 + 迁移脚本**两条路径均可用；存量行读为 `'MOCK'`；幂等重复不覆盖库内值
- [ ] **SC-012（反向还原）** 对沙箱支付退款：渠道调用发生在 `DyeContext=SANDBOX` 包裹内，退款 attempt `channel_mode='SANDBOX'`，且**未被重新路由**

### 沙箱协议

- [ ] **SC-009（SDK 收口）** ArchUnit：`application/channel/**` MUST NOT 依赖 `com.alipay.sdk`
- [ ] **SC-013（离线可测）** 新依赖可下载；签名 / 验签 / 参数排序用固定向量钉死；单测**不连沙箱、不访问公网**
- [ ] **SC-014（notify 端点）** 合法通知 → 收敛且**恰好返回纯文本 `success`**；验签失败 → `403` 且不触达收敛服务；`WAIT_BUYER_PAY` → 不推进

### 回归与文档

- [ ] **SC-001（全量门禁）** `./mvnw -B clean verify -fae` 全绿（含 `architecture-tests`）
- [ ] **SC-015（零回归）** 不染色路径下，既有支付 / 退款 / 可靠性 / 集成 / E2E 测试**零改动**通过
- [ ] **SC-016（文档一致）** `payment-service.md` §3.11 与代码一致；`technical-solution.md` 仅描述结构不重复字段；ADR README 两张表与 traceability 已登记；**链接与锚点自检 0 断链**

## 3. 演示验收

### 3.1 本地 mock 动线（默认，可复现）

- [ ] demo 页**不选**环境 → 下单 → `channel_mode='MOCK'`，`payUrl` 指向 mock 收银台 → 收银台点「支付成功」→ payment `SUCCEEDED`
- [ ] 金额尾号 `11` → 仍 `timeout`（基类横切行为**未被覆写**，ADR-0072 不变量保持）
- [ ] `X-Dye-Tag: BLUE` → `400 INVALID_ARGUMENT`，**不建单**

### 3.2 支付宝沙箱动线（手工 live，不进 CI）

> 前置：沙箱 APPID / 应用私钥 / 支付宝公钥；`notify_url` 公网可达（内网穿透）；
> `payment.channel.adapters.alipay.sandbox.enabled=true`。

- [ ] demo 页选「支付宝沙箱」→ 下单 → `channel_mode='SANDBOX'`，`payUrl` 以沙箱网关开头
- [ ] `window.open(payUrl)` 打开的是**支付宝沙箱收银台**（不是我们的 mock 收银台）
- [ ] 沙箱买家账号付款 → notify 到达 → payment `SUCCEEDED`，`channel_reference = trade_no`
- [ ] notify 应答为纯文本 `success`（抓包或日志确认）
- [ ] 对笔沙箱支付发起退款 → 退款 attempt `channel_mode='SANDBOX'`，`payment.status` 不回滚
- [ ] `/demo/trace?orderId=` 能读到 `channel_mode` / `channel_code` / `channel_reference` 三列
- [ ] 关闭 `enabled` 后再选沙箱 → `400 INVALID_ARGUMENT`（**kill switch 有效且不静默回落**）

## 4. 明确不验收（本期不做，避免误判）

- ❌ 二维码 / 表单 HTML / JSAPI 参数集 / `client_secret` 的**端到端**承载（L1，需改 `common-dto`）
- ❌ 微信 / 抖音 / Stripe 的真实协议调用（仅契约兼容，实现后续逐家接）
- ❌ 渠道真实健康探测、自动降级 failover、智能路由（spec 028 §8 继续有效）
- ❌ 沙箱进 CI（L6）——**真机验证只认手工证据**
- ❌ 支付状态机新增状态（待付款复用 `PROCESSING`，D7）

## 5. 已知限制确认（实现完须逐条核对仍成立）

- [ ] L1 非跳转型凭证无处承载（`CreatePaymentResponse` 只有 `payUrl`）
- [ ] L2 染色不参与选路 → 染色 SANDBOX + 非 ALIPAY 渠道 = `400`
- [ ] L3 沙箱待付款单 30s 后可能被 `TimeoutScanner` 转 `UNKNOWN`（与 mock 延迟路径同构）
- [ ] L4 `notify_url` 须公网可达（本地需内网穿透）
- [ ] L5 SDK 体积与 CVE 风险（已收口在 `AlipayGateway` 后）
- [ ] L6 沙箱不进 CI
- [ ] L7 `page.pay` 同步响应不含 `trade_no` → `charge` 时 `channel_reference = null`
- [ ] L8 抖音两套体系 / 支付宝退款 `refund_notify_url` 待实现期复核
- [ ] L9 沙箱 10s 与全局 1.5s 两档超时并存
- [ ] L10 notify 同步处理
- [ ] L11 染色值不在业务体回显（仅响应头 + MDC）

## 6. 总体结论

| 项 | 结论 |
|---|---|
| INV 硬门禁 | ☐ 全过 ／ ☐ 未过（阻塞合并） |
| SC 清单 | ☐ 全部勾选 ／ ☐ 未完成 |
| 演示 | ☐ mock 动线通过 ／ ☐ 沙箱动线通过（手工） |
| 已知限制 | ☐ 已逐条确认 |
| 签字 | ☐ 负责人 |

**兜底原则**：任一条 INV 未过 → **不合并**；SC 未勾完 → **不标 Implemented**；
沙箱手工验证因环境（密钥 / 穿透）不可用而未做 → **必须在 ADR-0076 与本文件显式记录未验证范围**，不得默认通过。
