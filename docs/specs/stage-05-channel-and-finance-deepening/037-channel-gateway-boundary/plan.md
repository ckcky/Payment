# Plan: 037-channel-gateway-boundary

**对应 Spec**：[spec.md](spec.md)　**推进方式**：TDD　**测试策略**：单测（不起全链路）

---

## 1. 目标形态

把渠道网关做成**进程内微服务边界**（in-process module boundary）：

```
Payment 域 ──[ChannelGateway]──▶ 渠道网关域 ──▶ 插件包（每渠道一个）
   ▲                                  │
   └──────[PaymentNotifyPort]─────────┘   （Payment 定义 + 实现，Channel 只依赖接口）
```

两条硬约束：

- **出向**：Payment → Channel 只经 `ChannelGateway`（`pay` / `refund` / `query`）
- **入向**：Channel → Payment 只经 `PaymentNotifyPort`（`onChannelPayResult` / `onChannelRefundResult`）

跨域类型一律走 `common-dto`（D1），跨域标识一律业务单号（INV-4 / ADR-0063）。

## 2. 为什么不在 payment-service 内新建契约包

`common-dto` 已是跨服务共享契约包，被 10 个模块依赖（spec §1.4 #15）。放在那儿，
将来渠道网关真拆成独立服务时，**两域依赖同一个 jar 即可，契约零改造**。
新建内部契约包等于预支一次搬迁。

## 3. 为什么是 Strategy Registry 而不是教科书策略模式

渠道是**运行时动态增删**的插件族，不是编译期确定的 2~3 个算法：

- `ChannelPlugin` = 策略接口
- `ChannelRegistry` = 策略注册表
- `channelCode` = 选择键，由配置驱动在运行时选出

教科书 Context 注入（构造期持有策略引用）在此会退化成 `if/else` 或 Map 查找。
**本 Feature 不改动这部分**（036 已落地且形态正确）。

唯一保留的可选项：把 `AbstractChannelPlugin` 里三处 `if (DyeContext.isSandbox())`
升为 `ChannelModeHandler` 策略——**本轮不做**，收益仅消灭 3 个 if，不影响插拔能力。

## 4. 步骤与依赖

| 步骤 | 内容 | 依赖 | 风险 |
|---|---|---|---|
| T1 | `BusinessNoType.CHANNEL` | 无 | 极低（纯加枚举值；`BusinessNosTest` 遍历枚举做并发唯一性，自动纳入） |
| T2 | `common-dto` 契约 | 无 | 低（纯新增包） |
| T3 | `channel_no` 列 + UNIQUE | T1 | 中（实体 `rehydrate` 有 3 处重载需同步；测试桩需补字段） |
| T4 | `ChannelGateway` 门面 | T2 | 中（6 处调用点替换，须保证行为零变化 NFR-2） |
| T5 | 回调两层 | T2, T4 | 高（涉及回调语义；`PaymentCallbackService.handleCallback` 签名变更波及面广） |
| T6 | 存量 4 家迁移 | T5 | 中（mock 确定性语义须与 `AbstractMockChannelAdapter` 对齐） |
| T7 | ArchUnit 门禁 | T4, T5 | 低（须带阳性对照） |

## 5. 风险与对策

| 风险 | 对策 |
|---|---|
| T4 门面替换改变既有行为 | 机械替换，既有单测断言**零修改**（NFR-2）；若必须改断言则视为设计缺陷回退重评 |
| T5 回调分层破坏现有回调语义 | 先落 `ChannelCallbackHandlerTest` 钉住四步顺序与既有终态行为，再重构 |
| `RefundResultListener` 被取代后 Mock 异步退款链路断裂 | `MockRefundResultBridge` 改掛 `PaymentNotifyPort.onChannelRefundResult`，语义等价 |
| 全仓编译面广（common-core / common-dto 被 10 模块依赖） | 每步结束跑 `./mvnw -B ... -am test` 全量回归 |

## 6. 不做的事

- 不拆 Maven 模块（D4）
- 不引入 MQ / 不改路由算法 / 不动账本科目体系
- 不做 `ChannelModeHandler` 策略化（见 §3）
- 不回填存量数据（系统未上线，D3）
