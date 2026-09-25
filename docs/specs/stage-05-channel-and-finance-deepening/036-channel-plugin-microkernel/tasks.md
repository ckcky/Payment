# 036-channel-plugin-microkernel — Tasks

> **Status**: **全部完成**（代码已于 `b73f960` 合入 master）。
> 本文回溯登记，供后续 Feature 核对「036 到底做了什么」，以及给新渠道接入提供**已验证的操作清单**。
> **Spec**: [spec.md](./spec.md)　**Plan**: [plan.md](./plan.md)　**Acceptance**: [acceptance.md](./acceptance.md)

---

## T1　内核 6 类　[FR-001][FR-002][FR-003][FR-004][FR-007][FR-008][INV-1][INV-4]

- [x] `application/channel/spi/ChannelPlugin.java` — 插件端口，`descriptor()` 必实现，其余 `default`
- [x] `application/channel/spi/AbstractChannelPlugin.java` — 模板方法，`charge` / `refund` / `queryStatus` **final**
- [x] `application/channel/spi/ChannelPluginDescriptor.java`
- [x] `application/channel/spi/ChannelPluginFactory.java`
- [x] `application/channel/spi/ChannelCallbackEnvelope.java`
- [x] `application/channel/spi/ParsedCallback.java`

**复核判据**：
```bash
grep -n "public final ChannelResult" \
  payment-service/src/main/java/com/payment/payment/application/channel/spi/AbstractChannelPlugin.java
# 期望命中 charge / refund / queryStatus 三处
```

## T2　通用回调端点　[FR-006][FR-007][FR-008][INV-4]

- [x] `api/ChannelPluginCallbackController.java` — `/internal/channels/{code}/callback`
- [x] 内核只做「收报文 → 包信封 → 找插件 → 翻译 → 交平台 → 用插件 ACK」，**不解析渠道报文**

**复核判据**：端点内不得出现任何渠道私有类型（`Alipay*` / `Stripe*` / `Wechat*` / `Douyin*`）。

## T3　工厂定位 + Registry 改造　[FR-005]

- [x] `infra/channel/ChannelPluginFactoryLocator.java` — Spring Bean + `ServiceLoader` 双通道
- [x] `infra/channel/SpringChannelRegistry.java` — 改为经工厂装配（不再硬编码渠道清单）

## T4　Stripe 插件五件套 + SDK　[FR-009][FR-010][FR-011][INV-2][INV-3][INV-5]

- [x] 根 `pom.xml` `dependencyManagement` 锁 `com.stripe:stripe-java:33.4.2`
- [x] `infra/channel/stripe/StripeSdkGateway.java` — **唯一**允许 import `com.stripe.*` 的类
- [x] `infra/channel/stripe/StripeGateway.java` — 渠道端口
- [x] `infra/channel/stripe/StripeSandboxProperties.java` — env 注入 + `enabled` 门控 + 启动期强校验
- [x] `infra/channel/stripe/StripeChannelPlugin.java` — `extends AbstractChannelPlugin`
- [x] `infra/channel/stripe/StripeChannelPluginFactory.java` — SPI 注册

**复核判据**：
```bash
grep -rn "com\.stripe" payment-service/src/main/java --include=*.java | grep -v StripeSdkGateway
# 期望为空
```

## T5　ArchUnit 门禁　[FR-014]

- [x] R1 SDK 收口：`com.stripe..` 仅 `StripeSdkGateway` 可依赖
- [x] R2 内核不依赖具体渠道：`application/**` 禁依赖 `Alipay*` / `Stripe*` / `Wechat*` / `Douyin*` 开头类
- [x] 阳性对照（故意违规能触发）

## T6　账本 + 配置 + 联调脚本　[FR-012]

- [x] `09-ledger-schema.sql` / `031-ledger-accounting-foundation.sql` 补 STRIPE 账户 **id 15 / 16**
- [x] `application.yml` 新增 `payment.channel.sandbox.stripe.*`；`STRIPE: { enabled: false, priority: 40 }`
- [x] `deployment/demo/stripe-listen.sh` — Stripe CLI `v1.51.1` 转发到 `localhost:8084/internal/channels/STRIPE/callback`

## T7　存量渠道兼容与收口

- [x] `AlipayGateway` 从 `application/channel/` 迁至 `infra/channel/alipay/`（`git mv`，保留历史）
- [x] 既有测试 import 同步修正（6 个测试文件）
- [x] `--no-ff` 合入 master（`b73f960`）
- [ ] **未完成（转 037）**：删 `AlipayNotifyController`、存量 4 家迁 `AbstractChannelPlugin`

---

## 给「接新渠道」的可复用清单（本 Feature 已验证）

1. 建包 `infra/channel/<channel>/`，写 5 个类：`XxxChannelPlugin` / `XxxChannelPluginFactory` /
   `XxxGateway`（端口）/ `XxxSdkGateway`（SDK 收口）/ `XxxProperties`（env + 启动期强校验）。
2. 插件 `extends AbstractChannelPlugin`，只覆写差异钩子。
3. 根 pom 锁 SDK 版本；SDK import 只允许出现在 `*SdkGateway`。
4. **同步补账本两行 seed**（`CHANNEL_RECEIVABLE` / `CHANNEL_FEE_EXPENSE`，owner = 渠道码）——漏补首笔记账即崩。
5. `application.yml` 加 `<CODE>: { enabled: false, priority: N }`。
6. 若渠道名是新前缀，**同步更新 ArchUnit R2 的硬编码清单**（TD-3，038 后改包级规则则不必）。
7. 目标：`application/` / `api/` / `domain/` 三层零改动。
