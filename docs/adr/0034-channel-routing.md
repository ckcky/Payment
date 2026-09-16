<a id="adr-0073"></a>

# ADR-0073: 支付渠道路由——注册表 + 规则化确定性选路（spec 028 立项）

- 状态：✅ **Accepted**（2026-09-16 提出，2026-09-16 负责人裁决接受并随 spec 028 落地）
- 关联：**ADR-0072（payment-service 两层结构——提供「渠道必须有身份」这一前提）**、ADR-0049（Mock 场景配置化，「配错不许静默走默认」的纪律沿用）、ADR-0012（双响应码错误分类）、ADR-0063（跨系统一律业务单号）、ADR-0064（一交易多支付单 / 显式选渠道；本 ADR supersede 其 spec 015 §8 第 1 条「不做注册表」）、spec 015、spec 022（确定性故障注入先例）
- 需求源头：负责人 2026-09-16「我想把支付路由这个功能加上」。

## 背景

### 一、「路由」在文档里早已存在，在代码里从未存在

| # | 事实 | 证据 |
|---|---|---|
| G1 | `channelCode` 是调用方传入且**必填**的 String，路由决策权在 order-service | `CreateOrderPaymentRequest(@NotBlank String channelCode)` |
| G2 | 三渠道是**名义上的**：`ALIPAY/WECHAT/DOUYIN` 只影响渠道引用的前缀字符串，走同一 Bean、同一套 mock 语义 | `MockChannelAdapter.channelPrefix()` |
| G3 | spec 015 §8 **明文否决过注册表**：「不做 `Map<ChannelCode, PaymentChannel>` 注册表；不改 channelCode 为枚举」 | `015-multi-channel-payment/spec.md` §8 第 1 条 |
| G4 | 但 `payment-service.md` §2.1 已写 channelCode「必须已注册到渠道 **Registry/Router**」——文档在描述一个**不存在**的组件 | `docs/architecture/systems/payment-service.md` §2.1 |
| G5 | **无 per-channel 可用性 / 健康 / 成功率概念**；熔断（Resilience4j）只包 Feign 出站 RPC，不是渠道健康 | `payment-service/pom.xml` + `application.yml` |
| G6 | 渠道端口无身份（`PaymentChannel` 无 `channelCode()`）、实现层只有一个 `MockChannelAdapter` | ADR-0072 §现状 S3 / S4 |

**G6 是前置依赖**：渠道没有身份，注册表就没有 key 可挂。故本 ADR 依赖 **ADR-0072 先确立两层结构与渠道身份**，两者由同一份 spec（028）承载落地。

> **与 ADR-0072 的关系**：ADR-0072 定结构（两层 + 渠道身份），本 ADR 定选路规则。两者由同一份 spec（028）承载，**必须一并落地**——先拆三渠道再回头收口，等于把同一批文件动两遍，且中间态（三个渠道实现却仍由 payment 层写 attempt）比现状更别扭。

## 决策

**D1. 语义范围 = 渠道路由骨架（选路），不做自动降级、不做智能路由、不做多商户号。**

一期只解决「由谁决定走哪个渠道」与「渠道如何被解析到实现」，不解决「渠道失败后自动改选」。理由：前者是后两者的共同前置；后两者分别在「已拍板不变量」与「数据源缺失」上被阻塞（见备选方案表）。

**D2. `channelCode` 在调用侧变为可选，且显式指定优先。**

- 传了 → 校验**已注册**后原样使用，Router 不干预（保住 spec 015 的 INV-2「换渠道由调用方发起」）；
- 未传 → 由 Router 按配置规则选出一个已注册渠道；
- `routing.enabled=false` 时回落旧行为（必填）。

> 口径细节：显式指定**只校验「已注册」，不校验「enabled」**。`enabled` 只影响自动选路——它的语义是「别自动挑我」，而不是「禁止使用」。这样 Router 对显式意图保持零干预，语义边界清晰。

**D3. 一期选路依据 = 配置化 `enabled` + `priority` 单选，零新依赖。**

过滤 `enabled=true` 后取 `priority` 最小者；候选为空则 409 `NO_AVAILABLE_CHANNEL`。不引入规则引擎、不引入金额 / 币种条件（留挂点）、不引入中间件（Constitution 禁止无理由新增）。

**D4. 分层落点：`ChannelRegistry` / `ChannelRouter` 为 `application/channel` 层端口，实现与各渠道 Adapter 在 `infra/channel` 层。**

Router **只产出 `channelCode`（字符串），不接触渠道协议**，从而不破 `Payment ≠ Channel`。ArchUnit 新增断言：`application/channel/**` MUST NOT 依赖 `infra/channel/**`。

**D5. 路由必须确定性。**

相同 `RouteContext` 必返回相同结果。一期**禁止随机数与进程级计数器参与选路**。

> 这条是被 spec 015 的幂等键口径逼出来的：`idempotencyKey = "payment:" + orderNo + ":" + channelCode + ":" + attemptSeq`。若路由结果会漂移，同一订单的重试会落到不同渠道、产生不同幂等键，于是「同一笔支付」被拆成多张支付单。**将来要做权重分流，权重因子必须取自请求内稳定字段（如 orderNo 哈希），不得用计数器或随机数。** 这是本 ADR 最有长期价值的约束。

**D6. 前向「选路」与反向「按记录解析」严格分离。**

- **前向（选路）**：新支付单由 Router 产出 `channelCode`，且必须发生在**建单之前**——以最终 code 参与幂等键构造与支付单落库，**不得先落库再改写 `channel_code`**（避免「建单用 A、请求发往 B」的不一致窗口）；
- **反向（按记录解析）**：退款 / 重试 / 主动查询**必须**使用 `payment_attempts.channel_code` 记下的那个渠道，**禁止重新路由**。

> 反向解析是资金安全红线：退款换渠道 = 钱退错地方。这条同时消除 ADR-0072 §现状 S5（退款尝试渠道硬编码 `"mock"`），并要求 `attempt_type=REFUND` 行的渠道取自被退支付单的生效支付渠道。

**D7. 兼容优先：保留既有单通道构造重载，保证既有测试零改动。**

`PaymentApplicationService` 的旧构造内部包装为「单通道注册表 + 恒等路由」。沿用 spec 015 FR-023 的既有纪律（当时为「不改构造签名，保证既有测试零改动」）。全仓 **10 个**测试文件直接 `new MockChannelAdapter(...)` 注入，不设重载即需全量改动。

**D8. 启动期强校验，禁止静默降级。**

重复的 `channelCode` 装配、未知渠道码、非法 `priority`、全渠道禁用 → **Bean 创建失败并列出合法取值清单**。沿用 ADR-0049 第 2 条的结论：静默走默认是最难排查的假绿。

## 备选方案与否决理由

| 备选 | 否决理由 |
|---|---|
| 自动降级 failover（渠道失败自动改选其他渠道） | 撞 spec 015 的 **INV-2**：换渠道被拍板为「调用方新建支付单、旧单保留 FAILED、不动订单」。自动改选等于把决策权从 order 挪到 payment，属宪法 Governance 的人类决策边界，不能顺手做 |
| 智能路由（按成功率 / 成本 / 延迟打分） | G5：**没有任何 per-channel 成功率数据源**，也没有 Registry 骨架，做了就是空中楼阁。应在骨架落地并积累数据后重新立项（可挂到 AI 系列之后） |
| 多商户号 / 通道账号路由 | 项目**无商户号模型**（`merchant` 甚至无独立数据源），需先建一整层模型，改动面远超本特性 |
| 改 `channelCode` 为枚举 | spec 015 FR-022 已明确保持 String；改枚举会让既有测试与脚本全面失配，且路由需要可配置的渠道集合，枚举反而僵化 |
| 在 `PaymentChannel` 端口上加 `String channelCode()` | **已改判**：曾以「侵入现有接口，全仓 4 个测试桩要跟着改」为由否决，改用独立 `ChannelProvider` 接口。经负责人 2026-09-16 裁决「渠道层负责具体渠道的实现和抽象，payment 层不关心渠道实现」后，**身份属于端口**才是正确结构——该议题已由 **ADR-0072 决策 3** 确立，测试桩改动作为重构成本接受，本 ADR 不再另设 `ChannelProvider` |
| Spring 自动注入 `Map<String, PaymentChannel>` 直接用 bean 名当渠道码 | bean 名（如 `mockChannelAdapter`）不是渠道码，靠命名约定隐式耦合；显式 `channelCode()` 才能做到启动期唯一性校验 |
| 一期上规则引擎 / 金额·币种条件表 | 无真实需求驱动，且会引入依赖；`RouteContext` 已预留 `amountMinor` / `currencyCode` 字段，将来加条件不改端口签名 |
| 用 Nacos 下发路由配置 | 一期走本地 `application.yml` 即可，Nacos 覆盖留待需要时；避免把配置中心耦合进选路路径 |

## 影响

- **正影响**：`payment-service.md` §2.1 的「Registry/Router」悬空描述**落地为事实**（消除文档漂移）；三渠道从「字符串前缀不同」变成「真的三个实现」；demo 可演示「不指定渠道 → 平台选路」；为将来的自动降级与智能路由备好插槽（唯一需要替换的是 Router 实现）。
- **代价**：payment-service 新增 `ChannelRegistry` / `ChannelRouter` / `RouteContext` 三个类型 + 2 个实现；`application.yml` 新增 `payment.routing.*` 配置段与一个 `@ConfigurationProperties`；Router / Registry 需新增单测与 ArchUnit 断言。
- **对既有决策的影响**：**Supersede spec 015 §8 第 1 条**（「不做 `Map<ChannelCode, PaymentChannel>` 注册表」→「一期不做，spec 028 引入」）。spec 015 其余条款（INV-1 / INV-2 / 幂等键口径 / 三渠道 mock / 不做枚举）**全部保持不变**。
- **不做**：自动 failover、智能路由、多商户号路由、渠道健康探测与自动熔断降级、真实渠道 SDK 接入、改 `channelCode` 为枚举、引入 MQ 或新中间件。

## 落地

由 **spec 028** 承载（与 ADR-0072 的两层结构重构一并实施——两者动的文件高度重叠，分两次做等于把同一批文件动两遍）。两份 ADR 均属结构变更，实现前须经 `/speckit-plan` 评审。
