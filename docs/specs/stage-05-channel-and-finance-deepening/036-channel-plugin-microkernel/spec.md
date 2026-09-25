# 036-channel-plugin-microkernel — Spec

> **Status**: Implemented `<!-- Draft | In Review | Approved | In Development | Implemented | Deprecated | Superseded | Not Implemented -->`
> **Date**: 2026-09-25（**回溯性 Spec**：代码已于 `b73f960` 合入 master，本 Spec 补齐文档欠账）
> **Stage / Path**: `docs/specs/stage-05-channel-and-finance-deepening/036-channel-plugin-microkernel/`
> **Related ADR**: 无独立 ADR（决策记录在本 Spec §9）
> **Standard**: [spec-standard.md](../../../standards/spec-standard.md)
> **实现提交**: `3b9ca41` feat(036) → `b73f960` Merge into master

> **⚠️ 本文性质**：本 Feature **已完成并合入 master**，但 Spec 四件套当时未写。
> 本文按**代码实证**回溯撰写，目的是：① 补齐文档完整性（037/038/039 均引用本 Feature）；
> ② 为后续渠道接入提供权威参照。所有 `【现状】` 均为可 grep 复核的代码事实。

---

## 1. 背景（Problem）

### 1.1 030 暴露的三条硬伤

030（渠道契约 / 沙箱 / 回调）接支付宝沙箱时，渠道域是「每接一家写一遍」的形态。实证如下：

| # | 【030 时的现状】 | 后果 |
|---|---|---|
| 1 | **每接一家渠道写一个 Controller**：支付宝回调走专属端点 `AlipayNotifyController`，报文解析逻辑写在 Controller 里 | 接第 N 家渠道要新增第 N 个 Controller + 第 N 套解析代码 |
| 2 | **application 层直接依赖具体渠道类**：`AlipayGateway` 位于 `application/channel/`（非 `infra/`），业务侧代码可直接引用 | 渠道实现与业务内核无隔离边界 |
| 3 | 渠道 SDK 类型（`AlipaySdkGateway` 等）散落在多处被调用 | 换 SDK / 换协议实现会扩散 |

### 1.2 目标：微内核 + 插件化

- **内核（microkernel）**：固定每一笔渠道交互的主流程，**不可覆写**；只声明差异钩子。
- **插件（plugin）**：各家渠道只实现自己的差异部分，**接新渠道对内核零改动**。
- **判据**：接新渠道时 `application/` / `api/` / `domain/` 三层**零改动** ⇒ 插件化成立。

---

## 2. 目标形态（Goals）

| # | 目标 | 落地情况 |
|---|---|---|
| G1 | 内核用**模板方法**锁死主流程，入口 `final` | ✅ `AbstractChannelPlugin` 三个 `final` 入口 |
| G2 | 插件由**工厂 + SPI** 注册与发现 | ✅ `ChannelPluginFactory` + `ChannelPluginFactoryLocator`（Spring Bean + `ServiceLoader`） |
| G3 | **通用回调端点**，报文翻译由插件承担，内核不解析渠道报文 | ✅ `ChannelPluginCallbackController` `/internal/channels/{code}/callback` |
| G4 | 渠道 SDK 收口到**单个类** | ✅ `com.stripe.*` 只允许出现在 `StripeSdkGateway` |
| G5 | 用 **ArchUnit** 把上述约束固化为 CI 门禁 | ✅ `ServiceBoundaryTest` 两条规则 |
| G6 | 以 **Stripe 沙箱（test mode）** 验证「接新渠道内核零改动」 | ✅ Stripe 五件套，`application/` 仅新增 `spi/` 包（内核）无渠道特有代码 |

---

## 3. 【现状】实现清单（代码实证）

### 3.1 内核 6 类 —— `payment-service/.../application/channel/spi/`

| 类 | 行数 | 职责 |
|---|---|---|
| `ChannelPlugin` | 91 | **插件端口**：`descriptor()` 必实现；`channelCode()` / `supportedScenes()` / `supportsRealMode()` / `acceptsCallback()` / `parseCallback()` / `callbackAckBody()` 为 `default` 可选钩子 |
| `AbstractChannelPlugin` | 242 | **模板方法基类**：`charge` / `refund` / `queryStatus` 三个入口 `final`；抽象钩子 `isRealModeEnabled` / `doRealCharge` / `doRealRefund` / `doRealQuery`；mock 默认实现 `doMock*` 可覆写 |
| `ChannelPluginDescriptor` | 50 | 插件**自描述**（渠道码、支持场景、是否支持真实模式等） |
| `ChannelPluginFactory` | 49 | 工厂接口（创建插件 + 声明渠道码） |
| `ChannelCallbackEnvelope` | 39 | 回调**原始报文信封**（内核不解析，交给插件翻译） |
| `ParsedCallback` | 62 | 插件翻译后的**平台统一结构** |

**模板四步**（`AbstractChannelPlugin` javadoc 明示，顺序不可变）：
1. **能力校验**（`requireSceneSupported`）→ 2. **模态门控**（`requireRealModeIfSandboxRequested`）→
3. **模态分派**（real → `doReal*` / mock → `doMock*`）→ 4. **异常兜底**（`guard` 统一收敛）

### 3.2 通用回调端点 —— `payment/api/ChannelPluginCallbackController.java`（279 行）

- 路径 `/internal/channels/{code}/callback`，**所有渠道共用**。
- 内核职责仅限：收报文 → 包成 `ChannelCallbackEnvelope` → 按 `{code}` 找插件 → 调 `parseCallback()` → 交平台侧处理 → 用插件的 `callbackAckBody()` 回 ACK。
- **内核不解析任何渠道报文**，翻译责任 100% 在插件 ⇒ 消除「每接一家写一个 Controller」。

### 3.3 工厂定位 —— `infra/channel/ChannelPluginFactoryLocator.java`（107 行）

- 双通道收集：Spring Bean（容器内插件）+ `ServiceLoader`（SPI 外部 jar）。
- `SpringChannelRegistry` 改为**通过工厂装配**（+64 行），不再硬编码渠道清单。

### 3.4 Stripe 插件五件套 —— `infra/channel/stripe/`

| 类 | 行数 | 职责 |
|---|---|---|
| `StripeChannelPlugin` | 281 | `extends AbstractChannelPlugin`；只覆写差异钩子 + `parseCallback` + `callbackAckBody` |
| `StripeChannelPluginFactory` | 42 | SPI 注册点 |
| `StripeGateway` | 134 | **渠道端口**（平台侧只见此接口，不见 SDK） |
| `StripeSdkGateway` | 209 | SDK 封装（**唯一允许 import `com.stripe.*` 的类**） |
| `StripeSandboxProperties` | 159 | 配置 + `enabled` 门控 + **启动期强校验** |

- SDK：`com.stripe:stripe-java:33.4.2`（版本在根 `pom.xml` 的 `dependencyManagement` **唯一锁定**，子模块声明不写版本）。
- Stripe **无 per-request notify_url**，只有全局 webhook 端点，靠 event 的 `metadata.paymentNo` 反查单据（协议差异全部关在插件内，内核无感）。

### 3.5 ArchUnit 门禁 —— `deployment/architecture-tests/.../ServiceBoundaryTest.java`（+69 行）

| 规则 | 内容 |
|---|---|
| R1 **SDK 收口** | 除 `StripeSdkGateway` 外，任何类不得依赖 `com.stripe..` |
| R2 **内核不依赖具体渠道** | `application/**` 不得依赖 `Alipay*` / `Stripe*` / `Wechat*` / `Douyin*` 开头的类 |

R2 的 `because` 原文即判据：「微内核 + 插件化的前提是『接新渠道不改内核』」。

### 3.6 配套

- **账本**：`09-ledger-schema.sql`（+5）、`031-ledger-accounting-foundation.sql`（+6）补 STRIPE 渠道维度账户 **id 15 `CHANNEL_RECEIVABLE` / id 16 `CHANNEL_FEE_EXPENSE`**。
- **配置**：`payment-service/application.yml` 新增 `payment.channel.sandbox.stripe.*`（enabled 默认 `false`）+ `STRIPE: { enabled: false, priority: 40 }`。
- **联调脚本**：`deployment/demo/stripe-listen.sh`（46 行）——Stripe CLI `v1.51.1` 转发到 `localhost:8084/internal/channels/STRIPE/callback`，终端打印的 `whsec_...` 即 webhook 签名密钥。

---

## 4. 功能性需求（FR）

> 回溯编号，均已在代码中实现。

| ID | 需求 |
|---|---|
| FR-001 | 渠道插件主流程 MUST 由模板方法锁死：`charge` / `refund` / `queryStatus` 声明为 `final`，子类不得覆写 |
| FR-002 | 模板四步顺序 MUST 固定：能力校验 → 模态门控 → 模态分派 → 异常兜底 |
| FR-003 | 插件 MUST 只实现差异钩子（`isRealModeEnabled` / `doReal*` / `parseCallback` / `callbackAckBody`），mock 行为由基类默认实现提供 |
| FR-004 | 插件 MUST 提供 `ChannelPluginDescriptor` 自描述，供内核与控制台读取能力 |
| FR-005 | 插件 MUST 经 `ChannelPluginFactory` 创建，由 `ChannelPluginFactoryLocator` 通过 Spring Bean 与 `ServiceLoader` 双通道收集 |
| FR-006 | 系统 MUST 提供**通用回调端点** `/internal/channels/{code}/callback`；内核不得解析渠道私有报文 |
| FR-007 | 回调报文翻译 MUST 由插件的 `parseCallback(ChannelCallbackEnvelope)` 承担，输出平台统一的 `ParsedCallback` |
| FR-008 | 回调 ACK 文本 MUST 由插件的 `callbackAckBody()` 提供（不同渠道 ACK 格式不同） |
| FR-009 | 渠道 SDK 类型 MUST 收口到单个 `*SdkGateway` 类；其余代码只依赖渠道端口接口 |
| FR-010 | 渠道凭据 MUST 只走 env 注入，`enabled=true` 时**启动期强校验**，缺失拒绝启动 |
| FR-011 | 染色 SANDBOX 而 `enabled=false` MUST 返回 400，**绝不静默回落 mock** |
| FR-012 | 新渠道 MUST 在 `09-ledger-schema.sql` 与 `031-ledger-accounting-foundation.sql` **同步补两行**渠道维度账户（`CHANNEL_RECEIVABLE` / `CHANNEL_FEE_EXPENSE`） |
| FR-013 | 接新渠道 MUST 做到 `application/` / `api/` / `domain/` 三层零改动 |
| FR-014 | ArchUnit MUST 固化 FR-009（SDK 收口）与 FR-013（内核不依赖具体渠道）两条规则，并含阳性对照 |

---

## 5. 不变量（INV）

| ID | 不变量 |
|---|---|
| INV-1 | 三个主流程入口恒为 `final` |
| INV-2 | 渠道密钥不得硬编码、不得入库、不得进明文日志 |
| INV-3 | `enabled=false` 时不得读取任何密钥 env |
| INV-4 | 内核不得出现任何渠道私有类型或报文解析逻辑 |
| INV-5 | 渠道 SDK 版本号在根 pom 唯一锁定 |

---

## 6. 验收场景（SC）

| ID | 场景 | 期望 |
|---|---|---|
| SC-001 | 接 Stripe 全流程 | `application/` 新增的只有 `spi/` 内核包，**无**渠道特有代码 |
| SC-002 | 通用回调端点 | `/internal/channels/STRIPE/callback` 收到事件后由插件翻译，内核不解析 |
| SC-003 | SDK 收口 | grep `com.stripe` 仅命中 `StripeSdkGateway` |
| SC-004 | 凭据门控 | `enabled=true` + env 缺 `webhook-secret` ⇒ 启动期拒绝启动 |
| SC-005 | 模态门控 | 染色 SANDBOX + `enabled=false` ⇒ 400（不回落 mock） |
| SC-006 | ArchUnit | `ServiceBoundaryTest` 两条规则绿，且阳性对照存在 |
| SC-007 | 账本 | STRIPE 渠道维度账户 id 15/16 存在，首笔记账不触发 `LEDGER_CHANNEL_UNKNOWN` |
| SC-008 | Stripe 联调 | `stripe-listen.sh` 转发 + 测试卡 `4242...`（成功）/ `4000...0002`（被拒）闭环 |

---

## 7. 非功能需求（NFR）

| ID | 需求 |
|---|---|
| NFR-1 | 接新渠道的改动 MUST 收敛在 `infra/channel/<channel>/` 单包内 |
| NFR-2 | 内核改动 MUST 不影响既有渠道（MOCK / WECHAT / ALIPAY / DOUYIN 行为零变化） |
| NFR-3 | 渠道协议差异 MUST 关在插件内，内核无感 |

---

## 8. 【现状】遗留技术债（037 / 038 承接）

| # | 债务 | 承接方 |
|---|---|---|
| TD-1 | `AlipayNotifyController` **未删除**（既有测试仍依赖）⇒ 当前「支付宝专属端点 / 通用端点」**双轨并存** | 037 T6 |
| TD-2 | 仅 **Stripe** 走新 SPI，存量 MOCK / WECHAT / ALIPAY / DOUYIN **未迁** `AbstractChannelPlugin`（半插件化） | 037 T6 |
| TD-3 | ArchUnit R2 是**硬编码渠道名**（`Alipay*` / `Stripe*` / `Wechat*` / `Douyin*`），接新渠道需改门禁 | 038 T6（包级规则） |
| TD-4 | 渠道件仍散落在 `payment/application/channel` + `infra/channel` + `api` + `web` + `infra/config` 五处，`channelgateway` 包**物理不存在** | 038 T1 |
| TD-5 | 账本渠道账户靠 **seed 行**隐式保证，无启动期校验；漏补则首笔记账即 `LEDGER_CHANNEL_UNKNOWN` fail-fast | 039（接入时自查） |

---

## 9. 决策记录（D）

| ID | 决策 | 理由 |
|---|---|---|
| D1 | 用**模板方法**而非策略注入 | 主流程是「所有渠道都必须走的固定四步」，用 `final` 让新渠道**想错都错不了** |
| D2 | 插件用**工厂 + SPI 双通道** | Spring Bean 覆盖容器内插件，`ServiceLoader` 为将来外部 jar 留口 |
| D3 | 回调走**通用端点 + 插件翻译** | 消除「每接一家写一个 Controller」 |
| D4 | 先做**包级隔离 + ArchUnit**，不拆 Maven 模块 | Java 无模块级编译隔离（除非 JPMS）；包级 + CI 拦截成本最低 |
| D5 | 第 5 家渠道选 **Stripe 沙箱** | 原定抖音支付需企业资质，不可得；Stripe test mode 免资质且有 webhook 可验 |

---

## 10. 关联 Spec

- [030-channel-contract-sandbox-callback](../030-channel-contract-sandbox-callback/spec.md) — 渠道契约 / 沙箱 / 回调（036 的问题来源）
- [037-channel-gateway-boundary](../037-channel-gateway-boundary/spec.md) — 承接 TD-1/TD-2（门面收口 + 存量渠道迁移）
- [038-payment-service-package-boundary](../038-payment-service-package-boundary/spec.md) — 承接 TD-3/TD-4（包级边界 + 包级门禁）
- [039-wechat-pay-channel-plugin](../039-wechat-pay-channel-plugin/spec.md) — 第 6 家渠道，以 036 的 Stripe 插件为同构参照
- [Constitution §Governance](../../../../.specify/memory/constitution.md)
