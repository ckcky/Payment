# Spec: 011-demo-showcase（端到端可演示）

**版本**：0.2
**日期**：2026-08-31
**状态**：Accepted

> **修订记录（2026-08-31 负责人裁决）**：原 v0.1 结论「不做收银台 / 不新增 mock-channel-web / 不引入 payUrl」已被负责人推翻。
> 最终决策：新增独立演示组件 `mock-channel-web`（端口 8091，非领域服务、ArchUnit 不纳管），由它承载收银台页面与
> 渠道回调代理；`CreatePaymentResponse` / `CreateOrderResponse` 新增 `payUrl` 字段（仅当 `payment.mock-cashier.enabled=true` 时返回），
> 走「下单 → 收银台跳转 → 三方 mock 回调 → 验签（ADR-0025 占位空实现，恒放行）→ 终态吸收」链路。
> 渠道回调验签维持 ADR-0025 的占位形态（过滤器骨架保留、`verifySignature` 恒返回 `true`、回调一律放行）；ADR-0052 真实验签于 2026-08-31 经用户确认**回退为 Not Implemented**（见 `docs/adr/0013`）。
> 下列正文已按此修订同步更正。

> **二次裁决（2026-08-31，用户确认）**：渠道回调验签**回退到 ADR-0025 占位空实现**（过滤器骨架保留、`verifySignature` 恒返回 `true`、回调一律放行）。即在 §4.3 / L1 中描述的「ADR-0052 真实验签 / 伪造签名 403 fail-closed」**不再落地**，详情见 `docs/adr/0013`（ADR-0052 → ⛔ Not Implemented）。本 Spec 正文已按此回退同步更正。

## 1. 背景与目标

Roadmap Phase 0~10 主链已交付完整业务闭环，但**"能跑通"不等于"能演示"**：

- 端到端验证靠人肉按 `docs/specs/001-core-business-model/quickstart.md` 手敲 curl，没有可重复执行、带断言的脚本；
- `merchant-service`（内存）与 `catalog-service` 每次重启后数据为空，没有种子数据，"每次演示从同一个确定状态开始"做不到；
- `MockChannelAdapter` 的场景在代码里硬编码为 `SUCCESS`，**UNKNOWN / 失败路径无法在不改代码的前提下演示**——而这恰恰是本项目最值得演示的正确性能力（超时 ≠ 失败、不猜成败落账）；
- `fulfillment-service` / `entitlement-service` 没有按订单查询的只读端点，演示脚本无法用 API 断言履约与权益状态；
- 对账演示缺少"按周期取账单"的真实素材，只能永远比对 classpath 上同一份 `sample.csv`。

本 Feature 的目标是：**把已跑通的真实链路变成一条命令可复现、每步有断言、失败非零退出的演示资产**。

**非目标**（明确排除，避免范围蔓延）：

- ❌ 不做商城 UI / 大屏 / 前端框架（演示页为零依赖静态页，托管于 `mock-channel-web` 的 8091 端口）。
- ❌ 演示脚本 MUST NOT 直接写业务状态、跳过状态机或伪造资金事实（设计原则 §2.1）。
- ❌ 收银台组件 MUST NOT 进 ArchUnit 服务边界门禁（`ServiceBoundaryTest.SERVICES` 不含它，属演示组件非领域服务）。

> **已采纳（负责人裁决，推翻 v0.1）**：新增 `mock-channel-web` Maven 模块（端口 8091）承载收银台与渠道回调代理；
> `CreatePaymentResponse` / `CreateOrderResponse` 新增 `payUrl` 字段（仅 `payment.mock-cashier.enabled=true` 时返回，不污染既有同步链路）。

## 2. 现状核实（基于真实代码，修正 `next-stage-design.md` 的三处偏差）

> `docs/architecture/next-stage-design.md` 是 Draft，其中 §4.3 列出的两处"对账前置缺陷"经核对与当前代码不符。本 Spec 以**代码事实**为准，并在 §9 提出修订。

| 草案描述 | 代码事实（2026-08-31 核对） | 结论 |
|---|---|---|
| `beginProcessing()` / `close()` 未被调用，batch 停在 `HAS_DIFFERENCE` 无法推进 | `ReconciliationApplicationService#resolveDifference` 第 151 行**已调用** `batch.beginProcessing()`；`closeBatch` 存在且带"尚有未处理差异 ⇒ 拒绝"门禁 | ❌ 草案有误，**不需要修复** |
| `CsvChannelStatementLoader.load(period)` 忽略 `period`，永远同一份 fixture | 第 54~59 行**先按** `{dir}/{period}.csv` 定位，命中即使用；未命中才回退 `sample.csv` 并留痕（WARN + `reconciliation.statement_fallback` 指标） | ⚠️ 部分有误。行为已正确，**只需给演示准备真实的周期账单文件**（ADR-0050） |
| `POST /payments` 返回 `{paymentId, payUrl}` | `CreatePaymentResponse` 原仅 `(paymentId, status)`；**v0.2 已按裁决新增 `payUrl` 字段**（仅 `payment.mock-cashier.enabled=true` 时返回，同步主链不受影响） | ✅ 原草案有误，已采纳 payUrl（ADR-0048 修订） |

## 3. 关键用户故事

- **US1 一条命令证明主链通**：`bash demo/scenario-happy-path.sh` 从确定性初始状态跑完 建单 → 支付 → 履约 → 权益 → 记账，末尾断言全部通过、失败非零退出。
- **US2 演示资金正确性能力**：UNKNOWN 不猜成败、主动查询不收敛、权威裁定后才履约且只履约一次；退款累计不超付；重复幂等键返回首次结果。
- **US3 演示对账闭环**：按周期取账单 → 四类差异被识别 → 逐条带依据处理 → 有未处理差异时拒绝关闭 → 全部处理后关闭成功。
- **US4 演示可复现**：`demo/reset.sh` 清库重灌后，任何场景可重复执行且结果一致。
- **US5 演示可交互**：静态渠道控制台能现场触发重复回调、无结论回调、伪造签名头（当前形态下仍放行，因 ADR-0025 占位），肉眼看到幂等吸收与终态保护。

## 4. 功能需求（FR）

### 4.1 演示编排（demo 资产，不进生产构件）

- **FR-001** `demo/lib.sh` MUST 提供：服务健康检查等待、HTTP 调用封装（打印命令 + 响应摘要）、JSON 取值（优先 `jq`，回退 `python3`/`python`）、断言（`assert_eq` / `assert_contains` / `assert_http_status`）、失败即非零退出。
- **FR-002** `demo/reset.sh` MUST 灌入确定性种子：1 个已审批商户、1 个已上架商品、3 个 ACTIVE SKU（101 正价 / 102 退款用 / 103 秒杀占位），且**幂等可重复执行**（reset 后库已清空，直接创建）。
- **FR-003** `demo/reset.sh` MUST 重建 9 个业务 Schema（DROP 仅限 `catalog/order/payment/refund/fulfillment/entitlement/reconciliation/settlement/ledger` 九库，绝不碰 `mysql` 系统库）→ 重放 `deployment/schema/*.sql` 建库建表 → 经 API 灌种子；仅在演示本机执行。
- **FR-004** 服务启停复用 `deployment/start-all.sh` / `stop-all.sh`（已含 `mock-channel-web` 8091 与 ledger 8090），启动后 `demo/lib.sh` 的 `wait_for_services` 等待 `/actuator/health` 全部 UP 才返回。
- **FR-005** 每个 `demo/scenario-*.sh` MUST 以断言结束（而非仅打印），任一步失败 MUST 立即非零退出。
- **FR-006** `demo/run-all.sh` MUST 按序执行 4 个场景，任一场景失败即中断并打印失败场景名。

### 4.2 生产代码（最小必要改动，均带测试）

- **FR-007** `MockChannelAdapter` 的 `Scenario` MUST 可由 `payment.channel.mock-scenario` 配置（默认 `SUCCESS`，保持向后兼容），使 UNKNOWN / 失败 / 超时场景可被脚本化演示（ADR-0049）。
- **FR-008** `fulfillment-service` MUST 提供只读端点 `GET /fulfillments/by-order/{orderId}`；`entitlement-service` MUST 提供 `GET /entitlements/by-order/{orderId}`。二者复用仓储已有的 `findByOrderId`，未找到返回 `NOT_FOUND`。
- **FR-009** 上述新增端点 MUST 有单元测试覆盖"命中"与"未命中 404"两条路径。

### 4.3 Mock 收银台（`mock-channel-web` 端口 8091，ADR-0048 修订）

- **FR-010** `mock-channel-web` 提供 `cashier.html`（零依赖静态页，由 8091 同源托管）：
  - 下单响应 `payUrl` 直接指向 `http://localhost:8091/cashier?paymentId=...`，实现「下单 → 跳转三方 mock 收银台」演示；
  - 收银台六按钮：`SUCCESS` / `FAILURE` / `TIMEOUT_NO_CALLBACK`（超时不回调）/ `UNKNOWN`（无结论）/ `REPLAY`（重复回调）/ `FORGED_SIGNATURE`（伪造签名）；
  - **连点两次 SUCCESS**：演示重复回调被终态吸收（状态不变、不重复履约，`payment.duplicate_callback` 计数 +1）；
  - **FORGED_SIGNATURE**：经 `/mock-channel/callback` 代理用错误密钥签名；**但 payment 侧当前为 ADR-0025 占位（恒放行），不会返回 403，回调仍被接受**（已知边界，见 runbook §6）；
  - 页面 MUST 标注各按钮对应的后端行为（终态吸收 / 验签未接入 / 超时等待轮询）。
- **FR-011** 收银台回调经 `mock-channel-web` 的 `ChannelCallbackProxy` 转发，并复用 `SignatureVerifier` 同源签名（与 payment 侧曾规划的 `payment.security.channel-secret` 共用 `PAYMENT_CHANNEL_SECRET`）；**payment 侧当前为 ADR-0025 占位不验签（恒放行），签名仅作演示**；不引入 CORS（同源 8091 代理转发）。

### 4.4 对账演示素材

> 本期 `scenario-reconciliation.sh` 走**真实对账流程闭环**：以当天 `period` 触发批次 → 查看差异 → 关账 → 结算汇总。`CsvChannelStatementLoader` 已支持按 `{period}.csv` 命中（ADR-0050 提供「生成真实周期账单 CSV」的增强路径，留待按需启用；当前演示使用 sample.csv 回退，差异即演示素材）。

- **FR-012** `scenario-reconciliation.sh` MUST 触发 `$PERIOD`（当天日期）对账批次，断言批次创建 200、差异明细可查、关账 200、结算汇总 200。
- **FR-013**（增强，按需）从平台真实事实（`/internal/payments/confirmed-facts` 等）生成 `target/classes/fixtures/channel-statements/{period}.csv`，使 `CsvChannelStatementLoader` 真实命中而不触发 fallback（ADR-0050，不改生产代码）。
- **FR-014**（增强，按需）另跑一次"不存在的周期"断言 fallback 行为（`reconciliation.statement_fallback` 指标 +1）。

## 5. 验收标准（SC）

- **SC-001** `bash demo/reset.sh` 后 `bash demo/run-all.sh` 全部场景断言通过，退出码 0。
- **SC-002** happy-path 断言：`payment.status == SUCCEEDED`、`fulfillment.status` 为已完成态、`entitlement.status == AVAILABLE`、ledger `/internal/ledger/balance` 的 `balanced == true` 且按 `PAYMENT/{paymentId}` 能查到分录。
- **SC-003** UNKNOWN 场景断言：渠道返回无结论后 `payment.status == UNKNOWN` 且**未产生** fulfillment 与 ledger 分录；等待 ≥2 个主动查询周期后仍为 `UNKNOWN`（不猜成败）；带 `X-Admin-Token` 权威裁定后才迁移到 `SUCCEEDED`，且履约**只触发一次**（重复裁定返回 `changed == false` 的语义体现为 fulfillment 数量仍为 1）。
- **SC-004** 退款场景断言：累计退款不超过已付金额（第 3 笔超额被拒）；同一幂等键重复提交返回同一 `refundNo`。
- **SC-005** 对账场景断言：4 类差异全部出现；全部处理后 `close` 成功且 `status == CLOSED`；另起一批次保留未处理差异时 `close` **被拒**（`UNRESOLVED_DIFFERENCES`）。
- **SC-006** `mvn -o clean verify -fae` 全量 BUILD SUCCESS（含 `architecture-tests` 边界门禁）。
- **SC-007** `bash -n demo/*.sh` 语法检查全部通过。

## 6. 已知限制（诚实标注，不在演示中掩盖）

| # | 限制 | 影响 | 记录位置 |
|---|---|---|---|
| L1 | ADR-0052 回调验签：**已回退为 Not Implemented**（2026-08-31 用户确认）。当前维持 ADR-0025 占位（恒放行），`payment.security.channel-secret` 配置已移除；演示环境无需 export `PAYMENT_CHANNEL_SECRET`（`mock-channel-web` 仍可用其作签名演示同源密钥，但 payment 侧忽略） | 「伪造签名被 403 拒绝」演示在当前形态下**不可达**，收银台该按钮仅作签名演示 | `docs/adr/0013`（ADR-0052 → ⛔ Not Implemented）；`runbook.md` §6 |
| L2 | 对账事实接口无 `period` 参数 | "T-1 对账"实为全量事实比对（N1 风险的延伸） | ADR-0023 / 本 Spec §6 |
| L3 | UNKNOWN 场景需以 `mock-scenario=BUSINESS_UNKNOWN` 重启 payment-service | 场景 2 会重启服务，非单进程内切换 | `demo/run-all.sh` 已编排 |
| L4 | 演示断言依赖本机 MySQL(3306, root/root) | 换环境需改 `demo/env.sh` | `demo/env.sh` 集中配置 |

## 7. 依赖与前置条件

- 本机 MySQL 8（库已存在，见 `deployment/initdb/01-create-databases.sql`）、JDK 17+、Maven 3.9+。
- 9 个服务进程可在本机同时启动（约 3~4 GB 内存）。
- 可选：`jq`（无则回退 Python）。

## 8. 不做（Out of Scope）

- `012-entry-idempotency` / `013-inventory-reservation` / `014-seckill-and-cache` 为**独立 Feature**（见 `docs/architecture/next-stage-design.md`）：本 Spec 仅负责演示编排，不实现其领域逻辑。
- 不引入 MQ（Constitution 禁止，同步 RPC 面）；Redis 已批准引入（ADR-0044），但其使用归属 `012/013/014`。
- 不修改既有状态机、领域模型或同步链路的 API 契约（`payUrl` 为**增量可选字段**，默认不返回）。
