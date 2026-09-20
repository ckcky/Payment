# Plan: 030-channel-contract-sandbox-callback（实现计划）

**配套**：[spec.md](spec.md)（v1.1） · [tasks.md](tasks.md) · [acceptance.md](acceptance.md)
**决策**：[ADR-0075](../../../adr/0075-unified-channel-contract.md)（🟢 **Accepted**） · [ADR-0076](../../../adr/0076-traffic-dyeing-and-alipay-sandbox.md)（🟢 **Accepted**，含 2026-09-19 **落库形态修订**）
**编号**：本 Feature 编号 **`030`**（原 `031`）。原 stage-04 的 `030-channel-contract-dye-alipay-sandbox` 四件套**已整目录删除**（2026-09-19，设计已全量吸收，见 [spec §0.3](spec.md#03-编号裁决与旧-030-的处置) 与本文 §5）
**本轮状态**：**只写 Plan，不写代码**。本文件是可执行的实现计划，经负责人确认后按批次开工。

---

## 0. 前置门状态（**开工前必读**）

| 门 | 内容 | 状态 | 结论 |
|---|---|---|---|
| **门 0** | 文档收口（D-1~D-6 漂移）+ ADR 登记 | 🟡 **部分完成**：ADR-0075/0076 已转 Accepted 并登记进 `adr/README.md` 两张表 + `traceability.md`（2026-09-19）；**D-1~D-6 漂移收口待做** | **不依赖任何裁决，可先做** → 批次 0 |
| **门 1** | ADR-0075 / ADR-0076 转 Accepted | ✅ **已通过**（2026-09-19） | 解除阻塞 |
| **门 2** | H2（模态落库载体）/ H3（新依赖）裁决 | ✅ **已通过**：H2 = **通用 JSON 列** `extra_json` 承载 `channelMode`；H3 = **引入** `alipay-sdk-java` | 解除阻塞 |
| **门 3** | H4（回调金额口径）/ H17（B7 兜底扫描器） | 🟡 **部分通过**：H4 ✅（**拒绝推进 + 记差异**）；H17 ⏳ 未裁决 | H17 仅影响 **FR-234 的完整形态**，不阻塞主体 |

**仍未裁决（**均不阻塞主体实现**）**：H9/H15（编号重排）、H13（`UNKNOWN → ACCEPTED`）、H17（B7 兜底扫描器）、H20（C-23 / 渠道 `close` 归属）。

> ⚠️ **H13 未裁决前，实现 MUST NOT 放行 `UNKNOWN → ACCEPTED` 迁移**（spec §6.2）。
> ⚠️ **H20 未裁决前，实现 MUST NOT 在 `PaymentChannel` 上加 `close`**（spec §7.2 / N15）。

---

## 1. Summary（技术路径）

一条主链 + 两条独立收口：

```
【主链】契约能装下四家 → 凭证回到浏览器 → 染色决定协议实现 → 沙箱真调支付宝 → 回调三段式校验 → 收敛
【收口 1】B1 账本幂等键双口径统一（🔴 缺陷，与主链无关，可最先做）
【收口 2】B7 自动退款在途守卫区分「重放 / 重试」（🟠 缺陷，与主链无关，可最先做）
```

1. **契约层**：`application/channel/` 补齐 `PaymentScene` / `Goods` / `CallbackUrls` / `Payer` / `PayCredential`；
   扩展现有三个请求 record 并**保留兼容构造器**；`ChannelResult` 加**可空** `credential`；
   `PaymentChannel` 加两个 `default` 方法。**全部向后兼容**——4 处构造点、6 处测试桩、21 个引用 `ChannelResult` 的测试文件**编译零改动**。
2. **凭证出参**：`credential.payload()` 经 `RoutedPayment` 填进**现有** `CreatePaymentResponse.payUrl`；
   `common-dto` 与 order-service **零改动**。`credential != null` ⇒ 渠道仅受理、买家未付 ⇒ payment 停 `PROCESSING`（INV-6）。
3. **染色**：`common-core` 加 `dye` 三段式（`DyeMode` / `DyeContext` / `DyeFilter` / `DyeRequestInterceptor`），
   **入站读 + 出站写同批落地**（INV-5）；`X-Dye-Tag` **缺省即 `MOCK`**（安全默认），非法值 `400` fail fast。
4. **模态落库（H2 修订后）**：`payment_attempts` 加**通用 JSON 列** `extra_json TEXT NULL`，模态以键 **`channelMode`** 承载；
   写入侧**强制含键**，读取侧 **fail-safe 一律回落 `MOCK`**；反向三条路径（退款 / 主动查询 / 超时扫描）据它还原。
5. **沙箱适配器**：`AlipayChannelAdapter` **双模态**（`MOCK` 分支 `super` 委托），协议收口在 `AlipayGateway` 端口后（INV-7）；
   新增 RSA2 notify 端点，**复用**现有收敛链路。
6. **回调三段式校验**：`① 验签（身份） → ② 引用归属 → ③ 金额/币种（事实）`，
   **两条回调路径同口径**（消除「新端点严、老端点松」）；任一失败 ⇒ **拒绝推进且原状态不变**（INV-10）。

---

## 2. Technical Context

| 项 | 值 |
|---|---|
| 语言 / 运行时 | Java 21；Spring Boot 3.5.x；Maven 多模块 |
| 变更服务 | **payment-service**（主体）、**common-core**（染色骨架）、**order-service**（仅 B7）、`mock-channel-web`（仅演示页静态资源） |
| **零改动服务** | **`common-dto`（硬约束）**、其余 7 个领域服务（ledger / reconciliation / settlement / catalog / fulfillment / entitlement / merchant） |
| 构建 / 测试 | `./mvnw -B clean verify -fae`；单元 + 集成用 H2（MySQL 兼容模式）；E2E 默认 skipTests |
| 新依赖 | `com.alipay.sdk:alipay-sdk-java`（版本在**根 pom `dependencyManagement`** 锁定，禁止子模块写死版本）；当前根 pom **无** alipay 相关条目 |
| DB 变更 | `payment_attempts` **加 `extra_json TEXT NULL`**（**非破坏性**：可空列、无默认值回填、存量行读为 `MOCK`） |
| 新增 HTTP 端点 | `POST /internal/channels/alipay/notify`（payment-service，`form-urlencoded`） |
| 新增配置 | `payment.channel.adapters.alipay.sandbox.*`（`enabled` 默认 **`false`**） |
| 外部依赖 | 支付宝沙箱网关（**仅手工 live 验证，不进 CI**）、公网可达的 `notify_url`（本地需内网穿透） |
| 未知项（实现期复核） | ① 支付宝退款是否有独立 `refund_notify_url`；② 抖音两套下单体系的取舍（本 Feature 不实现抖音，仅契约兼容） |

---

## 3. Constitution Check（GATE）

| # | 检查项 | 结论 | 说明 |
|---|---|---|---|
| C1 | 跨领域改数据 / DB 结构变更？ | ⚠️ **是（人类决策边界）→ 已裁决** | `payment_attempts` 加列属 **Database Schema Migration**。**H2 已裁决**：采用**通用 JSON 列** `extra_json TEXT NULL`（**不新增专用列**）。**非破坏性**（可空、无回填），且**建表 + 增量迁移 + 测试 schema 三处齐备**（`CREATE TABLE IF NOT EXISTS` 不会给存量库补列）。 |
| C2 | 核心领域依赖具体渠道实现？ | ✅ **否** | `application/**` 仍只依赖 `PaymentChannel` / `AlipayGateway` 端口；SDK 收口在 `infra/channel/alipay/`，**ArchUnit 断言**（INV-7 / SC-A-09）。 |
| C3 | 新增微服务 / 中间件 / MQ 主题？ | ✅ **否** | 无新服务、无新中间件、不引入 MQ（N5）、不引入配置中心（N20）。 |
| C4 | 新增第三方依赖？ | ⚠️ **是（人类决策边界）→ 已裁决** | `alipay-sdk-java`。**H3 已裁决：A 引入官方 SDK**，风险（34.3 MB / 传递依赖 / ≥9 CVE）**显式接受并记入 ADR-0076**（FR-295）。 |
| C5 | 改领域模型 / 状态机 / 服务边界？ | ⚠️ **部分（需保持纪律）** | **基数关系不动**（`Transaction 1:N Payment` / `Payment 1:1 PaymentAttempt`，§4.1）；**服务边界不动**；`PaymentStatus` **不动**。`PaymentAttempt` 新增 `extra` 字段属**字段级新增**（非语义变更）。**`PaymentAttemptStatus` 的 `UNKNOWN → ACCEPTED` 不放行**（H13 待裁决，§6.2）。 |
| C6 | 改公共 API / 破坏性变更？ | ✅ **否** | 三个 record 是**服务内契约**（非跨服务 RPC），且**保留兼容构造器 + `default` 方法**；`CreatePaymentResponse` 与所有 `common-dto` **不动**；order-service 仅 B7 改动（内部缺陷修复，非 API 变更）。 |
| C7 | 安全策略变更？ | ⚠️ **是（人类决策边界）→ 已裁决** | 新增 RSA2 验签与密钥管理。口径为 ADR-0025 / ADR-0026 的自然延伸（密钥 env 注入、禁入库禁日志）；**HMAC 占位保持不变**（`ChannelCallbackSecurityTest` 零改动）。新增 notify 端点**必须公网可达**，故 **MUST 自带验签 + 语义校验**（FR-297）。 |
| C8 | 生产部署策略变更？ | ✅ **否** | `sandbox.enabled` 默认 `false`；无密钥时服务照常启动、CI 可跑。染色头**不是安全边界**（FR-296）。 |

**门禁结论**：本计划**不引入新的未确认人类决策边界**——C1 / C4 / C7 三条均已有负责人明确口径（H2 / H3 / H4）。
实现期若出现任何偏离（如改为改 `common-dto`、改为新增 `AWAITING_PAYMENT` 状态、改为在契约里加 `close`、改为引入 MQ），**MUST 先回负责人确认**。

**实现期六条禁止**（越界即回退）：

1. 禁止把渠道私有参数加进通用字段（走 `channelExtra`）；
2. 禁止用 `double` / `float` 参与任何金额换算（INV-1）；
3. 禁止让染色参与选路、或让反向路径重新选渠道（INV-3 / INV-4）；
4. 禁止只做染色的入站读而漏做出站写（INV-5，先例 S17 曾导致全线 403）；
5. 禁止「超时即失败」的自动终态化（INV-9）；
6. 禁止任何「禁止多 Payment SUCCESS」「换渠道前关闭旧 Payment」的改造（N17/N18/N19，会破坏**当前正确**的 surplus 行为）。

---

## 4. Project Structure（批次与文件级改动）

> **批次间为串行 PR**：每批次一个 feature 分支 `feature/030-<slug>`（或 `fix/<slug>`），PR 以 `--no-ff` 合并回 master。
> **禁止**跨批次长期堆积改动（宪法 §Governance 提交与合并节奏）。

### 批次 0 —— 门 0 文档收口（**docs-only，可直推 master**）

| 文件 | 动作 | 内容 |
|---|---|---|
| `docs/architecture/systems/payment-service.md` | 改 | **修 D-* 漂移**：`:52` 悬空锚点 `#311-...`、`:281`/`:302` **两个重复 `### 3.10`**；⚠️ `:146-150` 把 `channel_mode` 列 + `030-payment-attempt-channel-mode.sql` 写成「已实现」（**该列与文件均不存在**）⇒ **必须按 H2 新载体改写**（`extra_json` 的 `channelMode` 键 + `030-payment-attempt-extra-json.sql`），**不能只删旧列名** |
| `docs/architecture/technical-solution.md` | 改 | 统一 `Payment:PaymentAttempt` 基数表述（与 `systems/payment-service.md` 一致）；写入「渠道事实 / 平台事实**可合法不一致**」口径 |
| `docs/specs/stage-04-new-directions/030-channel-contract-dye-alipay-sandbox/`（四件套） | **删除** | ✅ **已完成**（2026-09-19）：**整目录删除**——内容已全量吸收进本 Feature，且旧 `channel_mode` 口径与 H2 裁决冲突。**不再保留横幅版本**（负责人裁决，见 §5） |
| `docs/adr/README.md`、`docs/adr/traceability.md` | **不动** | 已于 2026-09-19 完成登记（本批次只需复核） |

**验收**：① 全部相对链接可达、无悬空锚点；② `payment-service.md` **无重复 `### 3.10`**；③ 基数表述两处一致；④ 旧 030 四件套**已删除且全仓 0 残留引用**。

### 批次 1 —— B1 账本幂等键口径统一（**payment-service，🔴 最高优先级**）

| 文件 | 动作 | 内容 |
|---|---|---|
| `payment-service/.../application/PaymentApplicationService.java` | 改 | `:222` 由传 `payment.getIdempotencyKey()` 改为只传 **`paymentNo`** |
| `payment-service/.../application/PaymentResultProcessor.java` | 改 | `:188` 由传 `"PAYMENT:" + paymentNo` 改为只传 **`paymentNo`**（去掉手工前缀） |
| `payment-service/.../infra/client/FeignLedgerPostingGateway.java` | **不动** | `:42` **独占**前缀拼接（**前缀只留一处**） |
| `payment-service/.../application/PaymentCaptureLedgerPostingTest.java` | 改 | 补断言：两条路径产生**同一**键 `PAYMENT:{paymentNo}` |
| `payment-service/.../application/PaymentApplicationServiceTest.java` | 改 | 同上（若已有键断言则加强） |
| `ledger-service` 文档 | 改（可选） | 写明**两种历史键并存**（FR-222），避免排障误判 |

**验收**：T-B1-1~4（SC-B1-01~04）。**既有测试断言只加强、不放宽。**

### 批次 2 —— B7 在途守卫区分「重放 / 重试」（**order-service，🟠**）

| 文件 | 动作 | 内容 |
|---|---|---|
| `order-service/.../application/TransactionApplicationService.java` | 改 | `:189-198` 在途守卫判据改为「**是否已成功推进过**」：`paymentRefundNo != null` 或状态 `PROCESSING` ⇒ **回放**；状态 `REQUESTED` 且 `paymentRefundNo == null` ⇒ **重放渠道调用** |
| `order-service/.../scenario/TransactionRefundTest.java` | 改 | ⚠️ **修正固化缺陷的断言**：`inFlightRefundIsReplayedNotDuplicated:104-114` 的 `hasSize(1)` ⇒ **修正为 2**，并在 PR 描述给出缺陷证据链（`design-review §11 C-18`） |
| order-service 指标 | 改 | 补 TXRF 停留 `REQUESTED` 超阈值的**指标**（FR-234；**告警规则属后续 Feature**） |

**验收**：T-B7-1~4（SC-B7-01~05）。**明确不采用**「先调渠道后落库」（FR-231）；`RefundPolicy` 累计上限**保持为最后防线**（FR-233）。

### 批次 3 —— A 统一渠道契约 + 凭证透传（**payment-service，零破坏**）

| 文件 | 动作 | 内容 |
|---|---|---|
| `.../application/channel/PaymentScene.java` | **新增** | 枚举 `WEB/H5/NATIVE/JSAPI/MINI_PROGRAM/APP` + 四家映射对照 Javadoc（FR-101） |
| `.../application/channel/Goods.java` | **新增** | `(title, description)` + `of(title)`（FR-102） |
| `.../application/channel/CallbackUrls.java` | **新增** | `(notifyUrl, returnUrl)` + `notifyOnly(url)`；Javadoc **显式声明** `returnUrl` 不承载资金事实（FR-103） |
| `.../application/channel/Payer.java` | **新增** | `(payerId, clientIp)` + `of(payerId)` / `byClientIp(ip)`（FR-104） |
| `.../application/channel/PayCredential.java` | **新增** | `(Kind kind, String payload, Instant expiresAt)`；六值 `Kind`；`redirectUrl(...)` / `formHtml(...)` / `h5Url(...)` / `qrCode(...)` / `isRedirectFamily()`（FR-111） |
| `.../application/channel/ChargeRequest.java` | 改 | 扩为 12 字段 + **保留 5 参兼容构造器**（FR-105） |
| `.../application/channel/RefundRequest.java` | 改 | 扩为 9 字段（含 `channelTransactionId` / `outRequestNo` / `reason` / `refundNotifyUrl`）+ **保留 5 参构造器**（FR-106） |
| `.../application/channel/QueryStatusRequest.java` | 改 | 扩为 4 字段（含 `channelTransactionId`）+ **保留 3 参构造器**（FR-107） |
| `.../application/channel/ChannelResult.java` | 改 | 加可空 `credential`；加 `accepted(ref, reason, credential)`；旧 7 个工厂委托 `null`；`withReason` **保留** `credential`；保留 5 参构造重载（FR-112） |
| `.../application/channel/PaymentChannel.java` | 改 | 加 `default Set<PaymentScene> supportedScenes()`（默认全支持）、`default boolean supportsRealMode()`（默认 `false`）；**不新增 `close` / `callback`**（FR-109 / §7.2） |
| `.../application/reliability/PaymentRetryService.java` | 改 | 重试结果携带最后一次 `charge` 的 `credential`（FR-114） |
| `.../application/PaymentApplicationService.java` | 改 | `RoutedPayment(payment, channelCode, credential)`；`credential != null` ⇒ **不调 `applyAndPersist`**（FR-115 / INV-6）；建 `ChargeRequest` 时填充 `scene`/`goods`/`callbackUrls`/`expireAt`；调 `charge` **之前**做场景校验（FR-110） |
| `.../api/PaymentController.java` | 改 | `payUrl` 优先取 `credential.payload()`，否则回落既有 mock 收银台链接（FR-114） |
| `common/common-dto/**` | **不动** | 硬约束（C6） |

**验收**：T-A-01~05（SC-A-02~05）。**关键**：4 处构造点 + 6 处测试桩 + 21 个 `ChannelResult` 引用文件**编译零改动**。

### 批次 4 —— 染色骨架（**common-core，必须整体一次落地**）

| 文件 | 动作 | 内容 |
|---|---|---|
| `common-core/.../dye/DyeMode.java` | **新增** | `MOCK` / `SANDBOX` + 大小写不敏感严格 `parse`（`null`/空白 → `MOCK`；非法 → 抛异常）（FR-160） |
| `common-core/.../dye/DyeContext.java` | **新增** | ThreadLocal：`isSandbox()` / `current()` / `set` / `clear` / `runWith(mode, Runnable)`（FR-161） |
| `common-core/.../dye/DyeFilter.java` | **新增** | `OncePerRequestFilter`：读 `X-Dye-Tag` → 解析 → ThreadLocal + `MDC("dyeMode")` → 响应头回写 → `finally` 清理；非法值 `400` + `dye_tag_rejected_total{reason=invalid}`（FR-162） |
| `common-core/.../dye/DyeRequestInterceptor.java` | **新增** | Feign `RequestInterceptor`：`DyeContext` **非空才写**出站头（FR-163） |
| `common-core/.../config/CommonCoreAutoConfiguration.java` | 改 | 加 `dyeFilter` + `dyeFilterRegistration(order = -190)`（FR-164 / FR-165） |
| `common-core/.../config/FeignTraceAutoConfiguration.java` | 改 | 加 `dyeRequestInterceptor`（`@ConditionalOnMissingBean`）（FR-164） |
| `META-INF/spring/...AutoConfiguration.imports` | **不动** | 两个自动配置类已注册，新增的是其中的 Bean（FR-166） |
| `.../api/PaymentController.java` | 改 | `deferChannel = mockCashier.isEnabled() && !DyeContext.isSandbox()`（FR-167）—— **染色唯一消费点** |

> ⚠️ **本批次必须整体一次落地（INV-5）**：`DyeFilter` 与 `DyeRequestInterceptor` 拆成两次提交，
> 会在「入站已拦截非法值、出站却还没传」或反之的窗口里造成服务间行为不一致。

**验收**：T-A-07~10（SC-A-06~SC-A-08 / SC-A-10）。

### 批次 5 —— 模态落库（**schema + domain + recorder**）

| 文件 | 动作 | 内容 |
|---|---|---|
| `deployment/schema/03-payment-schema.sql` | 改 | `payment_attempts` 建表语句补 **`extra_json TEXT NULL`**（FR-301①） |
| `deployment/schema/030-payment-attempt-extra-json.sql` | **新增** | `ALTER TABLE payment_attempts ADD COLUMN extra_json TEXT NULL COMMENT ...`（存量库迁移；**幂等可重放**，FR-301② / FR-308） |
| `payment-service/src/test/resources/schema.sql` | 改 | H2 测试 schema 同步（FR-301③） |
| `.../domain/PaymentAttempt.java` | 改 | 新增 `extra`（`Map<String,String>`，**可空**）+ **只读派生访问器 `getChannelMode()`**（由 `extra` 解析，缺失/非法一律 `MOCK`）；全部工厂 / `rehydrate` 同步（FR-302 / FR-304） |
| `.../infra/persistence/ChannelAttemptRecorderImpl.java` | 改 | 创建 attempt 时读 `DyeContext`（空 → `MOCK`）**写入 `channelMode` 键**；**方法签名不变**（FR-151 / FR-303） |
| `payment-service/src/test/java/.../infra/InMemoryPaymentAttemptRepository.java` | 改 | 同步字段（FR-302） |
| JSON 编解码 | **新增** | 落在 `infra/persistence`，**领域对象 MUST NOT 依赖 Jackson**（FR-302） |

**关键约束**：
- **FR-303 写入侧强制**：**新写入行 MUST NOT 缺失 `channelMode` 键**；
- **FR-304 读取侧 fail-safe**：`NULL` / 非法 JSON / 缺键 / 值不在 `{MOCK,SANDBOX}` ⇒ **一律 `MOCK`**，**MUST NOT** 抛异常、**MUST NOT** 误判为 `SANDBOX`；
- **FR-305**：`extra_json` **MUST NOT** 用于渠道归属判定（归属恒读 `channel_code` **列**）；
- **FR-306**：幂等重复返回**库内值**，不覆盖；**FR-307**：存量 `NULL` ⇒ `MOCK`，**不回填**；**FR-309**：**不建索引**、不做按模态 SQL 统计。

**验收**：T-A-12（SC-A-11 / SC-A-06）。**双路径**：全新库 + 存量库走迁移脚本，两条都可用。

### 批次 6 —— 反向路径自足性（**查询 / 退款 / 超时扫描**）

| 文件 | 动作 | 内容 |
|---|---|---|
| `.../application/reliability/ChannelQueryService.java` | 改 | `:95-96` 查询请求传 **`attempt.getChannelReference()`**（修 C-12 / S21，FR-270）；渠道调用包裹在 `DyeContext.runWith(attempt.getChannelMode(), ...)` 内（FR-271） |
| `.../application/reliability/ChannelQueryService.java` | 改 | `resolveRecordedChannel:116-132` 加**确定性排序**（修 S22 的无 `ORDER BY` `findFirst`）；同时解析 `channel_code` **列**与 `extra_json.channelMode`；**不回落默认渠道**（FR-272 / FR-273） |
| `.../application/PaymentRefundService.java` | 改 | 退款调用包裹在模态内（FR-153）；`refund` 的**重试也在模态包裹内**（FR-263） |
| `.../application/reliability/TimeoutScanner.java` | **不改** | 扫描对象与判据不变（FR-280）；**MUST NOT** 改为扫 `UNKNOWN`、**MUST NOT** 写 `FAILED`（FR-282 / INV-9） |
| `.../application/PaymentUnknownResolutionService.java` | 改 | 补 `payment.unknown_age` **分桶**指标（FR-257；**只加指标不加自动动作**） |

**验收**：T-A-11（SC-A-12）。**断言反向路径未调用 `ChannelRouter`**（INV-4）。

### 批次 7 —— 支付宝沙箱适配器 + Gateway + SDK

| 文件 | 动作 | 内容 |
|---|---|---|
| 根 `pom.xml` | 改 | `dependencyManagement` 锁定 `com.alipay.sdk:alipay-sdk-java` 版本（FR-135） |
| `payment-service/pom.xml` | 改 | 引入该依赖（**不写版本**）（FR-135） |
| `.../application/channel/AlipayGateway.java` | **新增** | 端口：`pagePay(...)` / `query(...)` / `refund(...)` / `verifyNotify(...)`（FR-132 / INV-7） |
| `.../infra/config/AlipaySandboxProperties.java` | **新增** | `@ConfigurationProperties("payment.channel.adapters.alipay.sandbox")`；`enabled` 默认 **`false`**；`enabled=true` 时**启动期强校验**必需项非空，缺失 ⇒ **启动失败并列出缺失项**（FR-133 / FR-134） |
| `.../infra/channel/alipay/AlipaySdkGateway.java` | **新增** | SDK 收口实现（签名 / 验签 / 参数组装 / 金额换算 / 错误归一化为 `ChannelResult`）（FR-132 / FR-141） |
| `.../infra/channel/AlipayChannelAdapter.java` | 改 | **双模态分发**；**`MOCK` 分支必须 `super` 委托**（基类 4 件横切行为口径 100% 不变）（FR-130）；`supportsRealMode()=true`，`supportedScenes()` 按真实能力收窄（FR-131） |
| `.../infra/channel/alipay/*` | 改 | `charge` 调 `alipay.trade.page.pay` → `accepted(null, "awaiting buyer", PayCredential.formHtml(...))`（FR-136；⚠️ 实况修正：SDK 返回**自动提交表单 HTML**，`Kind = FORM_HTML` 而非 `REDIRECT_URL`，见 spec §2.5 F1）；`queryStatus` 三态映射（FR-137）；`refund` 同步返回（FR-138） |

**关键约束**：
- 金额换算 **MUST** 用 `BigDecimal.valueOf(amountMinor, 2).toPlainString()`，**禁 `double`/`float`**（FR-139 / INV-1）；
- 沙箱 HTTP 超时独立配置（默认 `10000ms`）且 **MUST < `payment.reliability.timeout`(30s)**（FR-140 / R4）；
- 渠道错误码 **MUST** 在 Adapter 内映射，**MUST NOT** 泄漏到 `application/**`（FR-141 / FR-240）；
- 密钥 **env 注入**，**禁硬编码 / 禁入库 / 禁明文日志**（FR-290 / INV-2）；
- 染色 `SANDBOX` 而 `enabled=false` ⇒ `400`，**不静默回落 mock**（FR-241 / INV-8）。

**验收**：T-A-03 / T-A-13 / T-A-15 / T-A-16（SC-A-03 / SC-A-09 / SC-A-13）。

### 批次 8 —— 回调三段式校验 + 支付宝 notify 端点（**含 B2 / B4**）

| 文件 | 动作 | 内容 |
|---|---|---|
| `.../api/AlipayNotifyController.java` | **新增** | `POST /internal/channels/alipay/notify`，`consumes=application/x-www-form-urlencoded`；验签 → 校验 → 映射 → `PaymentCallbackService.handleCallback` → 返回**恰好纯文本 `success`**（FR-201 / FR-206） |
| `.../api/dto/AlipayNotifyRequest.java` | **新增** | **接收原始 `Map<String,String>`**（保留全部参与签名参数）（FR-202） |
| `.../infra/channel/alipay/...` 验签 | **新增** | 剔除 `sign`/`sign_type` → 参数名排序拼串 → RSA2 `verifyNotify`；失败 → **`403`** 且**不触达** `PaymentCallbackService`（FR-202 / FR-292） |
| 回调语义校验（**两条路径共用**） | **新增** | ② **引用归属**：attempt 已记录引用不一致 ⇒ 拒绝；引用已被其他 `payment_no` 占用 ⇒ 拒绝（FR-212）；③ **金额/币种**：不等 ⇒ 拒绝推进 + 保留原状态 + 指标 + 审计（FR-210 / FR-211 / FR-213） |
| `.../application/PaymentResultProcessor.java` | 改 | `converge` 的 **UNKNOWN 分支补 `channelReference` 回填**（**B4**，FR-153 相邻；C-10 的 `UNKNOWN → ACCEPTED` **不放行**） |
| `.../web/WebConfig.java` | 改（可选） | 注释说明 notify 路径**为何不在** `ChannelCallbackSignatureFilter` 的 urlPatterns 内（FR-201） |
| `.../web/ChannelCallbackSignatureFilter.java` | **不改** | HMAC 占位 `verifySignature` 恒 `true` 保持（ADR-0025 / FR-292 尾注） |
| `payment-service/src/test/.../web/ChannelCallbackSecurityTest.java` | **不改** | 占位断言保持不变（T-R-03） |

**关键约束**：
- **校验链在收敛之前**：任一步失败 ⇒ **不触达** `PaymentCallbackService`，**状态不变**（INV-10 / FR-213）；
- **两条路径同口径**：JSON 回调路径与 notify 路径**校验行为一致**（消除「新端点严、老端点松」）；
- `changed = false` 是**幂等吸收**不是失败 ⇒ 重复回调**仍返回 `success`**，B2 **不得**把重复回调误判为金额不一致而反复拒绝；
- **本期同步处理**；若实测耗时逼近支付宝超时窗口，改为「先应答后异步处理」并记入已知限制（FR-207）；
- 日志只记 `out_trade_no` / `trade_status` / 验签结果 / 是否推进，**禁止打印完整报文**（FR-208 / FR-294）。

**验收**：T-A-14 / T-B2-1~6 / T-B4-1~2（SC-A-14 / SC-B2-01~SC-B2-06 / SC-B4-01~SC-B4-02）。

### 批次 9 —— demo 环境开关

| 文件 | 动作 | 内容 |
|---|---|---|
| `deployment/mock-channel-web/.../static/demo.html` | 改 | 新增环境选择器（本地 mock / 支付宝沙箱），默认本地 mock；解除 `placeOrder(...)` 与 `demoOverrun(...)` 两处 `{channelCode:'MOCK'}` 硬编码（沙箱 → `ALIPAY` + `X-Dye-Tag: SANDBOX`） |
| `deployment/demo/README.md` | 改 | 环境开关说明 + 两条动线预期结果 |
| `deployment/start-all.sh` | 改 | 透传 `PAYMENT_ALIPAY_*` 环境变量 |
| `DemoProxyController` / `cashier.html` | **不动** | 代理已黑名单式逐头透传（S12） |

### 批次 10 —— 测试与门禁收敛

见 [tasks.md](tasks.md) 的 Phase I。要点：

- **全量门禁** `./mvnw -B clean verify -fae` 全绿（含 `deployment/architecture-tests` 新增断言）；
- **零回归证明**：不染色路径下既有支付 / 退款 / 可靠性 / 集成 / E2E 测试**零改动**通过（SC-A-15）；
- **离线可测**：所有沙箱相关单测用 **mock `AlipayGateway` + 固定签名向量**，**不连沙箱、不访问公网**（SC-A-13）；
- **凭证泄漏核查**：全仓 grep 确认无签名 URL / 私钥 / `client_secret` 出现在日志、测试夹具或文档示例中（SC-A-04）。

### 批次 11 —— L0 文档回写（**实现完成后**）

| 文件 | 动作 | 内容 |
|---|---|---|
| `docs/architecture/systems/payment-service.md` | 改 | 补 `### 3.11 渠道内部契约`（消除悬空锚点）；补 §2.5 / §3.2 的模态落库与 notify 端点现状 |
| `docs/architecture/technical-solution.md` | 改 | §2.4 / §3.5 / §4.3 / §5.2 同步 |
| `CHANGELOG.md` | 改 | 置顶本 Feature 条目 |

> ⚠️ **实现前 MUST NOT** 把 `stage-design` 的 `【目标】` 项写入 L0（N21，会造成虚假合规）。

---

## 5. 编号迁移与旧 030 处置（**已完成**）

**本轮动作**（负责人 2026-09-19 裁决）：**删除旧 030 四件套 + 本 Feature 编号 `031` → `030`**。

| 项 | 动作 | 状态 |
|---|---|---|
| 旧 `030-channel-contract-dye-alipay-sandbox`（stage-04，四件套） | **整目录删除**——纯文档、零代码实现；设计已被本 Feature 全量吸收；旧 `channel_mode` 口径与 H2 裁决冲突 | ✅ 已完成 |
| 本 Feature 目录 | `031-channel-contract-sandbox-callback` → `030-channel-contract-sandbox-callback` | ✅ 已完成 |
| 四件套内编号 | 标题、目录名、迁移脚本名（`031-payment-attempt-extra-json.sql` → `030-payment-attempt-extra-json.sql`）、分支前缀（`feature/031-*` → `feature/030-*`）全部改为 `030` | ✅ 已完成 |
| 索引与 ADR | `docs/specs/README.md`、`docs/adr/README.md`、`docs/adr/traceability.md`、`docs/adr/0075`、`docs/adr/0076` 的引用同步 | ✅ 已完成 |

**决策痕迹的三处承载**（删除不等于丢失）：① [ADR-0075](../../../adr/0075-unified-channel-contract.md) / [ADR-0076](../../../adr/0076-traffic-dyeing-and-alipay-sandbox.md)（真正的决策记录）；② spec [§0.3](spec.md#03-编号裁决与旧-030-的处置)；③ git 历史（可完整回溯四份文件）。

**本 Feature 从旧 030 吸收的内容**：FR-001~FR-018 → spec §7.3；INV-1~INV-8 → spec §7.8（INV-9/10 为本 Feature 新增）；
SC-001~SC-016 → 本 Feature 的 [acceptance.md](acceptance.md)（**映射为 `SC-A-01~SC-A-16`**，编号不复用）。
**本 Feature 相对旧 030 的实质变更**：① 模态载体 `channel_mode` 列 → `extra_json.channelMode`（H2）；② 新增 **B1/B2/B4/B7** 四项收口；
③ 新增**回调三段式校验**（旧 030 未覆盖 ②③）；④ 新增 `close` 的**显式非目标**判定（旧 030 未讨论）。

**未决（不阻塞主体实现）**：**后续 Feature 编号是否顺移一位**（H9/H15）——`design-review 附 A` 的门 3 序列原为
031 ledger / 032 recon / 033 test / 034 reliability / 035 observability；渠道 Feature 占用 `030` 后是否整体前移，**待负责人裁决**。

---

## 6. Complexity Tracking（复杂度登记）

| 引入的复杂度 | 为什么必须 | 为什么不选更简单的做法 |
|---|---|---|
| **新依赖 `alipay-sdk-java`（34.3 MB + 传递依赖 fastjson / okhttp / bcprov / dom4j + ≥9 CVE）** | RSA2 签名 / 验签 / 参数排序自研极易出错，出错的表现是「支付成功但平台判失败」这类**资金级事故** | 纯 JDK 自研（`HttpClient` + `SHA256withRSA`）确实零依赖，但把密码学相关代码放进取款路径，风险收益不划算。**折中**：SDK 收口在 `AlipayGateway` 端口后，将来换实现**零扩散**（INV-7 / FR-295）。**风险已由 H3 显式接受** |
| **DB 结构变更（`payment_attempts` 加 `extra_json`）** | 反向三条路径（退款 / 主动查询 / 超时扫描）**没有入口请求**，ThreadLocal 为空，染色值传不进去 | 不加列则只能「反向路径固定走 mock」，沙箱单退款是假的（比不做更危险）。**折中**：**可空 JSON 列**（非破坏性）+ 存量行读 `MOCK` + 不回填 + **写入侧强制 / 读取侧 fail-safe 双保险**（H2 裁决） |
| **JSON 存 JSON（`TEXT` 而非 MySQL 原生 `JSON` 类型）** | 需要承载「未来还会增加的渠道交互扩展字段」而不反复动 schema | 用专用列 ⇒ 每加一个模态/扩展字段就要一次 DDL；用原生 `JSON` 类型 ⇒ H2 测试库方言不一致。**折中**：沿用项目既有 `payload_json` / `matches_json` 的 **`TEXT` 存 JSON** 先例 |
| **染色落 common-core（跨服务）** | demo 页的染色头必须在 **order-service** 被读出、再由它的 Feign 出站写给 payment-service；放 payment 内部则演示链路走不通 | 只改 order + payment 两个服务 ⇒ 重复代码且将来 refund 链路拿不到；只在 payment 内 ⇒ 演示要绕开订单入口。common-core 一处即全栈生效，且 **order-service 零改动** |
| **单 Adapter 双模态（覆写 `charge`）** | 负责人明确「不新建 SandboxChannelAdapter」（N11）；而 ADR-0072 规定「子类只声明身份、横切行为不得覆写」 | 新建 Sandbox Adapter ⇒ 需改渠道码（波及演示脚本 / 前端 / 测试）或让注册表一 code 挂两实现（破坏 ADR-0073 语义）。**折中**：`MOCK` 分支 `super` 委托，基类 4 件横切行为口径 100% 不变（FR-130） |
| **B1 / B7 纳入本 Feature（跨领域缺陷收口）** | B1 是 🔴（**潜在双记账**）；B7 是 🟠（**重复支付的钱可能永久不退**）。二者都是「接真实渠道前必须修」的资金正确性问题 | 单独立项 ⇒ 会与主链**同文件冲突**（B1 与契约扩展同改 `PaymentApplicationService`）。**折中**：**受控纳入**——只修 correctness gap，**不重新设计** Ledger / Refund 模型（spec §2.2 红线） |

---

## 7. 实现期待确认项（开工前须有明确答案）

| # | 待确认 | 推荐 | 阻塞 |
|---|---|---|---|
| Q1 | H17：B7 是否同时加「order 侧退款补偿扫描器」兜底 | 建议**本 Feature 只做方案 A + 指标**（FR-234），扫描器留 034 | FR-234 的完整形态 |
| Q2 | H13：`UNKNOWN → ACCEPTED` 是否放行（或删除 `ACCEPTED`） | 建议**放行**（语义更准，与 `backfillChannelReference` 一致） | 该迁移的实现 |
| Q3 | 批次 0（门 0 文档收口）是否先单独 PR 合并 | 建议**先做**——不依赖任何裁决，且 SC-A-16 依赖它 | SC-A-16 |
| Q4 | ~~旧 030 三份余件如何处置~~ | ✅ **已裁决：整目录删除**（2026-09-19，见 §5） | — |
| Q5 | `notify_url` 是「配置单值」还是「按单动态拼」 | 建议**配置单值**（本期单租户演示），按单拼留作后续 | FR-133 |
| Q6 | 沙箱 `enabled=false` 时是否装配 Gateway Bean | 建议**不装配**（`@ConditionalOnProperty`），避免无密钥时构造失败 | FR-134 |
| Q7 | `ChargeRequest.scene` 缺省（`null`）时是否按渠道推导默认场景 | 建议**不推导**，`null` 表示「未声明」（零回归，FR-110） | FR-110 |
| Q8 | B1 的**存量旧键** posting 处理口径 | 建议**保留为历史事实，不迁移**（FR-222） | 批次 1 文档 |

---

## 8. 交付节奏（建议）

| 序 | 批次 | 分支 | 可独立合并？ |
|---|---|---|---|
| 1 | 批次 0 门 0 文档收口 | 直推 master（`docs:`） | ✅ 独立 |
| 2 | 批次 1 B1 | `fix/030-ledger-idempotency-key` | ✅ 独立 |
| 3 | 批次 2 B7 | `fix/030-refund-inflight-guard` | ✅ 独立 |
| 4 | 批次 3 契约 + 凭证 | `feature/030-channel-contract` | ✅ 独立（零破坏） |
| 5 | 批次 4 染色骨架 | `feature/030-dye` | ✅ 独立 |
| 6 | 批次 5 模态落库 | `feature/030-attempt-extra-json` | ⚠️ 依赖批次 4（读 `DyeContext`） |
| 7 | 批次 6 反向自足 | `feature/030-reverse-path` | ⚠️ 依赖批次 5 |
| 8 | 批次 7 沙箱适配器 | `feature/030-alipay-sandbox` | ⚠️ 依赖批次 3 |
| 9 | 批次 8 回调三段式 | `feature/030-callback-validation` | ⚠️ 依赖批次 3 / 5 / 7 |
| 10 | 批次 9 demo 开关 | `feature/030-demo-env-switch` | ⚠️ 依赖批次 4 / 7 |
| 11 | 批次 10 测试收敛 | 随各批次内联 | — |
| 12 | 批次 11 L0 回写 | `docs/030-l0-sync` | ⚠️ 依赖全部 |

> **MVP 建议**：批次 0 → 1 → 2 → 3 → 4 已构成「契约就位 + 染色可用 + 两个资金缺陷修复」的**可独立交付增量**；
> 批次 5~9 构成「沙箱真调 + 回调闭环」的完整 Feature 增量。
