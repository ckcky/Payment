# 039-wechat-pay-channel-plugin — Spec

> **Status**: Draft `<!-- Draft | In Review | Approved | In Development | Implemented | Deprecated | Superseded | Not Implemented -->`
> **Date**: 2026-09-25
> **Stage / Path**: `docs/specs/stage-05-channel-and-finance-deepening/039-wechat-pay-channel-plugin/`
> **Related ADR**: 待实现阶段补（渠道接入类，参照 036 Stripe 是否立 ADR 再定）
> **Standard**: [spec-standard.md](../../../standards/spec-standard.md)

> **阅读约定**：`【现状】`= 已核实的代码事实；`【实测】`= 本机实测输出（附命令与原始响应）；`【目标】`= 设计意图，**尚未实现**。
> **本 Spec 是执行方唯一事实源**：`tasks.md` 为逐条打勾清单，验收命令见 `acceptance.md`。

---

## 1. 背景（Problem）

### 1.1 现状：微信渠道只有 mock，没有真实协议

| # | 【现状】 | 证据 |
|---|---|---|
| 1 | `WechatChannelAdapter` 只是 `extends AbstractMockChannelAdapter`，**无真实协议、无签名、无 SDK**；只声明 `channelCode()=WECHAT` + 可配 mock 场景 | `payment-service/src/main/java/com/payment/payment/infra/channel/WechatChannelAdapter.java`（全文 45 行） |
| 2 | 036 已建立微内核 + 插件化，但**只有 Stripe 一家**走 `AbstractChannelPlugin` | `payment-service/.../infra/channel/stripe/`（5 个类）；grep `extends AbstractChannelPlugin` 单命中 |
| 3 | 微信支付是国内主流渠道，本项目（电商支付平台）**缺真实接入** | — |

```java
// 【现状】WechatChannelAdapter.java 摘录
@Component
public class WechatChannelAdapter extends AbstractMockChannelAdapter {
    public static final String CODE = "WECHAT";
    @Override public String channelCode() { return CODE; }
    // 仅 @Value 注入 mock 场景：SUCCESS / TIMEOUT / REJECT ... 无任何真实 HTTP 调用
}
```

### 1.2 🔴【实测】微信支付沙箱：V3 没有沙箱，V2 沙箱也覆盖不到本项目主流程

> 用户要求「看看微信的沙箱能不能用，试试」。实测如下（2026-09-25，本机 curl，无代理）。

| # | 探测 | 【实测】原始结果 | 结论 |
|---|---|---|---|
| 1 | V2 沙箱换密钥<br>`POST https://api.mch.weixin.qq.com/xdc/apiv2sandbox/pay/getsignkey` | **HTTP 200**<br>`<xml><return_code>FAIL</return_code>`<br>`<return_msg>商户号非法</return_msg></xml>` | V2 沙箱端点**活着**，但**必须真实商户号**才能换到沙箱密钥（此处用的是文档示例号 `1900000109`） |
| 2 | V3 沙箱路径探测<br>`POST https://api.mch.weixin.qq.com/v3/sandboxnew/pay/transactions/native` | **HTTP 404** | **V3 根本没有沙箱** |
| 3 | V3 正式路径连通性<br>`GET https://api.mch.weixin.qq.com/v3/certificates` | **HTTP 401**<br>`{"code":"SIGN_ERROR","message":"Authorization不合法"}` | 网络可达；必须真实商户 API 证书才能调用 |
| 4 | 官方文档《支付验收指引》<br>`pay.weixin.qq.com/wiki/doc/api/native_sl.php?chapter=23_1` | 原文：「仿真系统的 API 协议与正式 API 完全相同。商户开发者只需将正式 API 的调用 URL 增加一层 `xdc/apiv2sandbox` 路径……**目前只支持付款码支付成功用例与付款码支付异常用例中的接口调用，下单接口 `https://api.mch.weixin.qq.com/pay/unifiedorder` 等目前暂不支持使用**」；异常用例靠 HTTP Header `Wechatpay-Negative-Test: {用例名}` 识别 | V2 沙箱**只覆盖付款码支付**（线下被扫，micropay），**明确不支持下单接口** |

**⇒ 三条理由，缺一不可：**

1. **沙箱只有 V2，本项目要用 V3**：沙箱是 XML + MD5/HMAC 签名；V3 是 JSON + RSA-SHA256 + 商户 API 证书 + 平台证书 + AES-256-GCM 回调加密。沙箱**练不到 V3 的签名 / 验签 / 证书链路**。
2. **V3 沙箱不存在**：`/v3/sandboxnew/**` 实测 **404**（网上「V3 有 sandbox」的说法是错的）。
3. **场景不匹配**：即使退回 V2 沙箱，也只支持**付款码支付**（micropay，线下被扫），而本项目是**电商下单 → Native 扫码 / JSAPI 唤起**，正是官方写明「暂不支持」的下单接口。

> **横向对比**：支付宝有可用沙箱（已接入 `AlipaySandboxProperties`）；Stripe 有 test mode（已接入 `StripeSandboxProperties`）。
> **微信是本项目唯一没有可联调环境的渠道**——这是渠道侧客观事实，**不是设计缺陷**。
> ⇒ 本 Feature 的验证策略必须绕开沙箱，改用三层替代验证（SC-003 / SC-004 / SC-005）。

### 1.3 关键复用点（不新造）

| # | 【现状】 | 证据 |
|---|---|---|
| 5 | `AbstractChannelPlugin` 模板方法：`charge`/`refund`/`queryStatus` 三个入口 `final`，四步（能力校验 → 模态门控 → 模态分派 → 异常兜底）；子类只需 `descriptor()` + `isRealModeEnabled()` + `doRealCharge`/`doRealRefund`/`doRealQuery` | `application/channel/spi/AbstractChannelPlugin.java` |
| 6 | `ChannelPlugin` 有默认钩子：`channelCode()`、`supportedScenes()`、`supportsRealMode()`、`acceptsCallback()`、`parseCallback(ChannelCallbackEnvelope)`、`callbackAckBody()` | `application/channel/spi/ChannelPlugin.java:36-88` |
| 7 | `PaymentScene` 枚举：`WEB` / `H5` / `NATIVE` / `JSAPI` / `MINI_PROGRAM` / `APP` | `common/common-dto/.../channel/PaymentScene.java:37-47` |
| 8 | **WECHAT 账本账户已存在**：`CHANNEL_RECEIVABLE` owner=WECHAT、`CHANNEL_FEE_EXPENSE` owner=WECHAT | `deployment/schema/09-ledger-schema.sql:163,167`；`031-ledger-accounting-foundation.sql:153,157` |
| 9 | Stripe 是同构参照实现：`StripeChannelPlugin` / `StripeChannelPluginFactory` / `StripeGateway`（渠道端口）/ `StripeSdkGateway`（SDK 封装）/ `StripeSandboxProperties`（env + `enabled` 门控 + 启动期强校验） | `infra/channel/stripe/` |

---

## 2. 目标（Goal）

| ID | 目标 | 判定 |
|---|---|---|
| G1 | `WECHAT` 从 mock 适配器升级为**真实插件**，支持 **Native 扫码**与 **JSAPI** 下单 | SC-001 |
| G2 | 支持查询（`out_trade_no` / `transaction_id`）、退款、回调（V3 验签 + AES-256-GCM 解密） | SC-004 |
| G3 | 凭据只走 env、禁落盘；`enabled=true` 时启动期强校验 | SC-006 |
| G4 | 无沙箱可联调的前提下，用「签名金标准单测 + 本地仿真桩 + 真商户 0.01 元闭环（可选）」三层验证替代 | SC-003 / SC-005 |
| G5 | 验证插件化成立：接微信对内核 / application / api / domain **零改动** | SC-002 |

---

## 3. 范围（Scope）

- **新增插件包**：`com.payment.channelgateway.infra.wechat`（038 合入后的路径）。
  ⚠️ 若 038 未合入，先落 `com.payment.payment.infra.channel.wechat`，038 合并时随之迁移。
- **依赖**：`com.github.wechatpay-apiv3:wechatpay-java:0.2.17`（Maven Central `maven-metadata.xml` 最高版本，lastUpdated 2025-04-14）。
- **账本**：**无需补 seed**——WECHAT 账户已存在（§1.3 #8）。⚠️ 漏补会触发 `LEDGER_CHANNEL_UNKNOWN` fail-fast，此处已核实。
- **回调端点**：复用既有通用端点 `POST /internal/channels/WECHAT/callback`，**不新增微信专属 Controller**。
- **是否动 schema**：**否**（渠道模态沿用 `payment_attempts.extra_json` 的 `channelMode` 键）。
- **是否动公共 API**：**否**。
- **是否动状态机**：**否**。

---

## 4. 非目标（Non-goals）

| 不做 | 理由 / 留给谁 |
|---|---|
| **不申请真实商户号、不做真钱验证** | 需企业资质；本 Feature 交付「配好凭据即可跑」的插件 |
| **不做付款码支付（micropay）** | 唯一能进沙箱的场景，但本项目是电商下单，无价值 |
| 不做微信 **V2** 协议 | 官方已进入淘汰阶段；本项目统一 V3 |
| 不做分账 / 电子发票 / 商家转账 / 支付分 / 服务商模式 | 超出支付主流程 |
| 不改 `AbstractChannelPlugin` 内核 | 改内核即插件化不成立（INV-6） |
| 不做 037 的门面 / `PaymentNotifyPort` 收口 | 037 独立推进 |

---

## 5. 场景与用户故事（Scenarios / User Stories）

| ID | 角色 | 前置 → 触发 → 期望 |
|---|---|---|
| US1 | 支付平台开发 | 配好 env 凭据 → 下单选 WECHAT + `NATIVE` 场景 → 拿到真实 `code_url`，可生成二维码 |
| US2 | 支付平台开发 | 下单选 `JSAPI` 场景（带 `payer.openid`）→ 拿到 `prepay_id`，可拼前端唤起参数 |
| US3 | 运维 | 未配凭据（`enabled=false`）→ WECHAT 渠道**不注册**、不读密钥、不出现在 `/internal/channels` |
| US4 | 资金正确性负责人 | 渠道超时 / SDK 抛异常 → 归一化为 `UNKNOWN`（可重试），**绝不臆断成败** |
| US5 | 资金正确性负责人 | 收到微信回调 → **先验签 → 再 AES-256-GCM 解密 → 再处理**；验签失败一律拒绝 |
| US6 | 支付平台开发 | 染色 SANDBOX 但 `enabled=false` → **硬失败**，绝不静默回落 mock |

**失败流**：

| # | 场景 | 触发 | 期望 |
|---|---|---|---|
| F1 | 凭据缺失却启用 | `enabled=true` 但 env 缺 `mchId` / 私钥 / APIv3 密钥 | **启动期拒绝启动** |
| F2 | 重复下单 | 同一 `out_trade_no` 重复提交 | 判 `UNKNOWN` 并留痕，**不得臆断成功** |
| F3 | 退款被拒 | `NOTENOUGH` / `REFUND_OVER_TIME_LIMIT` | 判 `FAILURE`，`failure_reason` 落库 |
| F4 | 回调重放 | 同一通知重复投递 | 幂等吸收 |
| F5 | 验签失败 / 证书不可用 | `Wechatpay-Serial` 对不上 | 拒绝回调，应答微信失败，记 `channel.callback_verify_failed` |

---

## 6. 功能需求（Functional Requirements）

| ID | 需求 |
|---|---|
| FR-001 | 系统 MUST 新增插件包 `com.payment.channelgateway.infra.wechat`，含 5 个类：`WechatChannelPlugin`（`extends AbstractChannelPlugin`）、`WechatChannelPluginFactory`（SPI 注册）、`WechatGateway`（渠道端口）、`WechatSdkGateway`（V3 SDK 封装）、`WechatPayProperties`（配置 + 门控） |
| FR-002 | `payment-service/pom.xml` MUST 增加 `com.github.wechatpay-apiv3:wechatpay-java:0.2.17`；SDK 引用 MUST 收口在 `wechat` 插件包内（对齐既有 SDK 收口门禁） |
| FR-003 | `descriptor()` MUST 自描述：`channelCode = WECHAT`；`supportedScenes ⊇ {NATIVE, JSAPI, H5, MINI_PROGRAM}`；`supportsRealMode = true`（受 `enabled` 门控）；`acceptsCallback = true` |
| FR-004 | `doRealCharge` MUST 支持：`NATIVE` → `POST /v3/pay/transactions/native` 取 `code_url`；`JSAPI` → `POST /v3/pay/transactions/jsapi` 取 `prepay_id`；`H5` → `/v3/pay/transactions/h5` 取 `h5_url`。金额一律用**分（int）**，与平台 `amountMinor` 直接对应 |
| FR-005 | `doRealQuery` MUST 支持按 `out_trade_no` 与 `transaction_id` 两种键查询（`GET /v3/pay/transactions/out-trade-no/{no}` 与 `GET /v3/pay/transactions/id/{id}`） |
| FR-006 | `doRealRefund` MUST 调 `POST /v3/refund/domestic/refunds`，退款单号用平台 `refundNo` 映射 `out_refund_no` |
| FR-007 | `parseCallback` MUST 完成 V3 通知三步：**① 平台证书验签**（`Wechatpay-Signature` / `Wechatpay-Timestamp` / `Wechatpay-Nonce` / `Wechatpay-Serial`）→ **② APIv3 密钥 AES-256-GCM 解密 `resource`** → **③ 转换为平台统一 `ParsedCallback`**。**顺序不可颠倒** |
| FR-008 | `WechatPayProperties` MUST 以 env 注入（前缀 `PAYMENT_WECHAT_`），`enabled` 默认 `false`；`enabled=true` 时 `@PostConstruct` 强校验缺失项并**拒绝启动**（与 `StripeSandboxProperties` 同纪律） |
| FR-009 | 商户私钥 / APIv3 密钥 / 平台证书 MUST **只走 env 或 env 指向的文件路径**，**禁止**写入 `application.yml`、禁止入库、禁止进 git |
| FR-010 | MOCK 模态 MUST 沿用内核统一实现，插件内**不得**自写 mock 语义 |
| FR-011 | 单测 MUST 提供**签名金标准**：固定密钥 + 固定 `timestamp` / `nonce` → 断言 `Authorization` 头签名串**逐字节一致**；并提供 V3 通知「加密 → 解密 → 验签」往返用例（用**本地生成**的测试密钥对，不依赖微信） |
| FR-012 | MUST 提供**本地微信仿真桩**（WireMock 或自建测试 Controller），模拟 V3 响应（`code_url` / `prepay_id` / 查询 / 退款受理 / 回调通知），使插件全链路**不依赖微信网络**即可走通 |
| FR-013 | 渠道模态 MUST 写入 `payment_attempts.extra_json` 的 `channelMode` 键（既有纪律，不新增列）；归属判定恒读 `channel_code` 列 |
| FR-014 | 回调 MUST 复用既有通用端点 `POST /internal/channels/WECHAT/callback`，**不得**新增微信专属 Controller |

---

## 7. 业务规则与不变量（Business Rules）

| ID | 规则 / 不变量 | 违反时的处理 | 守卫者 |
|---|---|---|---|
| INV-1 | 商户私钥 / APIv3 密钥 / 平台证书 MUST NOT 落盘进仓库或配置文件 | 凭据泄露 = 资金风险 | FR-008 / FR-009 + 启动期校验 |
| INV-2 | 染色 SANDBOX 而真实模式未启用 ⇒ **硬失败 400**，绝不静默回落 mock | 静默回落会把「想测真实」变成「跑了假数据」 | `AbstractChannelPlugin` 内核（既有） |
| INV-3 | 渠道超时 / SDK 异常 ⇒ `UNKNOWN`（可重试），**绝不臆断成败** | 网络抖动被记成失败 → 渠道已扣款但订单被关 | `AbstractChannelPlugin` 内核（既有） |
| INV-4 | 金额单位 MUST 为**分（int）**，与 `amountMinor` 一一对应，**禁止元/分混用** | 金额放大 100 倍 | FR-004 + 单测 |
| INV-5 | 回调处理顺序 MUST 为「验签 → 解密 → 业务」，不可颠倒 | 未验签就解密 = 接受伪造通知 | FR-007 + 单测 |
| INV-6 | 接入微信 MUST 对内核 / `application` / `api` / `domain` **零改动** | 需改内核即插件化不成立 | SC-002 |
| INV-7 | 微信交易状态映射 MUST 符合 §8；`USERPAYING` / `NOTPAY` 一律判 `UNKNOWN` 不判失败 | 误判失败 → 用户已付款却订单被关 | 单测钉死 |

---

## 8. 状态与生命周期（State / Lifecycle）

**微信 `trade_state` → 平台 `ChannelResult.Status` 映射**（本 Feature 唯一的状态口径新增点）：

| 微信 `trade_state` | 平台 Status | 说明 |
|---|---|---|
| `SUCCESS` | `SUCCESS` | 支付成功 |
| `REFUND` | `FAILURE` | 已退款（退款语义由平台侧承载，不做支付成功判定） |
| `CLOSED` | `FAILURE` | 已关闭 |
| `REVOKED` | `FAILURE` | 已撤销（付款码场景） |
| `PAYERROR` | `FAILURE` | 支付失败 |
| `USERPAYING` | `UNKNOWN` | 用户支付中，**可重试查询** |
| `NOTPAY` | `UNKNOWN` | 未支付，**可重试** |
| `ORDERNOTEXIST` | `UNKNOWN` | 订单不存在，留痕对账 |
| `SYSTEMERROR` | `UNKNOWN` | 微信侧异常 |

**终态吸收口径沿用既有规则**，本 Feature 不改变：`payments` 与 `payment_attempts` 可合法不一致，只能靠对账发现。

---

## 9. 错误与边界（Error / Edge Cases）

| 场景 | 触发条件 | 期望行为 | 错误标识 |
|---|---|---|---|
| 凭据缺失却启用 | `enabled=true` + env 缺必需项 | 启动期抛错，应用起不来 | `WECHAT_CONFIG_INCOMPLETE` |
| 场景不支持 | `scene` 不在 `supportedScenes` | 调渠道前就 400 拒绝 | `INVALID_ARGUMENT` |
| 真实模式未启用却染沙箱 | `enabled=false` + `DyeContext.isSandbox()` | 400 硬失败 | `INVALID_ARGUMENT` |
| SDK 抛异常 / 超时 | 网络抖动、微信 5xx | 归一化为 `UNKNOWN` | `CHANNEL_UNKNOWN` |
| 回调验签失败 | 签名不匹配 / 证书不可用 | 拒绝回调，应答微信失败 | `WECHAT_CALLBACK_VERIFY_FAILED` |
| 回调解密失败 | `resource` 解密异常 | 拒绝回调 | `WECHAT_CALLBACK_DECRYPT_FAILED` |
| 退款被拒 | `NOTENOUGH` / `REFUND_OVER_TIME_LIMIT` | `FAILURE` + `failure_reason` 落库 | `CHANNEL_REFUND_REJECTED` |

---

## 10. 幂等与重复处理（Idempotency）

| 入口 | 幂等键 | 存储 | 重复请求行为 |
|---|---|---|---|
| 下单 | `channelNo` → 微信 `out_trade_no` | 微信侧 + `payment_attempts` | 微信返回重复 ⇒ 判 `UNKNOWN` 留痕，**不臆断** |
| 退款 | 平台 `refundNo` → 微信 `out_refund_no` | 微信侧 + `transaction_refunds` | 沿用既有退款三层幂等（order 在途守卫 → TXRF 幂等键 → `RefundPolicy` 累计上限） |
| 回调 | 微信 `transaction_id` + 通知 `event_type` | 既有回调幂等口径 | 重复通知幂等吸收（F4） |

**重放**：支持。回调重放 MUST NOT 产生二次记账（沿用既有幂等键）。

---

## 11. 验收标准（Acceptance Criteria）

| ID | 验收标准 | 验证方式 |
|---|---|---|
| SC-001 | `WECHAT` 注册为 `AbstractChannelPlugin` 插件；`enabled=false` 时不注册 | 单测断言 `WechatChannelPluginFactory` 产出 + `descriptor()` 字段 |
| SC-002 | **零改动判据**：`git diff --stat` 中 `AbstractChannelPlugin` / `ChannelPlugin` / `ChannelPluginFactory` 内核**零改动**；`application` / `api` / `domain` **零改动**；改动仅限 `infra/wechat/**` + `pom.xml` + 配置 | `git diff --stat` 人工核对 |
| SC-003 | **签名金标准**：固定密钥 + 固定 `timestamp` / `nonce` 下，`Authorization` 头签名串逐字节一致 | 单测（固定向量） |
| SC-004 | **回调往返**：本地生成密钥对 → 构造 V3 通知（加密 + 签名）→ 插件验签 + 解密 → 断言解析结果与原文一致；验签失败用例能触发拒绝 | 单测 |
| SC-005 | **仿真桩全链路**：不依赖微信网络，走通「下单（NATIVE/JSAPI）→ 查询 → 退款 → 回调」四步 | 集成测试（WireMock / 自建桩） |
| SC-006 | `enabled=false` 时：渠道不注册、路由不选中、不读取任何密钥 env | 单测 + 启动日志断言 |
| SC-007 | `enabled=true` 且 env 缺项时：**启动期拒绝启动** | 单测断言 `@PostConstruct` 抛错 |
| SC-008 | 全量单测零回归 + 全链路 demo 场景通过 | 见 `acceptance.md` 回归基线 |
| SC-009 | **沙箱结论留档**：`acceptance.md`「沙箱实测」章节保留 §1.2 四条证据，注明「V3 无沙箱、V2 沙箱不支持下单 ⇒ 不用于本渠道验证」 | 文档核对 |

---

## 12. 依赖（Dependencies）

**硬依赖**
- **038（payment-service 包边界重构）**：决定插件包落点。**建议 038 合入后再开工 039**，否则需二次迁移。
- **036（Channel 微内核 + 插件化，已合入 master）**：`AbstractChannelPlugin` + `ChannelPluginFactory` SPI + 通用回调端点是本 Feature 的底座。

**软依赖 / ⚠️ 冲突提示**
- **与 037 T6 重叠**：037 T6 要求「MOCK / WECHAT / ALIPAY / DOUYIN 迁至 `AbstractChannelPlugin`」。
  **本 Feature 把 WECHAT 一步到位写成 `AbstractChannelPlugin` 插件包** ⇒ **037 T6 中 WECHAT 的部分被本 Feature 取代**；MOCK / ALIPAY / DOUYIN 仍归 037。
  执行 037 时 MUST 跳过 WECHAT，避免两个分支撞车。

**外部依赖（本 Feature 不获取）**
- 真实联调需：**企业资质**商户号 `mchid`、已绑定 `appid`、商户 API 证书（含序列号）、APIv3 密钥、微信支付平台证书 / 公钥。
- 官方推荐的无沙箱验证法：**0.01 元实付 + 即时退款**闭环（不占用资金、可反复跑）。

---

## 13. 相关文档（Related Documents）

- [038-payment-service-package-boundary](../038-payment-service-package-boundary/spec.md) — 决定插件包落点
- 037-channel-gateway-boundary — 渠道网关边界收口（WECHAT 迁移重叠，见 §12）。
  ⚠️ 该 Spec 尚在 `feature/037-channel-gateway-boundary` 分支、**未合入 master**，故此处不给出相对链接。
- [payment-service System Design](../../../architecture/systems/payment-service.md) — L0，渠道清单需在完成后同步
- 微信支付《支付验收指引》`pay.weixin.qq.com/wiki/doc/api/native_sl.php?chapter=23_1`
- 微信支付 APIv3《开发必要参数说明》`pay.weixin.qq.com/doc/v3/merchant/4013070756`
