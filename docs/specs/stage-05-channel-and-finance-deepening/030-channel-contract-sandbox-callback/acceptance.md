# Acceptance: 030 统一渠道契约 + Mock/沙箱双模态 + 回调基础闭环

> **用途**：实现完工时的验收清单。**INV 硬门禁全绿**才可合并；SC 逐条勾选，**不允许「部分完成」**。
> **配套**：[spec.md](spec.md)（v1.1） · [plan.md](plan.md) · [tasks.md](tasks.md)
> **编号与取代**：本 Feature 编号 **`030`**；旧 stage-04 的 `030-channel-contract-dye-alipay-sandbox` 四件套**已于 2026-09-19 整目录删除**（见 [spec §0.3](spec.md#03-编号裁决与旧-030-的处置)）。旧 030 的 INV-1~8 / SC-001~SC-016 **已全部并入本文件**并扩展；**编号映射：旧 030 的 `SC-001~SC-016` → 本文件的 `SC-A-01~SC-A-16`**，编号不复用以避免歧义
> **当前状态**：**已实现并已合入 master（`e348090`）**；本文件为**合并后补做的验收记录**（2026-09-20）。
> 代码与文档实况：`feature/030-channel-contract-sandbox-callback` + `feature/030-demo-sandbox-ui` 均已 `--no-ff` 合入 master。
> **验收结论**：INV-1~INV-10 **全绿**；SC 清单 **除 §3.2 沙箱手工 live 动线外全部通过**；
> 按「不允许部分完成」口径，**本文件不自行宣布「通过」**，签署栏留待负责人（见 §6）。

**证据记号**（本文件新增，便于复核）：
- 🟢 **本会话实跑/静态核对**（2026-09-20，命令与输出可复现）
- 🔵 **由既有测试覆盖**（全量门禁绿；本会话按测试名与结果计数确认，未逐行复核断言内容）
- ⏳ **未完成 / 待人工**（沙箱需真实买家账号与公网 `notify_url`）

---

## 0. 合并前置五条

- [x] `./mvnw -B clean verify -fae` **全绿**（含 `deployment/architecture-tests`）
      —— 🟢 2026-09-20 实跑：**17/17 reactor 条目 BUILD SUCCESS**，**834 tests / 0 failures / 0 errors / 0 skipped**
      （`JAVA_HOME=~/.workbuddy/binaries/java/jdk-21-home ./mvnw -o -B clean verify -fae`，耗时约 5~6 分钟）。
      ⓘ **计数口径**：以 surefire **XML**（`TEST-*.xml` 的 `tests=`）汇总为准 = **834**；其**纯文本摘要**在 3 个类
      （`PaymentCallbackValidationTest` / `ChannelCallbackSecurityTest` / `InternalServiceAuthTest`）上误记为 `Tests run: 0`
      （XML 各为 12 / 6 / 8，耗时 12s 级 ⇒ 确实执行），故按 `.txt` 汇总得 808 系**报告器假象**。
      `deployment/e2e-tests` 需 live 栈、默认跳过，不产报告（属预期）。
- [x] 本文件的 **INV 硬门禁（10 条）+ SC 清单**全部勾选 —— 见 §1 / §2；**唯 §3.2 沙箱手工 live 未完成**（已显式记录）
- [x] **门 0** 完成：D-1~D-6 漂移收口（`payment-service.md` 无悬空锚点、无重复 `### 3.10`、**无「已实现的 `channel_mode` 列」虚假陈述**）
      —— 🟢 代码/schema 复核实况：`channel_mode` 列 0 命中；`extra_json` 三处齐备（§SC-A-11 证据）
- [x] **门 1 / 门 2 已通过**（ADR-0075/0076 🟢 Accepted；H2 = 通用 JSON 列、H3 = 引入 SDK）
- [x] 文档无漂移（`docs/guides/engineering-standards.md` §11 五条 grep 全过）
      —— 🟢 2026-09-20 复核：① ADR 引用无 `>0076` 的非法引用；② 锚点重复仅 `0054`（索引表已备案）；
      ④ `git status` 无根目录产物污染。**另修 2 处实况漂移，见 [spec §2.5](spec.md#25-实现期实况修正2026-09-20两处声明--实况)**（F1 凭证形态、F2 空转门禁）

---

## 1. INV 硬门禁（**任一不过 = 不可合并**）

| INV | 内容 | 验证方式 | 通过 |
|---|---|---|---|
| **INV-1** | **金额纪律**：内部一律 `amountMinor`(long) + `currencyCode`(String)；「元字符串」**只在支付宝适配器内**用 `BigDecimal.valueOf(minor, 2).toPlainString()` 换算；**代码中无 `double` / `float` 参与金额** | ① 全仓 grep 金额换算处的 `double`/`float`；② `AlipayAmountConversionTest` 固定向量 | [x] 🟢 静态核对：`payment-service` 主源仅 3 处命中且全为注释/文案，唯一真实浮点用法是 `counter(..., (double) recycled, ...)`（**指标计数**，非金额）。🔵 `AlipayAmountConversionTest` 绿 |
| **INV-2** | **凭证与密钥不落库、不入 git、不进明文日志**；持久化渠道标识恒为 `payment_attempts.channel_reference` | ① `payment_attempts` **无凭证列**；② grep 日志与测试夹具；③ `application.yml` 只出现 `${ENV}` 占位 | [x] 🟢 `grep -i credential deployment/schema/` = **0 命中**；`AlipaySandboxProperties.toString()` 只报「已配/未配」不输出密钥；密钥仅 env（`PAYMENT_ALIPAY_SANDBOX_*`），根/子 pom 与 yml 无明文私钥 |
| **INV-3** | **染色不参与选路**：`ChannelRouter` MUST NOT 读取 `DyeContext` | ① 代码评审 `ChannelRouter` 实现无 `DyeContext` 引用；② `DyeNotAffectingRoutingTest`；③ ArchUnit | [x] 🟢 `ChannelRouter.java` / `ConfiguredChannelRouter.java` 对 `Dye|dye` **0 命中**；`application/channel/**` 整包 0 命中。🔵 `channelRouterMustNotReadDyeContext`（ArchUnit）与 `DyeNotAffectingRoutingTest` 绿 |
| **INV-4** | **反向路径按记录解析，禁止重新路由**：退款 / 重试 / 主动查询 MUST 用 `payment_attempts.channel_code` 解析；染色 MUST NOT 让反向路径改换渠道 | ① 反向路径无 `ChannelRouter` 调用；② 构造「Router 会选另一渠道」的配置，退款仍回原渠道 | [x] 🔵 `ReversePathDyeModeTest` + `resolveRecordedChannel` 确定性排序（FR-272）绿 |
| **INV-5** | **染色入站读与出站写同批落地**（依据 S17：`InternalToken` 曾因只做入站校验导致**全线 403**） | `git show --stat <commit>` 中 `DyeFilter.java` 与 `DyeRequestInterceptor.java` **同时出现** | [x] 🟢 两文件同批存在于 `common/common-core/.../dye/`（`DyeFilter` / `DyeMode` / `DyeContext` / `DyeRequestInterceptor`），并在同一 Feature 提交中引入。🔵 `DyeFilterTest` / `DyeRequestInterceptorTest` 绿 |
| **INV-6** | `credential != null` ⇒ 渠道**仅受理**、买家未付 ⇒ payment **停 `PROCESSING`**，**MUST NOT** 走成功收敛路径（不记账 / 不通知 order） | 单测 + 集成：沙箱下单后 payment 状态与 ledger / order **无副作用** | [x] 🔵 `AlipaySandboxNotifyScenarioTest#fullSandboxMotionLine`（断言语义：凭证 ≠ 付款完成 ⇒ `PROCESSING`）绿 |
| **INV-7** | `application/**` **MUST NOT** 依赖 SDK 的 **Java 包 `com.alipay.api`**（SDK 收口在 `AlipayGateway` 后；⚠️ 非 Maven 坐标 `com.alipay.sdk`，写错会让门禁空转） | `architecture-tests` ArchUnit 断言（含阳性对照） | [x] 🟢 **本会话修正后为真绿**：规则原写 `com.alipay.sdk..`（**该 Java 包不存在** ⇒ 恒通过、空转假绿），已改为真实包 `com.alipay.api..` 并加**阳性对照**；静态侧全仓仅 `AlipaySdkGateway` 引用 `com.alipay.*`。🔵 `ServiceBoundaryTest#alipaySdkMustBeConfinedToItsInfrastructureAdapter` 绿 |
| **INV-8** | **不静默降级**：非法染色值 / 染色 `SANDBOX` 但真实模式未启用 / 请求渠道未声明的 `PaymentScene` ⇒ **`400 INVALID_ARGUMENT`** | 三条单测 + 三条集成断言（且**均不建单**） | [x] 🔵 `AlipayDualModeTest`（`requireSandboxEnabled` 抛 400 的分支）+ 契约/场景校验测试绿 |
| **INV-9** | **超时 MUST NOT 转 `FAILED`**：`TimeoutScanner` 只写 `UNKNOWN`（宪法 §V.7） | ① `TimeoutScanTest` 保持通过；② 代码评审 `TimeoutScanner` 无 `FAILED` 写入路径 | [x] 🟢 `TimeoutScanner.java` 对 `FAILED|markFailed` **0 命中**，唯一迁移入口是 `payment.markUnknown(TIMEOUT_REASON)`。🔵 `TimeoutScanTest` 绿 |
| **INV-10** | **回调 MUST NOT 在校验失败时推进状态**：验签 / 引用归属 / 金额币种**任一失败** ⇒ 拒绝且**原状态不变** | 四类失败各自断言：不推进 + 指标 + 审计 | [x] 🔵 `AlipayNotifyValidationTest` + `PaymentCallbackValidationTest`（三件套断言，含 `RecordingObservability` 记录式指标/审计替身）绿 |

---

## 2. SC 验收清单

### 2.1 A 类 —— 契约与凭证

- [x] **SC-A-01（全绿门禁）** `./mvnw clean verify -fae` 全绿（含 `architecture-tests` 边界门禁）—— 🟢 17/17 SUCCESS，834 tests / 0 fail
- [x] **SC-A-02（契约兼容）** `PaymentScene` / `Goods` / `CallbackUrls` / `Payer` / `PayCredential` 就位；三个请求 record 扩展后**保留兼容构造器**；**既有 4 处构造点 + 6 处测试桩 + 21 个 `ChannelResult` 引用文件编译零改动** —— 🟢 静态：`ChargeRequest`(12) / `RefundRequest`(9) / `QueryStatusRequest`(4) 与 `PaymentScene` / `PayCredential` 均已就位；全量编译零改动通过（兼容构造器生效）。🔵 `ChannelContractCompatTest` 绿
- [x] **SC-A-03（金额纪律）** `amountMinor=1 → "0.01"`；`amountMinor=100000000 → "1000000.00"`；代码中**无 `double`/`float`** 参与金额 —— 🔵 `AlipayAmountConversionTest` 固定向量绿；🟢 见 INV-1 静态核对
- [x] **SC-A-04（凭证）** 沙箱下单 `payUrl` 为**签名的自动提交表单 HTML**（`Kind = FORM_HTML`；**不是 URL**，见 spec §2.5 F1）；`payment_attempts` **无凭证列**；日志**无**完整签名 URL / 私钥 / `client_secret` —— 🟢 无凭证列 + 密钥不打印（同 INV-2）；🔵 `AlipaySandboxChargeTest` / `AlipayDualModeTest`（本会话已把断言由 `REDIRECT_URL` 更正为 `FORM_HTML`）绿
- [x] **SC-A-05（凭证即待付款）** `credential != null` 时**不记账、不通知 order**；payment 停 `PROCESSING`；**notify 到达后**才收敛 `SUCCEEDED` —— 🔵 `AlipaySandboxNotifyScenarioTest` 绿

### 2.2 A 类 —— 染色

- [x] **SC-A-06（染色透传 + 模态落库）** 带 `X-Dye-Tag: SANDBOX` 从 demo 页经 `/proxy` → order → payment ⇒ **`extra_json.channelMode = 'SANDBOX'`** 且**新写入行必含该键**；不带头 ⇒ `'MOCK'`；响应头回写 `X-Dye-Tag`；MDC 有 `dyeMode` —— 🔵 `PaymentAttemptExtraJsonTest`（落库往返：键名两侧不漂移）绿；🟢 `MybatisPaymentAttemptRepository` 写入侧强制
- [x] **SC-A-07（fail fast）** 非法染色值 → `400` + 指标；染色 `SANDBOX` 而 `enabled=false` → `400`；**二者均不建单**（`payments` 无新增行） —— 🔵 `AlipayDualModeTest`（`enabled=false` ⇒ 400 且不回落）+ 染色解析测试绿
- [x] **SC-A-08（染色不入路由）** 同一 `RouteContext` 在两种染色下选出**同一** `channelCode` —— 🔵 `DyeNotAffectingRoutingTest` 绿
- [x] **SC-A-09（SDK 收口）** ArchUnit 通过：`application/**` 不依赖 SDK Java 包 `com.alipay.api` —— 🟢 **规则已修正**（原包名 `com.alipay.sdk` 空转，见 spec §2.5 F2）；🔵 `ServiceBoundaryTest` 10 tests 绿
- [x] **SC-A-10（入站出站同批）** `DyeFilter` 与 `DyeRequestInterceptor` **同批存在**并通过测试 —— 🟢 同 INV-5 证据

### 2.3 A 类 —— 模态落库与反向还原

- [x] **SC-A-11（模态落库）** 新列 **`extra_json`** **三处 schema 齐备**（建表语句 + 增量迁移 `030-payment-attempt-extra-json.sql` + 测试 H2 schema）；**全新库**与**存量库 + 迁移脚本**两条路径均可用；存量行（`NULL`）读为 `'MOCK'`；**非法 JSON / 缺 `channelMode` 键 / 值非法 ⇒ fail-safe `'MOCK'`**；迁移脚本**可重复执行** —— 🟢 三处实测齐备：`deployment/schema/03-payment-schema.sql:58`、`deployment/schema/030-payment-attempt-extra-json.sql`、`payment-service/src/test/resources/schema.sql:47`；迁移脚本用 `information_schema` 存在性判断 ⇒ **可重复执行**。🔵 `PaymentAttemptExtraJsonTest` + `PaymentAttemptChannelModeTest`（四类坏数据）绿
- [x] **SC-A-12（反向还原）** 沙箱单退款 / 主动查询：渠道调用在**正确模态**包裹内，退款 attempt 模态正确，**渠道未被重新路由**；`resolveRecordedChannel` 有**确定性排序**且同时解析 `channel_code` 与 `channelMode` —— 🔵 `ReversePathDyeModeTest` 绿

### 2.4 A 类 —— 沙箱协议与回调

- [x] **SC-A-13（离线可测）** 新依赖可下载；签名 / 验签 / 参数排序用**固定向量**单测钉死；**全程不连沙箱、不访问公网** —— 🔵 沙箱相关测试全部用桩网关（`StubGateway` / `ScriptedGateway`），门禁在离线模式 `-o` 下通过 ⇒ 无公网依赖
- [x] **SC-A-14（notify 端点）** 合法通知收敛并**恰好返回纯文本 `success`**（无引号 / 无换行 / 无 JSON 包装）；验签失败 `403` 且**不触达**收敛服务；`WAIT_BUYER_PAY` **不推进** —— 🔵 `AlipayNotifyControllerTest` / `AlipayNotifyValidationTest` 绿；🟢 公网侧 403 由 live 动线验证（§3.2）
- [x] **SC-A-15（零回归）** 不染色路径既有测试**零改动**通过 —— 🟢 全量 834 tests 绿；本会话改动的测试仅限「把误标的凭证 Kind 更正为 `FORM_HTML`」（授权修正，证据链见 §2.5 F1）
- [x] **SC-A-16（文档一致）** `payment-service.md §3.11` 与代码一致；`adr/README.md` 两张表 + `traceability.md` 已登记；**链接与锚点自检 0 断链** —— 🟢 §3.11 / §3.11.1 已回写；ADR-0075/0076 已登记进索引表 + 编号速查表（`0001–0076`，下一可用 **ADR-0077**）+ `traceability.md`

### 2.5 B 类 —— B1 账本幂等键（🔴）

- [x] **SC-B1-01** 同步路径 posting 键 **== `PAYMENT:{paymentNo}`** —— 🟢 `PaymentApplicationService` 只传 `paymentNo`；前缀拼接**只留** `FeignLedgerPostingGateway:42` 一处。🔵 `PaymentCaptureLedgerPostingTest#syncPathPassesBarePaymentNoNotIdempotencyKey` 绿
- [x] **SC-B1-02** 回调路径 posting 键 **== 同一值**（与同步路径**同一键**）—— 🟢 `PaymentResultProcessor` 已去掉手工 `"PAYMENT:"` 前缀。🔵 `#bothSuccessPathsProduceTheSamePostingKey` 绿
- [x] **SC-B1-03** 同一支付单先同步成功再收到回调 ⇒ 账本分录数 **= 1**（不是 2）—— 🔵 `#syncSuccessThenCallbackProducesExactlyOnePosting` 绿（H2）；真库 `LedgerPostingConcurrencyTest#sequentialSyncThenCallbackPostingIsSingle` 亦绿
- [x] **SC-B1-04** 并发两条路径同时记账（**需真库**）⇒ 唯一约束吸收，分录数 **= 1** —— 🟢🟢 **真库实测通过**：`LedgerPostingConcurrencyTest`（Testcontainers-MySQL 8.0，`uk_postings_idempotency_key`）**3 tests / 0 fail / 0 skip / 25.7s**——本机 Docker 守护进程可用（29.7.2），容器真实启动，8 线程并发同键 INSERT ⇒ 恰好 1 个赢家、7 个被吸收。**L1 限制对 B1 已解除**
- [x] **补充验收 · FR-220 / FR-222** 前缀拼接**只留一处**（`FeignLedgerPostingGateway`）；存量旧键 posting **保留为历史事实、不迁移** —— 🟢 全仓 `"PAYMENT:"` 命中 3 处，其中 **2 处为注释**，唯一代码拼接点即 `FeignLedgerPostingGateway:42`

### 2.6 B 类 —— B2 回调语义校验（🟠）

- [x] **SC-B2-01** 金额不符回调 ⇒ **拒绝推进** + 原状态不变 + 指标 + 审计 —— 🔵 `PaymentCallbackValidationTest` 绿
- [x] **SC-B2-02** 币种不符回调 ⇒ 同上 —— 🔵 同上
- [x] **SC-B2-03** 引用**串号**（`channelReference` 已被其他 `payment_no` 占用）⇒ 拒绝推进 + 指标 + 审计 —— 🔵 同上
- [x] **SC-B2-04** 引用与 attempt 已记录值**冲突** ⇒ 拒绝；attempt 值为**空**则**回填** —— 🔵 同上（B4 联动）
- [x] **SC-B2-05** **重复回调不被误拒**：同结果重复 ⇒ 仍**幂等吸收**（不因 B2 反复拒绝） —— 🔵 `PaymentCallbackValidationTest` / `PaymentCaptureLedgerPostingTest#duplicateSuccessCallbackDoesNotRepostLedger` 绿
- [x] **SC-B2-06** **两条路径同口径**：JSON 路径与 notify 路径的校验行为**一致** —— 🔵 `PaymentCallbackPathParityTest` 绿

### 2.7 B 类 —— B4 `channelReference` 回填（🟠）

- [x] **SC-B4-01** `accepted(ref)` 的 ref 在 `converge` 的 **UNKNOWN 分支落库** —— 🔵 回调收敛测试绿
- [x] **SC-B4-02** 异步受理后 `channel_reference` **非空**（受理流水号不丢） —— 🔵 同上（`ChannelAttemptRecorderImpl` 已补回填）

### 2.8 B 类 —— B7 在途守卫（🟠）

- [x] **SC-B7-01** TXRF=`REQUESTED` + 渠道调用失败 ⇒ 重试时渠道请求次数 **= 2**（首次 + 重试），**不是 1** —— 🔵 `TransactionRefundTest`（断言已由 `hasSize(1)` **更正为 2**，证据链见 spec §16 与 CHANGELOG）绿
- [x] **SC-B7-02** TXRF=`PROCESSING`（渠道已受理）⇒ 重试时渠道请求次数 **= 1**（正确回放，不重复调） —— 🔵 `TransactionRefundTest` 绿
- [x] **SC-B7-03** 并发两次同参 surplus 退款 ⇒ 最终**只产生一个** TXRF + 一个 PMRF（三层防线） —— 🔵 `TransactionRefundTest` SC-B7-03 用例绿（H2；真库并发属 033 / H16 范围）
- [x] **SC-B7-04** 渠道调用持续失败至重试耗尽 ⇒ **指标 + ERROR 日志可观测**（不静默） —— 🔵 可观测断言绿
- [x] **SC-B7-05** TXRF 停留 `REQUESTED` 超阈值 ⇒ **指标**可发现（告警规则属后续 Feature） —— 🔵 指标断言绿

### 2.9 B 类 —— 跨项（既有行为零变化）

- [x] **SC-B-06** **既有 surplus 行为零变化**：surplus TXRF **不累加** `refunded_minor`、**不改**订单状态、**不终止**履约（回归 `TransactionRefundTest.surplusRefundClosesWithoutBooking` 保持通过） —— 🔵 绿
- [x] **补充验收 · FR-233** `RefundPolicy` 累计上限（同支付单累计申请额 ≤ 已支付额）**仍为最后防线**，未被 B7 改动绕过 —— 🔵 `RefundPolicy` 边界单测（T17，含 `>` vs `>=` 比较符）绿

---

## 3. 演示验收

### 3.1 本地 mock 动线（默认，**可复现，进 CI**）

- [x] demo 页默认「本地 mock」下单 ⇒ `payUrl` 指向本地 mock 收银台；`extra_json.channelMode = 'MOCK'` —— 🟢 缺省染色即 `MOCK`；🔵 沙箱桩测试反向确认「不带头 ⇒ MOCK」
- [x] 不带头 / 带头为 `MOCK` ⇒ 行为**完全一致**（缺省即 MOCK） —— 🔵 `AlipayDualModeTest`（MOCK 分支零分叉）绿
- [x] 金额尾号 `11` ⇒ 仍 `timeout` ⇒ payment 进 `UNKNOWN`（**不是 `FAILED`**）⇒ 主动查询收敛 —— 🔵 `AlipaySandboxChargeTest`（尾号 11 在沙箱分支不触发 mock 注入，在 mock 分支仍注入）+ 可靠性测试绿
- [x] 既有支付 / 退款 / 可靠性测试**零改动**通过 —— 🟢 全量 834 tests 绿

### 3.2 支付宝沙箱动线（**手工 live，不进 CI**）

前置：配置 `PAYMENT_ALIPAY_*` 环境变量；内网穿透暴露 `notify_url`；置 `payment.channel.adapters.alipay.sandbox.enabled=true`。

- [x] demo 页选「支付宝沙箱」⇒ 请求带 `X-Dye-Tag: SANDBOX` ⇒ order → payment 全链路透传
      —— 🟢 live 已验证（2026-09-20 联调：`X-Dye-Tag` 经 Feign 出站拦截器传播；此前的「order 容器内 common-core 无 Dye 类」问题已由重建镜像修复）
- [x] payment 侧 `channel_code = ALIPAY`、`extra_json.channelMode = 'SANDBOX'`
      —— 🟢 live 已验证（建单实况：支付单 `PROCESSING`，模态落库 `SANDBOX`）
- [ ] `window.open(payUrl)` 打开**支付宝沙箱收银台**（不再跳本地 mock 收银台）
      —— ⏳ **半完成**：凭证形态已修（`FORM_HTML` + `demo.html` Blob 包装 + `/cashier/return` 回跳页），
      但「打开后确实呈现沙箱收银台而非空白页」**尚未由买家账号确认** ⇒ 见下方「未验证范围」
- [ ] 沙箱买家账号付款 ⇒ notify 到达 ⇒ payment 收敛 `SUCCEEDED`；`payment_attempts.channel_reference = trade_no` —— ⏳ **未完成**（需真实买家账号付款）
- [ ] ledger 有 `PAYMENT:{paymentNo}` 分录（**恰好一条**） —— ⏳ **未完成**（依赖上一条收敛）
- [ ] 退款：沙箱单退款走**沙箱分支**（模态从库还原），渠道**未被重新路由** —— ⏳ **未完成**（需先有一笔沙箱成功支付）

**已 live 验证的相邻项（记录在案）**：公网回调端点 `POST /internal/channels/alipay/notify` 空体 ⇒ **403**（验签 fail-closed，ADR-0052 生效）。

> ⚠️ **未验证范围 MUST 显式记录**（ADR-0076 R10）——不得默认通过。
> **本轮明确未验证**：① 沙箱收银台渲染结果；② 买家付款 → notify → `SUCCEEDED` 端到端收敛；
> ③ 沙箱退款回归原模态。**前置阻塞**：`notify_url` 需公网可达（联调用的 ngrok 隧道已失效），
> 且需支付宝沙箱**买家账号**手工付款——两者均非 CI 可覆盖，属人工动线。

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
| 9 | **二维码 / 表单 / JSAPI 参数的端到端承载** | N13 —— 需改 `common-dto`，属 API 变更（本期凭证**类型化**已就位，但 `payUrl` 仍是字符串通道） |
| 10 | **HMAC 真实验签** | ADR-0025 决议：占位**保持不变**（保留给本地 mock 路径） |
| 11 | **新增 MQ / 新中间件 / 新微服务 / 分布式事务** | N5 / N6 / N8 |
| 12 | **任何「超时即失败」的自动终态化** | N16 / INV-9 |
| 13 | **「禁止多 Payment SUCCESS」「换渠道前关闭旧 Payment」** | N17 / N18 / N19 —— **会破坏当前正确的 surplus 行为** |

---

## 5. 已知限制确认（实现完须逐条核对**仍成立**）

| # | 限制 | 实现后核对 |
|---|---|---|
| L1 | **H2 无法验证真并发与唯一约束触发路径** ⇒ SC-B1-04 / SC-B7-03 在 H2 上**可能假绿** | [x] **B1 已解除**：`LedgerPostingConcurrencyTest` 走 **Testcontainers-MySQL 真库**，本机 Docker 可用（29.7.2）时真实执行（3 tests / 0 skip / 25.7s）；无 Docker 时 `assumeTrue` 整体 skip 不 fail。**B7-03 仍为 H2**，真库并发留 033 / H16 |
| L2 | **沙箱不可复现、需密钥 + 公网 `notify_url`** ⇒ 真机验证为**手工** | [x] 未验证范围已**显式记录**（ADR-0076 R10 + 本文件 §3.2） |
| L3 | **`RefundPolicy` 比较符此前未逐行审查**（`>` vs `>=`） | [x] 已补边界单测（T17） |
| L4 | **支付宝退款是否有独立 `refund_notify_url`** 未确认 | [x] 已复核；**只影响适配器内部映射，不影响内部契约**（`AlipayTradeRefundRequest` 用同步返回，未设独立通知地址） |
| L5 | **`PaymentAttemptStatus.ACCEPTED` 无路径能停留**（H13 未裁决） | [x] 未放行 `UNKNOWN → ACCEPTED`；该态仍为**事实上的预留态** |
| L6 | **`notify` 端点为同步处理**（FR-207） | [x] 实测耗时**未**逼近支付宝超时窗口（回调处理为单次本地事务 + 无外部调用）；仍保持同步实现 |
| L7 | **`sandbox.enabled=false` 时沙箱能力不可用** | [x] 无密钥时服务**照常启动**、CI 可跑（全量门禁在 `-o` 离线模式绿）；染色 `SANDBOX` 明确 `400`（不静默回落） |
| L8 | **`extra_json` 为可空 TEXT**（R17） | [x] 写入侧强制 + 读取侧 fail-safe 双保险已落地（`AttemptExtraCodec` 双向 + `PaymentAttempt.getChannelMode()` fail-safe）；坏数据行**未**导致误连真实渠道（四类坏数据单测绿） |
| **L9（本会话新增）** | **包名匹配型架构门禁存在「空转假绿」风险**：`resideInAPackage(...)` 写错包名不会编译报错，只会恒通过 | [x] INV-7 已修（真实包 + 阳性对照）；`everyServiceMustActuallyBeImported` 仅防「0 类导入」，**不防包名笔误**——其余 `resideInAPackage` 用法（`com.payment.common.core.dye..` 等）建议后续统一加阳性对照 |
| **L10（本会话新增）** | **凭证形态曾被误标**（`FORM_HTML` → `REDIRECT_URL`），使 `isRedirectFamily()` 对被误标凭证返回 `true` | [x] 已修（`PayCredential.formHtml(...)` + 适配器改用 + 3 处测试断言更正）；**契约纪律**：形态判据恒用 `Kind`，不得嗅探 payload 首字符 |

---

## 6. 总体结论

- [x] **INV-1~INV-10 全绿** —— 见 §1（其中 INV-7 为**修正后**的真绿）
- [ ] **SC 清单全部勾选** —— **除 §3.2 三条沙箱手工 live 项外全部勾选**（按「不允许部分完成」口径，本项**保持未勾**）
- [x] **明确不验收项未被误做** —— 见 §4，13 项均未被实现（无 `close` / 无 `UNKNOWN→ACCEPTED` / 无新中间件 / 无「超时即失败」）
- [x] 实现期偏离（改 `common-dto`、新增 `AWAITING_PAYMENT`、契约加 `close`、引入 MQ）—— **均未发生**；
      本会话新增的 2 处修正（F1 凭证形态、F2 门禁包名）**不属人类决策边界**（不触领域模型 / 状态机 / 服务边界 / DB 结构 / 公共 API），
      已按「发现即报告 + 证据链」处置并记录于 [spec §2.5](spec.md#25-实现期实况修正2026-09-20两处声明--实况) 与 CHANGELOG

**结论**：⬜ 通过 / ⬜ 不通过（**不允许「部分通过」**）
—— **建议**：代码与自动化验收层面 **满足合并条件**（已合并 `e348090`）；但 §3.2 沙箱手工 live 三条未完成，
按本文件自身口径**不应勾「通过」**。建议二选一：① 由负责人完成沙箱买家账号动线后勾「通过」；
② 将 §3.2 三条**显式移入 034 / 后续 Feature**（与 §4 第 5 项同源），再勾「通过」。

**签署**：______ 日期：______
