# Stage-05 交接提示词（037 / 038 / 039）

> **用途**：把后续开发交给另一个 AI 执行。本文是**可直接复制粘贴**的提示词集合。
> **生成时间**：2026-09-25　**基线**：`master` @ `ee9f1b3`　**当前分支**：`docs/038-039-specs` @ `ca1cf5e`
> **项目根**：`C:\Users\user\Desktop\GoProj\PaymentArch`

---

## 0. 待开发 Spec 总览

| # | Spec | 分支 | 状态 | 剩余工作 | 依赖 |
|---|---|---|---|---|---|
| A | **037** 渠道网关边界收口 | `feature/037-channel-gateway-boundary` | **WIP，禁止合入** | T3 剩余 + T4~T8 | 无（最先做） |
| B | **038** payment-service 包边界重构 | 待建 `feature/038-...` | Spec 四件套已完成 | T1~T9 全部 | **037 已合入 master** |
| C | **039** 微信支付渠道插件 | 待建 `feature/039-...` | Spec 四件套已完成 | T1~T8 全部 | **038 已合入 master** |

### 0.1 执行顺序（**必须按顺序，不要并行**）

```
037 续完 → 合入 master → 038 包重构 → 合入 master → 039 微信插件 → 合入 master
```

**为什么不能换序**：

- 037 的 T4~T8 改的文件（`payment/application/channel/**`、`PaymentRefundService`、`ChannelQueryService` 等）在 038 时会被**整包搬进 `channelgateway`**。先做 037，`git mv` 会连改动一起搬走；反过来先做 038，037 spec 里写的所有路径全部失效。
- 037 的 T6 要求"5 家渠道全部 `extends AbstractChannelPlugin`"，其中含 WECHAT。**039 明确要求 037 跳过 WECHAT**（039 会一步到位按新结构写微信插件），否则两个分支在 `WechatChannelAdapter` 上撞车。
- 039 新增插件包的落点写成双分支兜底：`com.payment.channelgateway.infra.wechat`（038 合入后）／`com.payment.payment.infra.channel.wechat`（038 未合入）。走推荐顺序时取前者。

### 0.2 各 Spec 的四件套位置

```
docs/specs/stage-05-channel-and-finance-deepening/
├── 037-channel-gateway-boundary/          # ⚠️ 只在 feature/037-* 分支上，master 没有
│   ├── spec.md  plan.md  tasks.md  acceptance.md
├── 038-payment-service-package-boundary/  # 在 docs/038-039-specs 分支，master 也没有
│   └── spec.md  plan.md  tasks.md  acceptance.md
├── 039-wechat-pay-channel-plugin/         # 同上
│   └── spec.md  plan.md  tasks.md  acceptance.md
└── HANDOFF-PROMPTS.md                     # 本文
```

> ⚠️ **038 / 039 的四件套尚未合入 master**。执行方开工前必须先 `git checkout docs/038-039-specs` 或把这两个目录 cherry-pick 到自己的工作分支，否则读不到 spec。

---

## 1. 共享约定（**三份提示词都要原样带上**）

> 下面这段是三份提示词的公共部分。复制提示词时，把它拼在每份任务专用提示词的后面。

```text
## 项目环境（必读）

- 项目根：C:\Users\user\Desktop\GoProj\PaymentArch（Windows + Git Bash）
- Maven wrapper：./mvnw（不要直接用 mvn）
- 唯一稳定绿 CI：./mvnw -B verify（约 6 分钟）

## 硬红线（违反即停工）

- 跨领域直接改他领域数据 / SQL 他领域表。
- 核心领域（Payment/Order/Ledger）依赖具体渠道实现（Payment ≠ Channel）。
- 金额用 float/double；资金变动绕过 ledger 复式记账直改余额。
- 资金入口无幂等键；散落直改 status 绕过状态机。
- 无理由新增微服务 / 中间件；引入 2PC/XA 分布式事务。
- **删测试或改测试迎合错误实现**（唯一例外：命名/包路径变更导致的 import 修正）。
- 擅自改领域模型 / 状态机 / 服务边界 / 数据库结构 / 公共 API（属宪法人类决策边界）。

## Git 纪律

- 禁用 git rebase。
- git 传路径用正斜杠 C:/... 风格。
- 改动前先 git pull --ff-only。
- 每个 Spec 完成立即 --no-ff 合入 master，不跨 Spec 堆积。
- 本机无 gh CLI → 合入走本地 git merge --no-ff + push（等价 merge commit）。

## 常用命令

- 单测（带 -am 保证依赖模块重新构建）：
  ./mvnw -B -pl common/common-core,common/common-dto,payment-service,deployment/architecture-tests -am test
- 只跑 payment-service：
  ./mvnw -B -pl payment-service -am test
- 文档治理校验（改完 docs/ 必跑）：
  deployment/output/tools/adr-xref-check.py

## 已知环境噪声（不要修，不要当失败）

- 红在 architecture-tests 的 AccountingVocabularyBoundaryTest 时，先看违规路径是不是
  .workbuddy/**。该 ArchUnit 扫全仓 .java 未排除点目录，把 gitignored 的
  .workbuddy/p3-removed-refund-service/ 当模块源码。
  → 不要改测试、不要删 .workbuddy/（项目数据目录）。不计入本 Feature 失败。
- Feign read-timeout=1000ms：首次调渠道适配器约 1.05s 超时，重跑即过，不是 bug。

## 全链路启动（用户要求"做完起全链路测"）

1. Docker Desktop 必须处于 Running。
2. 宿主模式：deployment/start-all.sh　（容器模式：start-container.sh）
3. 首次或重置后必须跑 deployment/demo/reset.sh（重放 schema 建表 + 种子；本项目无 Flyway/ddl-auto）。
4. 端口 8081–8091 两模式共用；演示控制台 mock-channel-web:8091，/demo/trace?orderId= 查全链路。
5. 只验 /demo/trace 时只需起 mysql + mock-channel-web，不必起全部 10 个进程。

## 停工条件（遇下列情况停下问人，不要自行决定）

- 需要改公共 API 路径、领域模型、状态机、服务边界、数据库结构。
- 需要新建 ADR 或改宪法 (.specify/memory/constitution.md)。
- 发现 Code ≠ System Design 的漂移（按 AGENTS.md 报 DOCUMENTATION_DRIFT，不得自行改写 L0）。
- Spec 内部自相矛盾或与代码事实冲突。
```

---

## 2. 提示词 A — 037 续完（渠道网关边界收口）

```text
你是一个 Java / Spring Cloud 项目的执行工程师。请完成 PaymentArch 项目的 Spec 037
「渠道网关边界收口」的剩余开发。

## 0. 起始状态（已核实，不要重做）

项目根：C:\Users\user\Desktop\GoProj\PaymentArch

git checkout feature/037-channel-gateway-boundary
git pull --ff-only   # 若失败先停下报告

该分支相对 master 已改 39 个文件（+968 / -31），HEAD commit message 标注了
「禁止合入 master」。已完成与未完成的边界如下：

【已完成，且测试已绿，不要重做】
- T1  BusinessNoType 增加 CHANNEL("CH")；BusinessNosTest 绿 8/8
- T2  common/common-dto 新建 com.payment.common.dto.channel 包共 15 个契约 record
      （ChannelPayCommand / ChannelRefundCommand / ChannelQueryCommand /
       ChannelPayReceipt / ChannelRefundReceipt / ChannelQuerySnapshot /
       ChannelPayNotified / ChannelRefundNotified / ChannelPayStatus /
       ChannelRefundStatus / Goods / Payer / CallbackUrls / PaymentScene / PayCredential）
      ；ChannelContractTest 绿 9/9；payment-service 367 全绿
- T3 一半
      · 03-payment-schema.sql 加 channel_no VARCHAR(32) NOT NULL + UNIQUE KEY uk_attempts_channel_no
      · PaymentAttempt 实体加 final String channelNo（6 参构造显式传，5 参兼容构造自动生成）
      · PaymentAttemptChannelNoTest 绿 7/7

【未完成，这才是你要做的】
- T3 剩余：持久化层没有映射 channel_no —— 这是当前禁止合入的唯一原因
      · PaymentAttemptEntity 无 channelNo 字段（实测只有 paymentNo/channelCode/attemptType/
        amountMinor/currencyCode/requestedAt/respondedAt/channelReference/status/failureReason/
        retryCount/errorType/extraJson）
      · MybatisPaymentAttemptRepository 的 INSERT / UPDATE 未含 channel_no 列
      · 结果：DDL 是 NOT NULL 而持久层不写该列 ⇒ 所有 payment_attempts 插入失败
      · PaymentAttempt.rehydrate(...) 的调用点需一并传 channelNo（Payment.java 1 处、
        PaymentAttempt.java 6 处、MybatisPaymentAttemptRepository.java 1 处、测试若干处）
      · 移除 ChargeRequest.attemptId（已被 channelNo 取代）
- T4~T8：一行没动

## 1. 你必读的文件（按顺序）

1. AGENTS.md（项目硬规则与上下文加载策略）
2. docs/specs/stage-05-channel-and-finance-deepening/037-channel-gateway-boundary/spec.md
3. .../037-channel-gateway-boundary/plan.md
4. .../037-channel-gateway-boundary/tasks.md        ← 主执行清单，逐条打勾
5. .../037-channel-gateway-boundary/acceptance.md   ← 验收判据 SC-001~xxx
6. payment-service/src/main/java/com/payment/payment/application/channel/spi/AbstractChannelPlugin.java
   （插件模板方法内核，先读明白再动）

## 2. 执行方式

- TDD：每个 Task 严格红 → 绿 → 重构 → 验证。先写失败测试，再实现。
- 只跑单测，不启全链路（除非 Task 明确要求）。
- 每完成一个 T，把 tasks.md 里对应的 - [ ] 改成 - [x]，并跑一次全量单测确认零回归。
- 最小变更，不静默顺带改无关代码。

## 3. 顺序

T3 剩余 → T4 → T5 → T6 → T7 → T8

## 4. 关键约束（037 专属）

- T6 要求「5 家渠道全部 extends AbstractChannelPlugin」，但 **WECHAT 必须跳过**。
  原因：039（微信支付插件）会一步到位按新结构写微信插件，037 若同时改
  WechatChannelAdapter 会与 039 撞车。MOCK / ALIPAY / DOUYIN / STRIPE 照常迁移。
- T8 的 ArchUnit 门禁当前只能写成「禁依赖某些具体类」（硬编码渠道名），这是已知脆弱点。
  **不要试图在 037 里解决**——038 做完包级切分后才有条件写成包级规则。
- 既有单测断言必须零变化（NFR-2）。若某个断言必须改，停下报告，不要自己改。

## 5. 完成判据（逐条自查，全部通过才算完）

- [ ] grep -rn "channel_no" payment-service/src/main/java | wc -l  非零
      （PaymentAttemptEntity 字段 + MybatisPaymentAttemptRepository INSERT/UPDATE）
- [ ] grep -rn "attemptId" payment-service/src/main/java --include=ChargeRequest.java  为空
- [ ] ./mvnw -B -pl common/common-core,common/common-dto,payment-service,deployment/architecture-tests -am test  全绿
      （唯一允许的红是 .workbuddy/** 环境噪声，见共享约定）
- [ ] deployment/demo/reset.sh 重放建表通过（验证 channel_no NOT NULL 不炸）
- [ ] tasks.md 全部打勾；acceptance.md 实测结论已回填
- [ ] docs/adr/README.md 与 traceability.md 已同步（若新增 ADR）

## 6. 收口

全部绿了之后，去掉 commit message 里的「禁止合入」标记，把 feature/037-channel-gateway-boundary
以 --no-ff 合入 master 并 push。合入前再跑一次全量单测。
```

---

## 3. 提示词 B — 038（payment-service 包边界重构）

```text
你是一个 Java / Spring Cloud 项目的执行工程师。请完成 PaymentArch 项目的 Spec 038
「payment-service 包边界重构」。这是**纯结构重构，零行为变化**。

## 0. 起始状态

项目根：C:\Users\user\Desktop\GoProj\PaymentArch

前置条件：**037 必须已合入 master**。先确认：
  git log --oneline -5 master   # 应能看到 037 的 merge commit
若 037 未合入，停下报告，不要开工。

建分支：
  git checkout master && git pull --ff-only
  git checkout -b feature/038-payment-service-package-boundary

038 的 Spec 四件套在分支 docs/038-039-specs 上（master 没有），先取回：
  git checkout docs/038-039-specs -- \
    docs/specs/stage-05-channel-and-finance-deepening/038-payment-service-package-boundary

## 1. 为什么做这件事（背景，帮你判断边界）

【现状】com.payment.payment 与 com.payment.refund 两个顶层包**双向循环 import**：
  · payment 包有 4 个文件 import com.payment.refund
  · refund 包有 10 个文件 import com.payment.payment
双向 import = 包边界失效的直接证据。这不是两个限界上下文，是一个域被切成了两半且切错了地方。

症状（同一职责被写成两份）：
  · 两份退款应用服务：payment/application/PaymentRefundService 与 refund/application/RefundApplicationService
  · 两份同名接口 LedgerPostingGateway：payment/application 与 refund/application 各一份
  · 两套退款 HTTP 入口：/internal/payments/refund-* 与 /internal/refunds/*
  · 渠道网关件 40 个主源码文件散落在 payment 域的 5 个子包里，
    导致 037 的 ArchUnit 门禁只能硬编码渠道名（脆弱）

【目标】一级包收敛为两个域：
  com.payment.payment        —— 资金动作域（Payment/PaymentAttempt/Refund 同域）
      api / application / domain / infra
      application 下按**操作**切片：pay / refund / query / reliability
      ⚠️ 注意：refund 是 payment 域内的**操作切片**（与 pay、queryOrder、queryRefund 同级），
         不是独立子域。domain 层的 Refund/RefundItem/RefundPolicy 与 Payment/PaymentAttempt
         平铺在 payment/domain 下，不单独建 payment/domain/refund 子包。
  com.payment.channelgateway —— 渠道网关域（进程内微服务边界）
      api / application/spi / domain / infra/{mock,wechat,alipay,douyin,stripe} / web
  com.payment.posting        —— 跨切面，保留独立不动

## 2. 你必读的文件

1. AGENTS.md
2. docs/specs/stage-05-channel-and-finance-deepening/038-payment-service-package-boundary/spec.md
   ← 里面有**三张逐文件移动映射表**（渠道 40 个 / refund 33 主 + 10 测试 / 渠道测试 8 个），
     照表执行，不要自己臆测目标路径
3. .../038-payment-service-package-boundary/plan.md   ← 目标包树 + 7 条决策 + 6 类风险
4. .../038-payment-service-package-boundary/tasks.md  ← T1~T9 主执行清单
5. .../038-payment-service-package-boundary/acceptance.md ← SC-001~008，含**已实测**的回归基线

## 3. 执行方式

- 用 git mv 移动（保留历史），不要 cp + rm。
- 纯机械移动优先，分批做，每批结束立刻编译验证：
    ./mvnw -B -pl payment-service -am test-compile
- 每完成一个 T 打勾并跑全量单测。

## 4. 最大坑（最容易翻车，务必先看）

**PaymentApplication 显式枚举了组件扫描包**。新建 com.payment.channelgateway 后必须在
@SpringBootApplication 的 scanBasePackages 里登记，漏了会导致**启动即崩而编译不报错**。
这是 T2，做完 T1 立刻做，不要拖到最后。

## 5. 顺序

T1 建 channelgateway 包迁 40 个渠道主源码
 → T2 PaymentApplication 扫描包登记（**紧接 T1，先于一切**）
 → T3 迁渠道测试 8 个
 → T4 消灭 com.payment.refund（33 主 + 10 测试）
 → T5 退款 HTTP 入口收口 + 6 个调用方同步
 → T6 ArchUnit 三条包级门禁
 → T7 全量单测零回归 + L0 文档同步
 → T8 起全链路验证
 → T9 收口

## 6. 关键约束（038 专属）

- 037 新增的类（如 ChannelGateway）落在 payment/application/ 下，T1/T4 时**一并搬进
  channelgateway 或对应新包**，不要留在旧位置。
- T5 退款入口收口属公共 API 变更，spec 里已列全 6 个调用方（reconciliation 的 Feign、
  mock-channel-web 的回调代理、E2E、两个 scenario 脚本、demo 页面）。
  **一个都不能漏**，漏了就是运行时 404 而编译不报错。
- T6 三条门禁必须**先落阳性对照**（故意违规能触发），否则规则可能空转。
- SC-001 的 grep 判据**限定在 payment-service/src/**。仓库里 .workbuddy/p3-removed-refund-service/
  下仍有 com/payment/refund/... 源码，不限定路径必然非零（已知，不必修）。

## 7. 完成判据（逐条自查）

- [ ] grep -rn "com\.payment\.refund" payment-service/src | wc -l  为 0
      （注意限定 payment-service/src，见上）
- [ ] payment-service/src/main/java 下存在 com/payment/channelgateway 与 com/payment/payment 两个一级包
- [ ] ./mvnw -B -pl common/common-core,common/common-dto,payment-service,deployment/architecture-tests -am test 全绿
      回归基线（master @ ee9f1b3 实测）：common-core 68 / common-dto 15 / payment-service 348 / architecture-tests 17
      允许红：.workbuddy/** 环境噪声（1 处）
- [ ] T8 全链路：Docker Running → start-all.sh → reset.sh → 下单支付退款闭环通过
- [ ] tasks.md 全部打勾；acceptance.md 实测结论已回填
- [ ] 若涉及 L0 文档（docs/architecture/systems/payment-service.md）漂移，按 AGENTS.md
      报 DOCUMENTATION_DRIFT，**不要自行改写 L0**

## 8. 收口

--no-ff 合入 master 并 push。
```

---

## 4. 提示词 C — 039（微信支付渠道插件）

```text
你是一个 Java / Spring Cloud 项目的执行工程师。请完成 PaymentArch 项目的 Spec 039
「微信支付渠道插件」。

## 0. 起始状态

项目根：C:\Users\user\Desktop\GoProj\PaymentArch

前置条件：**038 必须已合入 master**。先确认：
  git log --oneline -5 master   # 应能看到 038 的 merge commit
若 038 未合入，停下报告，不要开工。

建分支并取回 Spec 四件套（master 上没有）：
  git checkout master && git pull --ff-only
  git checkout -b feature/039-wechat-pay-channel-plugin
  git checkout docs/038-039-specs -- \
    docs/specs/stage-05-channel-and-finance-deepening/039-wechat-pay-channel-plugin

## 1. 最重要的一条结论：微信沙箱**实测不可用**，不要再去试

已用 curl 实测（2026-09-25），证据如下：

| 探测 | 结果 |
|---|---|
| V2 沙箱 POST https://api.mch.weixin.qq.com/xdc/apiv2sandbox/pay/getsignkey | HTTP 200，返回「商户号非法」→ 端点活着但要**真实商户号** |
| V3 沙箱 POST https://api.mch.weixin.qq.com/v3/sandboxnew/pay/transactions/native | **HTTP 404** → V3 根本没有沙箱 |
| V3 正式 GET https://api.mch.weixin.qq.com/v3/certificates | HTTP 401 SIGN_ERROR → 要真实证书 |
| 官方《支付验收指引》ch23_1 | 「只支持付款码支付……下单接口 /pay/unifiedorder **暂不支持**」 |

三条理由缺一不可：协议错配（沙箱只有 V2，本项目写 V3）／V3 沙箱 404／场景错配
（沙箱只覆盖线下被扫 micropay，本项目是电商下单）。

结论：支付宝沙箱 ✅、Stripe test mode ✅、**微信是唯一没有联调环境的渠道**。
因此**不要**把「跑通微信沙箱」当验收标准，也不要花时间反复调试凭据。
改用 spec 里定义的三层替代验证：签名金标准单测 + 回调往返单测 + 本地仿真桩。
真商户 0.01 元闭环是**可选**，不是必需。

## 2. 你必读的文件

1. AGENTS.md
2. docs/specs/stage-05-channel-and-finance-deepening/039-wechat-pay-channel-plugin/spec.md
   ← 含微信沙箱实测结论、状态映射表、FR-001~014、INV-1~5
3. .../039-wechat-pay-channel-plugin/plan.md    ← 7 类风险与对策
4. .../039-wechat-pay-channel-plugin/tasks.md   ← T1~T8 主执行清单
5. .../039-wechat-pay-channel-plugin/acceptance.md ← SC-001~008 + 已知限制 4 条
6. payment-service/src/main/java/com/payment/channelgateway/application/spi/AbstractChannelPlugin.java
   （038 合入后的新位置；模板方法内核，先读明白）
7. payment-service/src/main/java/com/payment/channelgateway/infra/stripe/
   （Stripe 插件是**同构参照实现**，照它的结构写微信）

## 3. 执行方式

- TDD：红 → 绿 → 重构 → 验证。只跑单测，不启全链路（T7 除外）。
- 与 Stripe 插件保持同构：Gateway（渠道端口）+ SdkGateway（SDK 封装）
  + Properties（配置 + 启动期强校验）+ Plugin（extends AbstractChannelPlugin）+ Factory（SPI）。

## 4. 顺序

T1 SDK 依赖 + WechatPayProperties
 → T2 WechatSdkGateway：V3 签名 + HTTP
 → T3 WechatChannelPlugin + Factory
 → T4 parseCallback：验签 + 解密
 → T5 本地仿真桩 + 四步全链路
 → T6 全量回归 + L0 文档同步
 → T7 起全链路验证
 → T8 收口

## 5. 关键约束（039 专属）

- SDK 坐标已定：com.github.wechatpay-apiv3:wechatpay-java:0.2.17（已查 Maven Central 确认存在）。
- 密钥**只走环境变量**，禁止落盘；enabled=true 时启动期强校验（照抄 StripeSandboxProperties 的写法）。
  环境变量命名照 spec：PAYMENT_WECHAT_*。
- 插件落点：038 已合入 → `com.payment.channelgateway.infra.wechat`。
  若 038 未合入才落 `com.payment.payment.infra.channel.wechat`（spec 已写双路径兜底）。
- **账本必补两行 seed**：渠道维度账户是 seed 行本身，AccountResolver 对 CHANNEL 维度缺户
  **直接 fail fast**（LEDGER_CHANNEL_UNKNOWN）。每接一家渠道必须在
  deployment/schema/09-ledger-schema.sql 与 031-ledger-accounting-foundation.sql
  **同步补两行**：CHANNEL_RECEIVABLE / CHANNEL_FEE_EXPENSE，owner = WECHAT。
  不补则首笔记账即崩（Stripe 是 id 15/16，照它的格式加 17/18）。
- 现有 payment/infra/channel/WechatChannelAdapter.java（或迁移后的位置）是老的 MOCK 风格适配器，
  039 要写成真正的插件。处理好新旧替换，别留下两个 WECHAT 实现。
- 回调必须带 channelCode；验签不能 return true 了事（这是既有技术债，微信侧不要复制该债）。

## 6. 完成判据（逐条自查）

- [ ] grep 确认微信插件 5 个类齐全（Plugin / Factory / Gateway / SdkGateway / Properties）
- [ ] grep -rn "WECHAT" deployment/schema/09-ledger-schema.sql
      deployment/schema/031-ledger-accounting-foundation.sql  各命中账户 seed 行
- [ ] 签名金标准单测通过（与官方 SDK 签名算法对齐）
- [ ] 回调往返单测通过（验签 + 解密 + 状态映射）
- [ ] 本地仿真桩四步全链路通过
- [ ] ./mvnw -B -pl common/common-core,common/common-dto,payment-service,deployment/architecture-tests -am test 全绿
      （允许红：.workbuddy/** 环境噪声）
- [ ] T7 全链路：Docker Running → start-all.sh → reset.sh → 选 WECHAT 渠道下单支付闭环（走仿真桩）
- [ ] tasks.md 全部打勾；acceptance.md 实测结论已回填，已知限制 4 条如实记录

## 7. 收口

--no-ff 合入 master 并 push。
```

---

## 5. 给执行方的三条通用提醒（写在哪份提示词里都行）

1. **先读 spec，不要凭假设动手**。四件套里 `spec.md` 是 FR/INV/SC 的权威源，`tasks.md` 是执行清单，
   `acceptance.md` 是验收判据。三者冲突时以 `spec.md` 为准，并把冲突报告给人类。
2. **编译通过 ≠ 完成**。本仓库有两类"编译不报错但运行时崩"的坑：
   组件扫描包漏登记（`PaymentApplication`）、Feign 路径变更漏改调用方（038 T5 的 6 个）。
   改完必须 grep 全仓复核。
3. **发现文档漂移不要自己改 L0**（`docs/architecture/technical-solution.md`、`systems/*.md`）。
   按 `AGENTS.md` 报 `DOCUMENTATION_DRIFT`，列出 Evidence + 各层文档描述 + 需人工确认的问题。
