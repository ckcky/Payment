# Plan: 030-channel-contract-dye-alipay-sandbox（实现计划）

**配套**：[spec.md](spec.md) · [tasks.md](tasks.md) · [acceptance.md](acceptance.md)
**决策**：[ADR-0075](../../adr/0075-unified-channel-contract.md)（🟡 Proposed）· [ADR-0076](../../adr/0076-traffic-dyeing-and-alipay-sandbox.md)（🟡 Proposed）
**本轮状态**：**只写文档，不写代码**。本文件是可执行的实现计划，待两份 ADR 转 Accepted 后按批次开工。

## 1. Summary（技术路径）

三件事一条链：**契约**（装得下四家）→ **凭证**（把用户带去渠道付款页）→ **染色**（决定走 mock 还是真实协议）。

1. **契约层**：在 `application/channel/` 内补齐 `PaymentScene` / `Goods` / `CallbackUrls` / `Payer` / `PayCredential`，
   扩展现有三个请求 record 并**保留兼容构造器**；`ChannelResult` 加**可空** `credential`；
   `PaymentChannel` 加两个 `default` 方法。**所有变更向后兼容**——6 处测试桩、4 处构造点、21 个引用 `ChannelResult`
   的测试文件**编译零改动**。
2. **凭证出参**：`credential.payload()` 经 `RoutedPayment` 填进**现有** `CreatePaymentResponse.payUrl`，
   `common-dto` 与 order-service 零改动。`credential != null` 即「渠道已受理待付款」，payment 停 `PROCESSING`。
3. **染色**：`common-core` 加 `dye` 三段式（`DyeMode` / `DyeContext` / `DyeFilter` / `DyeRequestInterceptor`），
   入站读 + 出站写**同批落地**；demo 页选环境 → 头随链路到达适配器。
4. **模态落库**：`payment_attempts` 加 `channel_mode`，反向三条路径据它还原（调度器无请求上下文，落库是唯一闭环）。
5. **沙箱适配器**：`AlipayChannelAdapter` 双模态（`MOCK` 分支 `super` 委托），协议收口在 `AlipayGateway` 端口后；
   新增 RSA2 notify 端点，复用现有收敛链路。

## 2. Technical Context

| 项 | 值 |
|---|---|
| 语言 / 运行时 | Java 21；Spring Boot 3.5.x；Maven 多模块（16 模块） |
| 变更服务 | **payment-service**（主体）、**common-core**（染色骨架）、**mock-channel-web**（仅演示页静态资源） |
| **零改动服务** | **order-service**（染色靠 common-core 自动覆盖）、其余 7 个领域服务、`common-dto` |
| 构建 / 测试 | `./mvnw -B verify`；单元 + 集成用 H2（MySQL 兼容模式）；E2E 默认 skipTests |
| 新依赖 | `com.alipay.sdk:alipay-sdk-java`（版本在**根 pom `dependencyManagement`** 锁定，禁止子模块写死版本） |
| DB 变更 | `payment_attempts` 加 `channel_mode VARCHAR(16) NOT NULL DEFAULT 'MOCK'`（**非破坏性**：带默认值的新增列） |
| 新增 HTTP 端点 | `POST /internal/channels/alipay/notify`（payment-service） |
| 新增配置 | `payment.channel.adapters.alipay.sandbox.*` |
| 外部依赖 | 支付宝沙箱网关（**仅手工 live 验证，不进 CI**）、公网可达的 `notify_url`（本地需内网穿透） |
| 未知项（实现期复核） | ① 支付宝退款是否有独立 `refund_notify_url`；② 抖音两套下单体系的取舍（本 Spec 以 DouyinPay 为准） |

## 3. Constitution Check（GATE）

| # | 检查项 | 结论 | 说明 |
|---|---|---|---|
| C1 | 跨领域改数据？ | ⚠️ **是（人类决策边界）** | `payment_attempts` 加列属 **Database Schema Migration**。**已由负责人确认口径**（D1）：带默认值的新增列、非破坏性、且**建表 + 增量迁移两件齐备**（`CREATE TABLE IF NOT EXISTS` 不会给存量库补列）。 |
| C2 | 核心领域依赖具体渠道实现？ | ✅ **否** | `application/**` 仍只依赖 `PaymentChannel` / `AlipayGateway` 端口；SDK 收口在 `infra/channel/alipay/`，**ArchUnit 断言**（INV-7 / SC-009）。 |
| C3 | 新增微服务 / 中间件？ | ✅ **否** | 无新服务、无新中间件、不引入 MQ、不引入配置中心。 |
| C4 | 新增第三方依赖？ | ⚠️ **是（人类决策边界）** | `alipay-sdk-java`。**已由负责人确认**（D2），风险（34MB jar / 传递依赖 / 已知 CVE）**显式接受并记入 ADR-0076**（L5）；工程规范 §9「新依赖 MUST 有理由」由 ADR-0076 满足。 |
| C5 | 改领域模型 / 状态机 / 服务边界？ | ✅ **否** | 状态机不动（待付款复用 `PROCESSING`，D7）；两层边界不动（ADR-0072 结构条款全部保持，仅「子类只声明身份」被修订为「ALIPAY 双模态 + `MOCK` 分支 `super` 委托」）；服务边界不动。 |
| C6 | 改公共 API / 破坏性变更？ | ✅ **否** | 三个 record 是**服务内契约**（非跨服务 RPC），且**保留兼容构造器**；`CreatePaymentResponse` 与所有 common-dto **不动**（D6），order-service **零改动**。 |
| C7 | 安全策略变更？ | ⚠️ **是（人类决策边界）** | 新增 RSA2 验签与密钥管理。**已由负责人确认**（D2/D11）；口径为 ADR-0025/0026 的自然延伸（密钥 env 注入、禁入库禁日志），**不改 HMAC 占位**（保留给 mock 路径）。 |
| C8 | 生产部署策略变更？ | ✅ **否** | 靠 `sandbox.enabled=false` 默认关闸；无密钥时服务照常启动、CI 可跑。 |

**门禁结论**：本计划**不引入新的未确认人类决策边界**——C1 / C4 / C7 三条均已有负责人明确口径（D1 / D2 / D11）。
实现期若出现任何偏离（如改为改 `common-dto`、改为新增 `AWAITING_PAYMENT` 状态、改为引入 MQ），**MUST 先回负责人确认**。

**实现期四条禁止**（越界即回退）：

1. 禁止把渠道私有参数加进通用字段（走 `channelExtra`）；
2. 禁止用 `double` / `float` 参与任何金额换算（INV-1）；
3. 禁止让染色参与选路、或让反向路径重新选渠道（INV-3 / INV-4）；
4. 禁止只做染色的入站读而漏做出站写（INV-5，先例 S17 曾导致全线 403）。

## 4. Project Structure（批次与文件级改动）

### 批次 A —— 统一渠道契约（`application/channel/`）

| 文件 | 动作 | 内容 |
|---|---|---|
| `PaymentScene.java` | **新增** | 枚举 `WEB / H5 / NATIVE / JSAPI / MINI_PROGRAM / APP` + 四家映射对照 Javadoc |
| `Goods.java` | **新增** | `(String title, String description)` + `of(title)` |
| `CallbackUrls.java` | **新增** | `(String notifyUrl, String returnUrl)` + `notifyOnly(url)`；Javadoc 声明 `returnUrl` 不承载资金事实 |
| `Payer.java` | **新增** | `(String payerId, String clientIp)` + `of(payerId)` / `byClientIp(ip)` |
| `PayCredential.java` | **新增** | `(Kind kind, String payload, Instant expiresAt)`；`Kind` 六值；`redirectUrl(url, expiresAt)` / `isRedirectFamily()` |
| `ChargeRequest.java` | 改 | 扩为 12 字段（含 `scene` / `goods` / `callbackUrls` / `expireAt` / `payer` / `attach` / `channelExtra`）+ **保留 5 参构造器** |
| `RefundRequest.java` | 改 | 扩为 9 字段（含 `channelTransactionId` / `outRequestNo` / `reason` / `refundNotifyUrl`）+ **保留 5 参构造器** |
| `QueryStatusRequest.java` | 改 | 扩为 4 字段（含 `channelTransactionId`）+ **保留 3 参构造器** |
| `ChannelResult.java` | 改 | 加可空 `credential`；加 `accepted(ref, reason, credential)`；旧工厂委托 `null`；`withReason` 保留 `credential`；保留 5 参构造重载 |
| `PaymentChannel.java` | 改 | 加 `default Set<PaymentScene> supportedScenes()`、`default boolean supportsRealMode()` |

**契约定义以 [payment-service.md §3.11](../../architecture/systems/payment-service.md#311-渠道内部契约spec-030--adr-0075) 为权威**，
实现时逐字段对照，不得自行增删。

### 批次 B —— 凭证透传到 payUrl

| 文件 | 动作 | 内容 |
|---|---|---|
| `application/PaymentRetryService.java` | 改 | 重试结果携带 `credential`（取最后一次 `charge` 的） |
| `application/PaymentApplicationService.java` | 改 | `RoutedPayment(payment, channelCode, credential)`；`credential != null` ⇒ **不调 `applyAndPersist`**（INV-6）；建 `ChargeRequest` 时填充 `scene` / `goods` / `callbackUrls` / `expireAt`；调 `charge` 前做**场景校验**（FR-010） |
| `api/PaymentController.java` | 改 | `payUrl` 优先取 `credential.payload()`；`defer` 公式改 `mockCashier.isEnabled() && !DyeContext.isSandbox()`（FR-057）；`buildPayUrl` 保留为 mock 分支 |
| `common/common-dto/**` | **不动** | ——（D6 硬约束） |

### 批次 C —— common-core 染色骨架

| 文件 | 动作 | 内容 |
|---|---|---|
| `common-core/.../dye/DyeMode.java` | **新增** | `MOCK` / `SANDBOX` + 大小写不敏感严格 `parse`（非法值抛异常） |
| `common-core/.../dye/DyeContext.java` | **新增** | ThreadLocal：`isSandbox()` / `current()` / `set` / `clear` / `runWith(mode, Runnable)` |
| `common-core/.../dye/DyeFilter.java` | **新增** | `OncePerRequestFilter`：读 `X-Dye-Tag` → 解析 → ThreadLocal + MDC(`dyeMode`) → 响应头回写 → finally 清理；非法值 400 + 指标 |
| `common-core/.../dye/DyeRequestInterceptor.java` | **新增** | Feign `RequestInterceptor`：非空才写出站头 |
| `common-core/.../config/CommonCoreAutoConfiguration.java` | 改 | 加 `dyeFilter` + `dyeFilterRegistration(order = -190)` |
| `common-core/.../config/FeignTraceAutoConfiguration.java` | 改 | 加 `dyeRequestInterceptor`（`@ConditionalOnMissingBean`） |
| `META-INF/spring/...AutoConfiguration.imports` | **不动** | 两个自动配置类已注册，新增的是其中的 Bean |

> ⚠️ **C 批次必须整体一次落地（INV-5）**：`DyeFilter` 与 `DyeRequestInterceptor` 拆成两次提交，会在
> 「入站已拦截非法值、出站却还没传」或反之的窗口里造成服务间行为不一致。

### 批次 D —— 模态落库

| 文件 | 动作 | 内容 |
|---|---|---|
| `deployment/schema/03-payment-schema.sql` | 改 | `payment_attempts` 建表语句补 `channel_mode` 列 |
| `deployment/schema/030-payment-attempt-channel-mode.sql` | **新增** | `ALTER TABLE payment_attempts ADD COLUMN channel_mode ...`（存量库迁移） |
| `payment-service/src/test/resources/schema.sql` | 改 | H2 测试 schema 同步 |
| `domain/PaymentAttempt.java` | 改 | 加 `channelMode` 字段 + 工厂 / `rehydrate` 同步 |
| `infra/persistence/ChannelAttemptRecorderImpl.java` | 改 | 创建 attempt 时读 `DyeContext`（空 → `MOCK`）落库；**方法签名不变** |
| `infra/InMemoryPaymentAttemptRepository.java`（测试） | 改 | 同步字段 |

### 批次 E —— 反向路径模态还原

| 文件 | 动作 | 内容 |
|---|---|---|
| `application/PaymentRefundService.java` | 改 | 退款前 `DyeContext.runWith(attempt.getChannelMode(), ...)` 包裹渠道调用 |
| `application/reliability/ChannelQueryService.java` | 改 | 主动查询同上（覆盖 `TimeoutScanScheduler` 路径） |

### 批次 F —— 支付宝沙箱适配器与 Gateway

| 文件 | 动作 | 内容 |
|---|---|---|
| 根 `pom.xml` | 改 | `dependencyManagement` 锁定 `com.alipay.sdk:alipay-sdk-java` 版本 |
| `payment-service/pom.xml` | 改 | 引入该依赖（**不写版本**） |
| `application/channel/AlipayGateway.java` | **新增** | 端口：`pagePay(...)` / `query(...)` / `refund(...)` / `verifyNotify(...)` |
| `infra/channel/alipay/AlipaySdkGateway.java` | **新增** | SDK 适配实现（签名 / 验签 / 参数组装 / 金额换算 / 错误归一化为 `ChannelResult`） |
| `infra/config/AlipaySandboxProperties.java` | **新增** | `@ConfigurationProperties("payment.channel.adapters.alipay.sandbox")` + `enabled=true` 时启动强校验 |
| `infra/channel/AlipayChannelAdapter.java` | 改 | 双模态分发；`MOCK` 分支 `super` 委托；覆写 `supportedScenes()`（收窄）与 `supportsRealMode()=true` |

### 批次 G —— 支付宝通知入站

| 文件 | 动作 | 内容 |
|---|---|---|
| `api/AlipayNotifyController.java` | **新增** | `POST /internal/channels/alipay/notify`（form-urlencoded，`consumes` 显式声明）；验签 → 校验 → 映射 → `PaymentCallbackService.handleCallback` → 返回**纯文本 `success`** |
| `api/dto/AlipayNotifyRequest.java` | **新增** | 通知报文 DTO（**必须接收原始 `Map<String,String>`** 以保留全部参与签名参数） |
| `web/WebConfig.java` | 改（可选） | 对 notify 路径留痕（注释说明为何不在 `ChannelCallbackSignatureFilter` 的 urlPatterns 内） |
| `test/.../web/ChannelCallbackSecurityTest.java` | **不改** | HMAC 占位断言保持（FR-070） |

### 批次 H —— demo 环境开关

| 文件 | 动作 | 内容 |
|---|---|---|
| `deployment/mock-channel-web/.../static/demo.html` | 改 | 环境选择器 + 解除 `channelCode:'MOCK'` 硬编码 + 带 `X-Dye-Tag`（`placeOrder` / `demoOverrun` 两处） |
| `deployment/demo/README.md` | 改 | 环境开关说明与预期 |
| `deployment/start-all.sh` | 改 | 沙箱环境变量透传 |
| `deployment/demo/scenario-alipay-sandbox.sh` | 新增（可选） | 手工 live 验证脚本，**不进 CI** |
| `DemoProxyController` / `cashier.html` | **不动** | 代理已黑名单式逐头透传（S12） |

### 批次 I —— 测试

| 类型 | 文件 | 断言要点 |
|---|---|---|
| 新增 | `dye/DyeModeTest`、`dye/DyeFilterTest`、`dye/DyeRequestInterceptorTest` | 缺省 `MOCK`；非法值 400；出站头只在非空时写 |
| 新增 | `channel/ChannelContractCompatTest` | 5 参 / 3 参兼容构造器可用；`withReason` 保留 `credential` |
| 新增 | `channel/PayCredentialTest` | `isRedirectFamily()` 语义 |
| 新增 | `channel/AlipayAmountConversionTest` | 固定向量（`1` → `"0.01"`、`100000000` → `"1000000.00"`） |
| 新增 | `channel/AlipaySandboxChargeTest`（mock `AlipayGateway`） | 沙箱分支产出 `REDIRECT_URL` 凭证；`MOCK` 分支尾数 11 仍 `timeout` |
| 新增 | `api/AlipayNotifyControllerTest`（`@SpringBootTest` + `MockMvc`） | 验签失败 403；成功**恰好返回纯文本 `success`**；`WAIT_BUYER_PAY` 不推进 |
| 新增 | `domain/PaymentAttemptChannelModeTest` | 落库读 `DyeContext`；幂等重复不覆盖 |
| 新增 | `routing/DyeNotAffectingRoutingTest` | 两种染色下同一 `RouteContext` 选出同一 code（INV-3） |
| 新增 | `architecture-tests/ServiceBoundaryTest` | `application/**` MUST NOT 依赖 `com.alipay.sdk`（INV-7） |
| 改 | 无（除新增断言外）；**6 处测试桩 / 4 处构造点 / 21 个 `ChannelResult` 引用文件零改动** | 证明兼容性 |

## 5. Complexity Tracking（复杂度登记）

| 引入的复杂度 | 为什么必须 | 为什么不选更简单的做法 |
|---|---|---|
| **新依赖 `alipay-sdk-java`（34MB + 4 个传递依赖 + 已知 CVE）** | RSA2 签名 / 验签 / 参数排序自研极易出错，出错的表现是「支付成功但平台判失败」这类**资金级事故** | 纯 JDK 自研（`HttpClient` + `SHA256withRSA`）确实零依赖，但把 150~200 行密码学相关代码放进取款路径，风险收益不划算。**折中**：SDK 收口在 `AlipayGateway` 端口后，将来换实现零扩散（INV-7 / L5） |
| **DB 结构变更（`payment_attempts` 加列）** | 反向三条路径（退款 / 主动查询 / 超时扫描）**没有入口请求**，ThreadLocal 为空，染色值传不进去 | 不加列则只能「反向路径固定走 mock」，沙箱单退款是假的（比不做更危险）。**折中**：带默认值的非破坏性新增列 + 存量行默认 `MOCK` + 不回填 |
| **染色落 common-core（跨 9 个服务）** | demo 页的染色头必须在 **order-service** 被读出、再由它的 Feign 出站写给 payment-service；放 payment 内部则演示链路走不通 | 只改 order + payment 两个服务 → 重复代码且将来 refund 链路拿不到；只在 payment 内 → 演示要绕开订单入口。common-core 一处即全栈生效，且 **order-service 零改动**（S11 / S12） |
| **单 Adapter 双模态（覆写 `charge`）** | 负责人明确「不新建 SandboxChannelAdapter」；而 ADR-0072 规定「子类只声明身份、横切行为不得覆写」 | 新建 Sandbox Adapter → 需改渠道码（波及演示脚本 / 前端 / 测试）或让注册表一 code 挂两实现（破坏 ADR-0073 的注册表语义）。**折中**：`MOCK` 分支 `super` 委托，基类 4 件横切行为口径 100% 不变，ADR-0076 逐字对齐措辞 |

## 6. 回归与验收

- **全量门禁**：`./mvnw -B clean verify -fae` 全绿（含 `architecture-tests` 新增断言）。
- **零回归证明**：不染色路径下既有支付 / 退款 / 可靠性 / 集成 / E2E 测试**零改动**通过（SC-015）。
  > 这是「兼容构造器 + `default` 方法 + 不改 DTO」三项设计的直接验收。
- **离线可测**：所有沙箱相关单测用 **mock `AlipayGateway` + 固定签名向量**，**不连沙箱、不访问公网**（SC-013）。
- **手工 live 验证**（负责人自测，不进 CI）：
  1. 准备沙箱 APPID / 应用私钥 / 支付宝公钥，配置 `PAYMENT_ALIPAY_*` 环境变量；
  2. 内网穿透暴露 `notify_url`（如 `https://<ngrok>/internal/channels/alipay/notify`）；
  3. 置 `payment.channel.adapters.alipay.sandbox.enabled=true`，重启 payment-service；
  4. demo 页选「支付宝沙箱」下单 → 打开沙箱收银台 → 沙箱买家账号付款；
  5. 验证：payment `SUCCEEDED`、`payment_attempts.channel_reference = trade_no`、`channel_mode = 'SANDBOX'`。
- **文档无漂移**（`engineering-standards.md` §11 五条 grep）：ADR 引用一致性 / 编号唯一性 / 版本集中 /
  产物污染 / 链接与锚点可达。

## 7. 实现期待确认项（开工前须有明确答案）

| # | 待确认 | 推荐 |
|---|---|---|
| Q1 | 两份 ADR 是否由 🟡 Proposed 升为 ✅ Accepted | 建议**升**（负责人已逐条拍板 D1~D11） |
| Q2 | `ChargeRequest.scene` 缺省（`null`）时是否要按渠道推导一个默认场景 | 建议**不推导**，保持 `null` 表示「未声明」（零回归） |
| Q3 | `expireAt` 到各渠道的换算归属 | 建议**适配器负责**（支付宝 `timeout_express` 相对量、微信 RFC3339），契约只给绝对时刻 |
| Q4 | `notify_url` 是「配置单值」还是「按单动态拼」 | 建议**配置单值**（本期单租户演示），按单拼留作后续 |
| Q5 | 沙箱 `enabled=false` 时是否装配 Gateway Bean | 建议**不装配**（`@ConditionalOnProperty`），避免无密钥时构造失败 |
