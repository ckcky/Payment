# Acceptance: 030 统一渠道契约 + Mock/沙箱双模态 + 回调基础闭环

> **用途**：实现完工时的验收清单。**INV 硬门禁全绿**才可合并；SC 逐条勾选，**不允许「部分完成」**。
> **配套**：[spec.md](spec.md)（v1.1） · [plan.md](plan.md) · [tasks.md](tasks.md)
> **编号与取代**：本 Feature 编号 **`030`**；旧 stage-04 的 `030-channel-contract-dye-alipay-sandbox` 四件套**已于 2026-09-19 整目录删除**（见 [spec §0.3](spec.md#03-编号裁决与旧-030-的处置)）。旧 030 的 INV-1~8 / SC-001~SC-016 **已全部并入本文件**并扩展；**编号映射：旧 030 的 `SC-001~SC-016` → 本文件的 `SC-A-01~SC-A-16`**，编号不复用以避免歧义
> **当前状态**：未开工（本轮只写 Plan）。

---

## 0. 合并前置五条

- [ ] `./mvnw -B clean verify -fae` **全绿**（含 `deployment/architecture-tests`）
- [ ] 本文件的 **INV 硬门禁（10 条）+ SC 清单**全部勾选
- [ ] **门 0** 完成：D-1~D-6 漂移收口（`payment-service.md` 无悬空锚点、无重复 `### 3.10`、**无「已实现的 `channel_mode` 列」虚假陈述**）
- [ ] **门 1 / 门 2 已通过**（ADR-0075/0076 🟢 Accepted；H2 = 通用 JSON 列、H3 = 引入 SDK）
- [ ] 文档无漂移（`docs/guides/engineering-standards.md` §11 五条 grep 全过）

---

## 1. INV 硬门禁（**任一不过 = 不可合并**）

| INV | 内容 | 验证方式 | 通过 |
|---|---|---|---|
| **INV-1** | **金额纪律**：内部一律 `amountMinor`(long) + `currencyCode`(String)；「元字符串」**只在支付宝适配器内**用 `BigDecimal.valueOf(minor, 2).toPlainString()` 换算；**代码中无 `double` / `float` 参与金额** | ① 全仓 grep 金额换算处的 `double`/`float`；② `AlipayAmountConversionTest` 固定向量 | [ ] |
| **INV-2** | **凭证与密钥不落库、不入 git、不进明文日志**；持久化渠道标识恒为 `payment_attempts.channel_reference` | ① `payment_attempts` **无凭证列**；② grep 日志与测试夹具；③ `application.yml` 只出现 `${ENV}` 占位 | [ ] |
| **INV-3** | **染色不参与选路**：`ChannelRouter` MUST NOT 读取 `DyeContext` | ① 代码评审 `ChannelRouter` 实现无 `DyeContext` 引用；② `DyeNotAffectingRoutingTest`；③ ArchUnit | [ ] |
| **INV-4** | **反向路径按记录解析，禁止重新路由**：退款 / 重试 / 主动查询 MUST 用 `payment_attempts.channel_code` 解析；染色 MUST NOT 让反向路径改换渠道 | ① 反向路径无 `ChannelRouter` 调用；② 构造「Router 会选另一渠道」的配置，退款仍回原渠道 | [ ] |
| **INV-5** | **染色入站读与出站写同批落地**（依据 S17：`InternalToken` 曾因只做入站校验导致**全线 403**） | `git show --stat <commit>` 中 `DyeFilter.java` 与 `DyeRequestInterceptor.java` **同时出现** | [ ] |
| **INV-6** | `credential != null` ⇒ 渠道**仅受理**、买家未付 ⇒ payment **停 `PROCESSING`**，**MUST NOT** 走成功收敛路径（不记账 / 不通知 order） | 单测 + 集成：沙箱下单后 payment 状态与 ledger / order **无副作用** | [ ] |
| **INV-7** | `application/**` **MUST NOT** 依赖 `com.alipay.sdk`（SDK 收口在 `AlipayGateway` 后） | `architecture-tests` ArchUnit 断言 | [ ] |
| **INV-8** | **不静默降级**：非法染色值 / 染色 `SANDBOX` 但真实模式未启用 / 请求渠道未声明的 `PaymentScene` ⇒ **`400 INVALID_ARGUMENT`** | 三条单测 + 三条集成断言（且**均不建单**） | [ ] |
| **INV-9** | **超时 MUST NOT 转 `FAILED`**：`TimeoutScanner` 只写 `UNKNOWN`（宪法 §V.7） | ① `TimeoutScanTest` 保持通过；② 代码评审 `TimeoutScanner` 无 `FAILED` 写入路径 | [ ] |
| **INV-10** | **回调 MUST NOT 在校验失败时推进状态**：验签 / 引用归属 / 金额币种**任一失败** ⇒ 拒绝且**原状态不变** | 四类失败各自断言：不推进 + 指标 + 审计 | [ ] |

---

## 2. SC 验收清单

### 2.1 A 类 —— 契约与凭证

- [ ] **SC-A-01（全绿门禁）** `./mvnw clean verify -fae` 全绿（含 `architecture-tests` 边界门禁）
- [ ] **SC-A-02（契约兼容）** `PaymentScene` / `Goods` / `CallbackUrls` / `Payer` / `PayCredential` 就位；三个请求 record 扩展后**保留兼容构造器**；**既有 4 处构造点 + 6 处测试桩 + 21 个 `ChannelResult` 引用文件编译零改动**
- [ ] **SC-A-03（金额纪律）** `amountMinor=1 → "0.01"`；`amountMinor=100000000 → "1000000.00"`；代码中**无 `double`/`float`** 参与金额
- [ ] **SC-A-04（凭证）** 沙箱下单 `payUrl` 为**签名 URL**；`payment_attempts` **无凭证列**；日志**无**完整签名 URL / 私钥 / `client_secret`
- [ ] **SC-A-05（凭证即待付款）** `credential != null` 时**不记账、不通知 order**；payment 停 `PROCESSING`；**notify 到达后**才收敛 `SUCCEEDED`

### 2.2 A 类 —— 染色

- [ ] **SC-A-06（染色透传 + 模态落库）** 带 `X-Dye-Tag: SANDBOX` 从 demo 页经 `/proxy` → order → payment ⇒ **`extra_json.channelMode = 'SANDBOX'`** 且**新写入行必含该键**；不带头 ⇒ `'MOCK'`；响应头回写 `X-Dye-Tag`；MDC 有 `dyeMode`
- [ ] **SC-A-07（fail fast）** 非法染色值 → `400` + 指标；染色 `SANDBOX` 而 `enabled=false` → `400`；**二者均不建单**（`payments` 无新增行）
- [ ] **SC-A-08（染色不入路由）** 同一 `RouteContext` 在两种染色下选出**同一** `channelCode`
- [ ] **SC-A-09（SDK 收口）** ArchUnit 通过：`application/**` 不依赖 `com.alipay.sdk`
- [ ] **SC-A-10（入站出站同批）** `DyeFilter` 与 `DyeRequestInterceptor` **同批存在**并通过测试

### 2.3 A 类 —— 模态落库与反向还原

- [ ] **SC-A-11（模态落库）** 新列 **`extra_json`** **三处 schema 齐备**（建表语句 + 增量迁移 `030-payment-attempt-extra-json.sql` + 测试 H2 schema）；**全新库**与**存量库 + 迁移脚本**两条路径均可用；存量行（`NULL`）读为 `'MOCK'`；**非法 JSON / 缺 `channelMode` 键 / 值非法 ⇒ fail-safe `'MOCK'`**；迁移脚本**可重复执行**
- [ ] **SC-A-12（反向还原）** 沙箱单退款 / 主动查询：渠道调用在**正确模态**包裹内，退款 attempt 模态正确，**渠道未被重新路由**；`resolveRecordedChannel` 有**确定性排序**且同时解析 `channel_code` 与 `channelMode`

### 2.4 A 类 —— 沙箱协议与回调

- [ ] **SC-A-13（离线可测）** 新依赖可下载；签名 / 验签 / 参数排序用**固定向量**单测钉死；**全程不连沙箱、不访问公网**
- [ ] **SC-A-14（notify 端点）** 合法通知收敛并**恰好返回纯文本 `success`**（无引号 / 无换行 / 无 JSON 包装）；验签失败 `403` 且**不触达**收敛服务；`WAIT_BUYER_PAY` **不推进**
- [ ] **SC-A-15（零回归）** 不染色路径既有测试**零改动**通过
- [ ] **SC-A-16（文档一致）** `payment-service.md §3.11` 与代码一致；`adr/README.md` 两张表 + `traceability.md` 已登记；**链接与锚点自检 0 断链**

### 2.5 B 类 —— B1 账本幂等键（🔴）

- [ ] **SC-B1-01** 同步路径 posting 键 **== `PAYMENT:{paymentNo}`**
- [ ] **SC-B1-02** 回调路径 posting 键 **== 同一值**（与同步路径**同一键**）
- [ ] **SC-B1-03** 同一支付单先同步成功再收到回调 ⇒ 账本分录数 **= 1**（不是 2）
- [ ] **SC-B1-04** 并发两条路径同时记账（**需真库**）⇒ 唯一约束吸收，分录数 **= 1**
- [ ] **补充验收 · FR-220 / FR-222** 前缀拼接**只留一处**（`FeignLedgerPostingGateway`）；存量旧键 posting **保留为历史事实、不迁移**（spec §16 未单列 SC，此处补验）

### 2.6 B 类 —— B2 回调语义校验（🟠）

- [ ] **SC-B2-01** 金额不符回调 ⇒ **拒绝推进** + 原状态不变 + 指标 + 审计
- [ ] **SC-B2-02** 币种不符回调 ⇒ 同上
- [ ] **SC-B2-03** 引用**串号**（`channelReference` 已被其他 `payment_no` 占用）⇒ 拒绝推进 + 指标 + 审计
- [ ] **SC-B2-04** 引用与 attempt 已记录值**冲突** ⇒ 拒绝；attempt 值为**空**则**回填**
- [ ] **SC-B2-05** **重复回调不被误拒**：同结果重复 ⇒ 仍**幂等吸收**（不因 B2 反复拒绝）
- [ ] **SC-B2-06** **两条路径同口径**：JSON 路径与 notify 路径的校验行为**一致**

### 2.7 B 类 —— B4 `channelReference` 回填（🟠）

- [ ] **SC-B4-01** `accepted(ref)` 的 ref 在 `converge` 的 **UNKNOWN 分支落库**
- [ ] **SC-B4-02** 异步受理后 `channel_reference` **非空**（受理流水号不丢）

### 2.8 B 类 —— B7 在途守卫（🟠）

- [ ] **SC-B7-01** TXRF=`REQUESTED` + 渠道调用失败 ⇒ 重试时渠道请求次数 **= 2**（首次 + 重试），**不是 1**
- [ ] **SC-B7-02** TXRF=`PROCESSING`（渠道已受理）⇒ 重试时渠道请求次数 **= 1**（正确回放，不重复调）
- [ ] **SC-B7-03** 并发两次同参 surplus 退款 ⇒ 最终**只产生一个** TXRF + 一个 PMRF（三层防线）
- [ ] **SC-B7-04** 渠道调用持续失败至重试耗尽 ⇒ **指标 + ERROR 日志可观测**（不静默）
- [ ] **SC-B7-05** TXRF 停留 `REQUESTED` 超阈值 ⇒ **指标**可发现（告警规则属后续 Feature）

### 2.9 B 类 —— 跨项（既有行为零变化）

- [ ] **SC-B-06** **既有 surplus 行为零变化**：surplus TXRF **不累加** `refunded_minor`、**不改**订单状态、**不终止**履约（回归 `TransactionRefundTest.surplusRefundClosesWithoutBooking` 保持通过）
- [ ] **补充验收 · FR-233** `RefundPolicy` 累计上限（同支付单累计申请额 ≤ 已支付额）**仍为最后防线**，未被 B7 改动绕过（spec §16 未单列 SC，此处补验）

---

## 3. 演示验收

### 3.1 本地 mock 动线（默认，**可复现，进 CI**）

- [ ] demo 页默认「本地 mock」下单 ⇒ `payUrl` 指向本地 mock 收银台；`extra_json.channelMode = 'MOCK'`
- [ ] 不带头 / 带头为 `MOCK` ⇒ 行为**完全一致**（缺省即 MOCK）
- [ ] 金额尾号 `11` ⇒ 仍 `timeout` ⇒ payment 进 `UNKNOWN`（**不是 `FAILED`**）⇒ 主动查询收敛
- [ ] 既有支付 / 退款 / 可靠性测试**零改动**通过

### 3.2 支付宝沙箱动线（**手工 live，不进 CI**）

前置：配置 `PAYMENT_ALIPAY_*` 环境变量；内网穿透暴露 `notify_url`；置 `payment.channel.adapters.alipay.sandbox.enabled=true`。

- [ ] demo 页选「支付宝沙箱」⇒ 请求带 `X-Dye-Tag: SANDBOX` ⇒ order → payment 全链路透传
- [ ] payment 侧 `channel_code = ALIPAY`、`extra_json.channelMode = 'SANDBOX'`
- [ ] `window.open(payUrl)` 打开**支付宝沙箱收银台**（不再跳本地 mock 收银台）
- [ ] 沙箱买家账号付款 ⇒ notify 到达 ⇒ payment 收敛 `SUCCEEDED`；`payment_attempts.channel_reference = trade_no`
- [ ] ledger 有 `PAYMENT:{paymentNo}` 分录（**恰好一条**）
- [ ] 退款：沙箱单退款走**沙箱分支**（模态从库还原），渠道**未被重新路由**

> ⚠️ **未验证范围 MUST 显式记录**（ADR-0076 R10）——不得默认通过。

---

## 4. 明确不验收（本期不做，避免误判）

| # | 不验收项 | 依据 |
|---|---|---|
| 1 | **渠道 `close`（关单）** | N15 / H20 —— 行为变更，已挂起（034） |
| 2 | **`UNKNOWN → ACCEPTED` 迁移** | H13 未裁决；裁决前**不得**放行 |
| 3 | **order 侧退款补偿扫描器** | H17 未裁决；本 Feature 只补指标 |
| 4 | **C-23 订单取消后迟到成功的追回** | 落点 034 / H20 |
| 5 | **C-19 退款侧超时扫描 / 主动查询** | 落点 034 / H10 |
| 6 | **C-20 对账/结算事实的期间与商户维度** | N2 —— 后续 Feature |
| 7 | **Ledger 完整重构 / Reconciliation / Settlement 改造** | N1 / N2 / N3 |
| 8 | **微信 / 抖音 / Stripe 真实协议** | N10 —— 契约兼容四家，**只实现支付宝沙箱** |
| 9 | **二维码 / 表单 / JSAPI 参数的端到端承载** | N13 —— 需改 `common-dto`，属 API 变更 |
| 10 | **HMAC 真实验签** | ADR-0025 决议：占位**保持不变**（保留给本地 mock 路径） |
| 11 | **新增 MQ / 新中间件 / 新微服务 / 分布式事务** | N5 / N6 / N8 |
| 12 | **任何「超时即失败」的自动终态化** | N16 / INV-9 |
| 13 | **「禁止多 Payment SUCCESS」「换渠道前关闭旧 Payment」** | N17 / N18 / N19 —— **会破坏当前正确的 surplus 行为** |

---

## 5. 已知限制确认（实现完须逐条核对**仍成立**）

| # | 限制 | 实现后核对 |
|---|---|---|
| L1 | **H2 无法验证真并发与唯一约束触发路径** ⇒ SC-B1-04 / SC-B7-03 在 H2 上**可能假绿** | [ ] 已标注需 **Testcontainers-MySQL**（H16 / 033） |
| L2 | **沙箱不可复现、需密钥 + 公网 `notify_url`** ⇒ 真机验证为**手工** | [ ] 未验证范围已**显式记录**（ADR-0076 R10） |
| L3 | **`RefundPolicy` 比较符此前未逐行审查**（`>` vs `>=`） | [ ] 已补边界单测（T17） |
| L4 | **支付宝退款是否有独立 `refund_notify_url`** 未确认 | [ ] 已复核；**只影响适配器内部映射，不影响内部契约** |
| L5 | **`PaymentAttemptStatus.ACCEPTED` 无路径能停留**（H13 未裁决） | [ ] 未放行 `UNKNOWN → ACCEPTED`；该态仍为**事实上的预留态** |
| L6 | **`notify` 端点为同步处理**（FR-207） | [ ] 实测耗时**未**逼近支付宝超时窗口；否则已改为「先应答后异步处理」并记录 |
| L7 | **`sandbox.enabled=false` 时沙箱能力不可用** | [ ] 无密钥时服务**照常启动**、CI 可跑；染色 `SANDBOX` 明确 `400`（不静默回落） |
| L8 | **`extra_json` 为可空 TEXT**（R17） | [ ] 写入侧强制 + 读取侧 fail-safe 双保险已落地；坏数据行**未**导致误连真实渠道 |

---

## 6. 总体结论

- [ ] **INV-1~INV-10 全绿** + **SC 清单全部勾选** + **明确不验收项未被误做** ⇒ **可合并**
- [ ] 实现期若出现任何偏离（改 `common-dto`、新增 `AWAITING_PAYMENT`、契约加 `close`、引入 MQ），**已回负责人确认**

**结论**：⬜ 通过 / ⬜ 不通过（**不允许「部分通过」**）

**签署**：______ 日期：______
