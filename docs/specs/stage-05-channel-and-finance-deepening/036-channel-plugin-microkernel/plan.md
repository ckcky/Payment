# 036-channel-plugin-microkernel — Plan

> **Status**: Implemented（**回溯性 Plan**：实现已完成，本文记录当时的结构决策与风险处置）
> **Spec**: [spec.md](./spec.md)　**Tasks**: [tasks.md](./tasks.md)　**Acceptance**: [acceptance.md](./acceptance.md)

---

## 1. 目标包结构

```
payment-service/src/main/java/com/payment/payment/
├── application/channel/
│   ├── spi/                          ← 【新增】微内核（渠道无关）
│   │   ├── ChannelPlugin.java            插件端口（default 钩子）
│   │   ├── AbstractChannelPlugin.java    模板方法基类（入口 final）
│   │   ├── ChannelPluginDescriptor.java  插件自描述
│   │   ├── ChannelPluginFactory.java     工厂接口
│   │   ├── ChannelCallbackEnvelope.java  回调原始信封
│   │   └── ParsedCallback.java           翻译后统一结构
│   ├── ChannelRegistry / ChannelRouter / ChannelResult / ChargeRequest ...  （既有）
│   └── ...
├── api/
│   ├── ChannelPluginCallbackController.java   ← 【新增】通用回调端点
│   ├── AlipayNotifyController.java            ← 【保留】TD-1 双轨
│   └── ...
└── infra/channel/
    ├── ChannelPluginFactoryLocator.java       ← 【新增】工厂定位（Spring + SPI）
    ├── SpringChannelRegistry.java             ← 【改】改为经工厂装配
    ├── alipay/  AlipayGateway / AlipaySdkGateway   ← 【迁】从 application/ 移入
    ├── stripe/  插件五件套                     ← 【新增】
    └── mock / wechat / douyin ...              ← 【未迁】TD-2
```

---

## 2. 实现顺序（实际执行路径）

| 阶段 | 内容 | 落点 |
|---|---|---|
| 1 | 内核 6 类 | `application/channel/spi/` |
| 2 | 通用回调端点 | `api/ChannelPluginCallbackController` |
| 3 | 工厂定位 + Registry 改造 | `infra/channel/` |
| 4 | Stripe 插件五件套 + SDK 依赖 | `infra/channel/stripe/` + pom |
| 5 | ArchUnit 两条门禁 | `ServiceBoundaryTest` |
| 6 | 账本账户 + 配置 + 联调脚本 | sql / yml / `stripe-listen.sh` |

---

## 3. 关键决策（详见 spec.md §9）

| ID | 决策 | 备选（未采纳） | 理由 |
|---|---|---|---|
| D1 | 模板方法 + `final` 入口 | 策略模式 Context 注入 | 主流程固定四步，用 `final` 防错；策略注入会让每个调用方自己拼装顺序 |
| D2 | 工厂 + SPI 双通道 | 纯 Spring Bean 扫描 | 为将来外部 jar 插件留口 |
| D3 | 通用端点 + 插件翻译 | 每渠道一个 Controller | 消除重复，内核不碰渠道报文 |
| D4 | 包级 + ArchUnit | 拆 Maven 模块 `channel-gateway` | 成本；JPMS 太重。038 之后可重新评估 |
| D5 | 第 5 家选 Stripe | 抖音支付 | 抖音需企业资质，不可得 |

---

## 4. 风险与处置

| ID | 风险 | 处置 |
|---|---|---|
| R1 | 模板方法过度约束，特殊渠道无法适配 | 抽象钩子已覆盖差异；极端情况可覆写 `doMock*` 默认实现 |
| R2 | 双通道工厂重复注册 | `ChannelPluginFactoryLocator` 按渠道码去重 |
| R3 | Stripe 无 per-request notify_url | 全局 webhook + `metadata.paymentNo` 反查（差异关在插件内） |
| R4 | webhook 签名密钥缺失被静默跳过 | **启动期强校验**（缺失即拒绝启动）——缺失即跳过验签等于「任何伪造事件都能把未付款订单推进成已支付」 |
| R5 | 渠道账户漏补导致首笔记账崩 | 本次已补 STRIPE id 15/16；该风险未根除 → TD-5 |
| R6 | SDK 扩散 | ArchUnit R1 收口到 `StripeSdkGateway` 单类 |
| R7 | 存量渠道未迁移造成认知分裂 | 明确记为 TD-2，由 037 承接 |

---

## 5. 与后续 Feature 的边界

| Feature | 承接内容 |
|---|---|
| 037 | TD-1（删 `AlipayNotifyController`）、TD-2（存量 4 家迁 `AbstractChannelPlugin`）；**WECHAT 跳过**（归 039） |
| 038 | TD-3（门禁改包级）、TD-4（建 `channelgateway` 包） |
| 039 | 第 6 家渠道；以 Stripe 五件套为**同构参照**；落实 TD-5 自查 |
