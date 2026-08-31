# ADR 集合：端到端演示形态（ADR-0048 ~ ADR-0051）

**Feature**：`011-demo-showcase`
**日期**：2026-08-31
**状态**：✅ **Accepted**（2026-08-31 负责人裁决；**ADR-0048 已按裁决修订**——推翻本文原「不做收银台」结论，改为新增 `mock-channel-web` 组件）
**关联**：`docs/specs/011-demo-showcase/spec.md`、`docs/architecture/next-stage-design.md` §4、`0009-risk-security-decisions.md`（ADR-0025）、`0013-channel-callback-signature-decisions.md`（ADR-0052）

> **编号说明**：ADR-0038~0046 是 `next-stage-design.md` §9 的**预留号段**，ADR-0047 已用于退款金额校验口径。
> 本集合从 **ADR-0048** 起编号，与 `docs/adr/README.md` 的「下一可用编号」一致。
>
> **与原提案的对应**：`next-stage-design.md` §9 原列 ADR-0038「演示形态：Mock 收银台 vs 纯脚本」。
> 本 ADR-0048 处理同一议题，但**结论不同**（详见下文「为什么不按原提案做收银台」），故单列新号而非占用 0038。

## 状态总览

| ADR | 标题 | 本期形态 |
| --- | --- | --- |
| **0048** | 演示形态：~~不做 `mock-channel-web`~~ → **新增 `mock-channel-web` 组件（2026-08-31 裁决修订）** | Accepted（修订，待随 Phase 1 落地） |
| **0049** | Mock 渠道场景配置化（`payment.channel.mock-scenario`） | Accepted（已落地） |
| **0050** | 对账演示账单：生成 CSV 写入 `target/classes`，不改生产代码 | Accepted（随 demo 脚本落地） |
| **0051** | 演示脚本纪律：只编排不伪造、断言失败即非零退出 | Accepted（随 demo 脚本落地） |

---

## ADR-0048 演示形态：~~不新增 `mock-channel-web`~~ → **新增 `mock-channel-web` 组件（2026-08-31 负责人裁决修订）**

> **⚠️ 修订（2026-08-31 负责人裁决，本文原结论被推翻）**
>
> 负责人在审阅 `next-stage-design.md` 后裁决：**新增独立收银台组件**，且必须能现场演示
> 「回调 / 签名 / 伪造签名被拒 / 重复重放」，同时**要做演示页面**（最小控制台）。
> 原「不做 `mock-channel-web`」结论自此**不再有效**，本 ADR 保留原文仅作决策演进记录。
>
> **修订后决策**：
> 1. 新增独立 Maven 模块 `mock-channel-web`（端口 8091）——收银台页 + 回调签名转发 + 演示控制台页 + 同源代理。
>    它是**演示组件而非领域服务**：不进 `ServiceBoundaryTest.SERVICES`，不拥有任何业务数据。
> 2. 补齐「跳转 + 回调」链路：`payment.mock-cashier.enabled=true` 时 `createPayment` 跳过渠道内联同步调用，
>    Payment 停留 PROCESSING 等回调，`CreatePaymentResponse` / `CreateOrderResponse` 新增 `payUrl`。
>    （原反对理由①「为演示改生产契约」由负责人裁决接受：改动用配置开关门控，默认关闭，生产路径零影响。）
> 3. ~~验签前置接入：伪造签名被拒依赖真实验签，见 **ADR-0052**~~ —— **2026-08-31 用户确认回退到 ADR-0025 空实现**，ADR-0052 ⛔ Not Implemented（见 `0013`）。
>    故「伪造签名 403」按钮在 `mock-channel-web` 中**无效**（点下去依旧放行），演示时应明确说明「验签尚未接入」。
> 4. CORS 不做：demo 页只与 8091 同源，跨服务读取由组件内代理转发。
> 5. 原四种坏行为的演示方式全部保留：连点两次 / 改金额 / 不回调 / 渠道无结论，
>    另加「重复回调幂等吸收」按钮（「伪造签名 403」因 ADR-0025 占位而取消）。
>
> 下文为原决策记录（历史）。

---

**原决策记录（已被上述修订取代）**

**背景**

`next-stage-design.md` §4.1 提议新增一个 `mock-channel-web` 最小 Web（收银台页面 + 回调转发接口），
把"跳转三方渠道"演出来，并支持四种坏行为（连点两次 / 改金额 / 不回调 / 渠道无结论）。

落地前核对代码，发现两个前提不成立：

1. **当前主链没有 `payUrl`**。`CreatePaymentResponse` 只有 `(paymentId, status)`；
   `PaymentApplicationService#createPaymentIntent` 是**同步**调用渠道并立即应用结果。
   所谓"跳转"在当前架构里并不存在——要演出来，就得给 `PaymentResponse` 加 `payUrl`、
   并把扣款改成"先落 PROCESSING、等回调再推进"。这是**为演示改生产契约与状态机**。
2. **新增模块要过服务边界门禁**。`architecture-tests` 按目录扫描各服务 `target/classes`，
   新增模块必须显式处理（要么加入服务清单、要么排除），否则防空转门禁与边界规则会受影响。

**决策（最简）**

1. **不新增任何服务或 Maven 模块**。
2. 演示的"可交互"部分由 **`demo/cashier/index.html`** 承担——一个**零依赖静态页**，
   浏览器双击即用，直接向 `payment-service` 的 `/internal/payments/{id}/channel-callback` 发请求。
   定位上它是 **Mock 渠道控制台**（渠道侧的人肉触发器），**不伪装成"渠道收银台跳转"**，
   因为当前并不存在跳转链路；叫收银台会误导观众以为系统有异步收银台能力。
3. "跳转 + 回调"的**完整形态**推迟到真正需要时（如接入异步渠道）连同状态机改造一起立项，
   届时再写新 ADR，不在本期为演示而改。

**四种坏行为如何演**

| 坏行为 | 本期演示方式 |
| --- | --- |
| 连点两次「支付成功」 | 控制台连点两次 SUCCESS 回调 ⇒ 第二次被幂等吸收（`payment.duplicate_callback` +1，状态与分录不变） |
| 改金额后伪造回调 | 控制台可改 `amountMinor` ⇒ 演示当前口径「回调金额仅落观测不拦截」（`ChannelCallbackRequest` Javadoc 已声明；金额校验属对账能力） |
| 点了不回调（关页面） | 见 ADR-0049：以 `mock-scenario=BUSINESS_UNKNOWN` 启动 ⇒ 渠道根本不给结论 ⇒ 进 UNKNOWN |
| 渠道无结论 | 回调 `status=UNKNOWN` ⇒ 进 UNKNOWN，**不猜成败落账**（宪章 V.7） |

**为什么不按原提案做收银台**

| 维度 | 新增 `mock-channel-web` | 静态控制台（本决策） |
| --- | --- | --- |
| 生产代码改动 | 新增模块 + 门禁适配 + 可能的 `payUrl` 契约变更 | **零** |
| 宪章契合度 | 「无理由新增微服务」风险（IV 禁止清单） | 不涉及服务边界 |
| 维护成本 | 一个需随架构演进的模块 | 一个 HTML 文件 |
| 演示保真度 | 高（有真实跳转） | 中（跳转型态缺失，但当前架构本就没有） |

**后果**

- 演示形态是"控制台 + 脚本"，不是"商城 + 收银台"。观众看到的是**链路与正确性**，不是 UI。
- 若后续要演示真实异步渠道，必须重新立项并改状态机（本期不预留挂点，避免半截实现）。

---

## ADR-0049 Mock 渠道场景配置化

**背景**

`MockChannelAdapter.Scenario` 在构造期硬编码为 `SUCCESS`（仅测试通过构造参数覆盖）。
结果是**生产进程永远只走成功路径**——UNKNOWN、失败、超时这三条本项目最值得演示的正确性路径，
在不改代码的前提下演不出来。

**决策（最简）**

1. 新增配置项 `payment.channel.mock-scenario`（默认 `SUCCESS`），由 Spring 注入 `MockChannelAdapter`。
2. 取值即 `MockChannelAdapter.Scenario` 枚举名（`SUCCESS` / `FAILURE` / `TIMEOUT` / `TRANSPORT_ERROR` / `BUSINESS_UNKNOWN`），
   **不做字符串别名、不做大小写容错**：非法值直接让 Bean 创建失败并启动报错（FAIL FAST），
   避免"配错了却静默走默认成功"这种最难排查的假绿。
3. **不新增运行期切换端点**。场景是构造期注入的，运行期切换需要新增管理端点——
   那等于给生产加一个"把支付改成失败"的开关，风险远大于收益。
   演示需要换场景时，由 `demo/lib.sh#restart_payment_service <scenario>` 重启服务（约 20s），
   `demo/run-all.sh` 已编排该步骤。

**配置示例**

```bash
./mvnw -pl payment-service spring-boot:run \
  -Dspring-boot.run.arguments="--payment.channel.mock-scenario=BUSINESS_UNKNOWN"
```

**后果**

- 演示 UNKNOWN 场景会重启 payment-service（已记录为 Spec §6 L3 已知限制）。
- `payment.channel.*` 命名空间新增一个键，`docs/operations/runbook.md` §4 环境变量表同步。

---

## ADR-0050 对账演示账单：生成 CSV 写入 `target/classes`，不改生产代码

**背景**

`next-stage-design.md` §4.3 称「`CsvChannelStatementLoader.load(period)` 忽略 `period` 入参，
每日对账是假的」，并将之列为演示前必须修复的缺陷。核对代码后该描述**不成立**：

- `CsvChannelStatementLoader#load` 第 54~59 行**先按** `{dir}/{period}.csv` 定位，命中即使用；
- 未命中才回退 `sample.csv`，且**显式留痕**（WARN 日志 + `reconciliation.statement_fallback` 指标 + `ChannelStatementSource.fallback=true`）。

真正的问题是：classpath 上只有一份 `sample.csv`，所以任何周期都走 fallback——
行为是对的，只是**没有周期素材**。

**决策（最简）**

1. **不改 `CsvChannelStatementLoader` 一行代码**。
2. `demo/scenario-reconciliation.sh` **从平台真实事实生成周期账单**：
   取 `/internal/payments/confirmed-facts` + `/internal/refunds/confirmed-facts`，
   按设计制造四类差异（原样复制 ⇒ MATCH；改金额 ⇒ `AMOUNT_MISMATCH`；
   改状态 ⇒ `STATUS_MISMATCH`；附加渠道独有行 ⇒ `CHANNEL_ONLY`；
   保留一笔平台事实不入账 ⇒ `PLATFORM_ONLY`）。
3. 生成的 CSV 写入 **`reconciliation-service/target/classes/fixtures/channel-statements/{period}.csv`**。
   `spring-boot:run` 的 classpath 根即 `target/classes`，因此 `ClassPathResource` 能**真实命中周期账单**，
   不触发 fallback。
4. 额外跑一次"不存在的周期"，把 ADR-0020 的**回退留痕**也演示出来（断言 `fallback == true`）。

**备选方案与取舍**

| 方案 | 评价 |
| --- | --- |
| 让 loader 支持 `file:` 外部目录（改代码） | 更"生产化"（真实渠道确实投递文件到目录），但属为演示改生产代码；需要时单独立项 |
| 把 demo CSV 提交进 `src/main/resources` | 用 demo 数据污染生产 classpath，且写死日期，不可重复 |
| **写 `target/classes`（本决策）** | 零生产改动；`target` 本就是构建产物目录，语义恰当；缺点是 `mvn clean` 后失效——脚本每次执行都重新生成，不构成问题 |

**后果**

- 对账演示依赖 `mvn` 已至少编译过一次（`target/classes` 存在）。`demo/start-stack.sh` 会先构建。
- `reconciliation-service` 重启后 `target/classes` 仍在（不是 `clean`），素材保持有效。

---

## ADR-0051 演示脚本纪律：只编排不伪造、断言失败即非零退出

**背景**

演示脚本最容易滑向两个坏味道：① 为了"演出效果"直接改库把状态改成想要的值；
② 遇到断言失败只打印 WARNING 继续跑，最后一次 `echo "done"` 收场。
两者都会让"演示通过"变成假象。

**决策（最简，固化为可执行约束）**

1. **只编排，不伪造**：脚本只能调用公开 API 或执行**只读** SQL。
   MUST NOT 直接 `UPDATE` 业务状态、MUST NOT 跳过状态机、MUST NOT 事后回填资金事实。
2. **异常只能来自配置或真实外部行为**：制造 UNKNOWN 用 `mock-scenario`（ADR-0049），
   制造掉单用"不发回调"，制造重复用"发两次"——不通过改库造假。
3. **失败即非零退出**：每个 `scenario-*.sh` 以断言结束，任一步失败立即 `exit 1`。
   禁止"打印警告继续"。
4. **最终态断言一律走 `wait_until`（带超时）**，不做 `sleep` 硬等——避免时序抖动造成的偶发假失败。
5. **每步打印 `curl` 命令 + 响应摘要 + 下一步提示**，让脚本本身即文档。
6. **断言所需的状态尽量走 API**；确无 API 的（如清库）才用 SQL，且 SQL 目标表 MUST 显式列出，
   MUST NOT 出现 `DROP DATABASE` 或通配删表。

**后果**

- 脚本比"能跑就行"的版本更啰嗦（每步都要断言），但这是本 Feature 的核心价值所在。
- `demo/lib.sh` 提供统一的 `assert_eq` / `assert_ne` / `assert_contains` / `assert_status` / `wait_until`，
  新增场景时复用，不各写各的。

---

## 验证

- `mvn -o clean verify -fae` 全量 BUILD SUCCESS（含 `architecture-tests` 边界门禁）。
- `bash demo/reset.sh && bash demo/run-all.sh`：4 个场景断言全通过，退出码 0。
- 实测记录见 `docs/specs/011-demo-showcase/acceptance.md`。
