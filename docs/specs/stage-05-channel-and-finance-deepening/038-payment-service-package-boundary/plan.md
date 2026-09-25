# Plan: 038-payment-service-package-boundary

**对应 Spec**：[spec.md](spec.md)　**推进方式**：机械移动 + 编译/门禁驱动（非 TDD：本 Feature 是纯结构重构，行为零变化，无新行为可先写测试）
**测试策略**：单测为主；**收尾必须起全链路跑一次 demo 退款场景**（用户要求）

---

## 1. 目标形态

```
com.payment
├── payment/                          # 资金动作域（单一域）
│   ├── api/                          # 入口平铺：Payment / Refund / Channel-free
│   │   └── dto/
│   ├── application/
│   │   ├── refund/                   # 退款操作切片（与 pay 同级，非子域）
│   │   ├── pay…（既有类留 application 根，见 D2）
│   │   └── reliability/
│   ├── domain/                       # Payment + Refund + RefundItem + RefundPolicy + PaymentAttempt 同域平铺
│   ├── infra/  ├── client/  └── persistence/refund/
│   ├── limit/   ├── mq/   └── web/
├── channelgateway/                   # 渠道网关域（进程内微服务边界）
│   ├── api/ + api/dto/               # 通用回调端点、渠道管理端点
│   ├── application/ + application/spi/   # 内核：Plugin / Factory / 模板方法
│   ├── infra/                        # Mock / Wechat / Douyin 适配器 + Registry/Router/Locator
│   │   ├── alipay/    └── stripe/    # 各渠道插件包
│   ├── infra/config/                 # AlipaySandboxProperties / RoutingProperties
│   └── web/                          # ChannelCallbackSignatureFilter
└── posting/                          # 跨切面出站失败台账（spec 034，保持独立）
```

**依赖方向**：`payment → channelgateway`（单向）。`posting` 不参与。

---

## 2. 关键决策与理由

| # | 决策 | 理由 |
|---|---|---|
| D1 | 一级包只留 `payment` / `channelgateway` / `posting` | 「Payment 与 ChannelGateway」是真正的**两个边界**；`refund` 不是域，是 payment 内的一个操作（用户 2026-09-25 裁定） |
| D2 | 只给 `application` 层建 `refund/` 切片，`pay` / `query` 不建对称目录 | pay 侧类已在 `payment/application` 根且被大量单测直接引用；对称迁移收益 < 回归风险。`refund/` 存在是因为它有 10 个来自外部包的类，需要明确归属 |
| D3 | `domain` / `api` / `infra` 平铺吸收 refund 类 | 类名自带 `Refund` 前缀可自辨；与 `Payment` / `PaymentAttempt` 同域平铺才真正表达"一个域" |
| D4 | `ChannelAttemptRecorder` 接口随渠道件迁 `channelgateway.application` | 它是**渠道层定义的端口**（实现在 payment 侧 `ChannelAttemptRecorderImpl`），属合法 DIP；**方向是 payment 实现 channel 定义的接口**，不是反向依赖应用服务 |
| D5 | 本轮**不**合并两份 `LedgerPostingGateway`、不合并两份退款应用服务 | 属行为改动（记账口径 / 职责重排），混入结构重构会让"零回归"无法验证；单列后续 Feature |
| D6 | 先切包再补 037 的门面 / Port 收口 | 037 的 ArchUnit 门禁只有在包边界物理存在后才能写成**包级规则**，否则只能硬编码渠道类名（036 已诊断该脆弱点） |

---

## 3. 主要风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| **R1（最高）** `PaymentApplication` 扫描包漏登记 | 新增顶层包后 Spring 扫不到 → 启动即崩，且**编译不报错** | T2 单列一步，改完立刻 grep 复核三个注解；SC-003 显式断言 |
| **R2** 包名替换顺序错误（`channel` 先于 `channel.spi`） | `spi` 包名被写成嵌套错误 | §3.1 规定**长前缀优先**替换顺序；T1 结束立即编译 |
| **R3** 同包引用未补 import | 原 `payment/api` 包内互相引用无需 import，搬走后编译错 | 036 已踩此坑；对策：**每批移动后立即 `mvn -B compile`**，逐个补 import |
| **R4** 调用方漏改（6 文件 / 8 处） | 对账 / 演示 / E2E 404 | T5 单列，改完 grep 全仓复核（SC-005） |
| **R5** `channelgateway` 反向依赖 `payment.application` | FR-009 ① 门禁报红 | T6 先落阳性对照，再实现规则；若既有代码违规，登记为技术债**不静默放宽规则** |
| **R6** 与 037 分支冲突 | 037 WIP 未合入，改动面重叠 | 038 从 **master** 起分支；037 后续合并时需重新对齐包路径 |

---

## 4. 步骤与依赖

| 步骤 | 内容 | 依赖 | 风险 |
|---|---|---|---|
| T1 | 建 `channelgateway` 包，迁 40 个渠道主源码文件 | 无 | R2 / R3 |
| T2 | `PaymentApplication` 三注解登记新包 | T1 | **R1** |
| T3 | 迁渠道测试 8 个 + 编译 | T1 | R3 |
| T4 | 消灭 `com.payment.refund`：迁 33 主 + 10 测试 | 无（可与 T1 并行，但建议串行避免冲突） | R3 |
| T5 | 退款入口收口 + 6 个调用方同步 | T4 | R4 |
| T6 | ArchUnit 三条包级门禁（含阳性对照） | T1、T4 | R5 |
| T7 | 全量单测零回归 + L0 文档同步 | T1~T6 | — |
| T8 | **起全链路**：容器启动 + `reset.sh` + `scenario-refund.sh` 退款三层可见 | T7 | 环境 |

**建议串行执行 T1→T8**（批量改同一份代码 / 同目录 MUST NOT 并行，避免覆盖）。

---

## 5. 验证命令

```bash
# 编译
./mvnw -B -pl payment-service -am compile

# 全量单测（零回归基线见 acceptance.md）
./mvnw -B -pl common/common-core,common/common-dto,payment-service,deployment/architecture-tests -am test

# 结构断言
grep -rn "com\.payment\.refund" payment-service/src/ | wc -l            # 期望 0
grep -rn "internal/refunds" --include=*.java --include=*.sh --include=*.html . | grep -v "\.workbuddy/" | wc -l   # 期望 0
```

## 6. 全链路验证（T8）

```bash
# 1) 容器
deployment/start-all.sh
# 2) 首次或需要干净数据时重建 schema
deployment/demo/reset.sh
# 3) 退款场景（断言 TXRF / PMRF / payment_attempts REFUND 三层）
deployment/demo/scenario-refund.sh
```

⚠️ 起服务时**必须** `env -u SERVER__PORT -u SERVER__HOST`，否则工具注入的 `SERVER__PORT=61628` 会被 Spring Boot 当作 `server.port`（环境事实，见 `paymentarch-build-and-git`）。
