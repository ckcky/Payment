# 下一阶段架构与业务设计（stage-05 · 渠道与资金纵深）

**版本**：0.1（Draft）
**日期**：2026-09-19
**状态**：🟡 **提案（待负责人确认）**——本文件**不是** L0 当前系统事实源，也**不产生**任何已生效决策；
其中涉及领域边界 / 状态机 / Schema / 已发布 API / 新增依赖的条目，一律按
[Constitution](../../../.specify/memory/constitution.md) §Governance「人类决策边界」**先确认再落地**。

**输入（本文件的事实依据）**：

| 类型 | 文档 |
|---|---|
| L0 当前系统事实 | [technical-solution.md](../../architecture/technical-solution.md)、[systems/*.md](../../architecture/systems/) |
| L1 当前约束 | [Constitution](../../../.specify/memory/constitution.md)、[docs/guides/](../../guides/) 三规范 |
| L2 进行中 | [spec 030（渠道契约 + 染色 + 支付宝沙箱）](030-channel-contract-sandbox-callback/spec.md)、[ADR-0075](../../adr/0075-unified-channel-contract.md)、[ADR-0076](../../adr/0076-traffic-dyeing-and-alipay-sandbox.md) |
| 计划权威 | [roadmap.md](../../architecture/roadmap.md)、[docs/specs/README.md](../README.md) |
| 欠账登记 | [code-debt-backlog.md](../../operations/code-debt-backlog.md) |

**阅读约定**：本文件用 `【现状】` 标注代码/文档中**已存在**的事实，用 `【目标】` 标注**尚未实现**的
设计意图，用 `【待确认】` 标注必须由负责人裁决的项。凡 `【目标】`/`【待确认】` 一律不得被下游文档
当作现行强制要求引用（避免形成虚假合规，口径同 Constitution v2.3.0）。

---

## 0. 本文件的定位

项目当前**没有**进行中的 Feature：`027-user-payment-limit` / `028-channel-routing` /
`029-redis-transactional-mq` 均已实现并合入 master；`030-channel-contract-sandbox-callback`
四件套齐备（**Spec v1.1 + Plan**）但**只写文档不改代码**，其两份 ADR 已于 2026-09-19 转 🟢 Accepted，**待开工**
（[spec 030](030-channel-contract-sandbox-callback/spec.md)）。

因此本文件回答三个问题：

1. **现在到哪了**——当前系统在「渠道 / 账务 / 对账结算 / 可靠性 / 可观测 / 测试」六个面上的真实位置（§1）；
2. **下一阶段的目标态是什么**——不推翻现有边界的前提下，六个面各自要补什么（§2~§8）；
3. **怎么落地、哪些必须先确认**——Feature 拆分建议、依赖顺序、人类决策边界清单（§9）。

本文件**不替代** Spec Kit 流水线：每个 Feature 仍须独立走 `spec → plan → tasks → implement`
（[ai-standards.md](../../guides/ai-standards.md) §2）。本文件只提供「阶段总目标」这一层输入。

---

## 1. 现状评估（Next-Phase Assessment）

### 1.1 系统规模与形态（核对于 2026-09-19）

| 维度 | 现状 | 证据 |
|---|---|---|
| 工程 | Maven 多模块单仓库，**17 个 reactor 条目** = 根 POM + 4 个 `common-*` + **9 个业务服务** + `mock-channel-web` + `e2e-tests` + `architecture-tests` | `pom.xml`、`deployment/` |
| 服务与端口 | merchant 8081 / catalog 8082 / order 8083 / payment 8084 / **8085 空号（原 refund-service 已退役）** / fulfillment 8086 / entitlement 8087 / reconciliation 8088 / settlement 8089 / ledger 8090；演示组件 8091 | 各 `application.yml` |
| 运行形态 | **双模式互斥**：宿主 `deployment/start-all.sh` ↔ 容器 `deployment/start-container.sh`，端口契约一致，由 `lib-mode-guard.sh` 双向守卫（[ADR-0070](../../adr/0070-containerized-local-stack.md)） | `deployment/README.md` |
| 通讯 | **同步** Feign + Nacos 服务发现（[ADR-0059](../../adr/0059-enable-nacos.md)）；**异步** Redis Streams 事务消息（[ADR-0074](../../adr/0074-redis-transactional-message.md)），`payment.mq.enabled=false` 可回落同步 Feign | `common/common-redis-mq`、各 `{service}/mq` |
| 数据 | Database-per-Service **9 个 schema**（merchant 用内存）；单 MySQL 8 实例；**无 Flyway / 无 ddl-auto**，靠 `deployment/demo/reset.sh` 重放 schema | `deployment/schema/*.sql`、`deployment/initdb/` |
| 基础设施 | MySQL / Redis / Nacos / Prometheus / Grafana / Loki / Promtail 全部由 `docker compose` 承载；**无 K8s、无 Service Mesh、无 MQ 中间件、无 gateway** | `deployment/docker-compose.yml` |

### 1.2 已闭环的能力（可演示、有测试、有指标）

| 能力面 | 已落地内容 | 关键决策 |
|---|---|---|
| 商业主链 | merchant → catalog → order → payment → fulfillment → entitlement 全链可跑通；三段式库存 + 秒杀预扣 + 限流 | 001 / 013 / 014 |
| 支付可靠性 | 超时进 UNKNOWN、主动查询收敛、请求内联重试（同 attempt 重放）、终态吸收（迟到成功不覆盖已失败）、`TimeoutScanner` 30s 扫描、`POST /payments/{ref}/resolve` 人工收敛（admin token 守卫） | [ADR-0003~0007](../../adr/0003-payment-reliability-decisions.md)、[ADR-0012~0015](../../adr/0012-payment-reliability-impl-decisions.md) |
| 渠道层结构 | payment-service 内部**两层**（payment 支付层 / channelAttempt 渠道层），表归属与写入口分离；`ChannelRegistry` + `ChannelRouter` 确定性选路；前向选路与反向按记录解析严格分离 | [ADR-0072](../../adr/0072-two-layer-channel-architecture.md)、[ADR-0073](../../adr/0073-channel-routing.md) |
| 退款 | order 驱动的**两层退款单**（TXRF 交易层 / PMRF 支付层互记）+ 渠道退款异步回调三路收敛 + 秒杀库存回补 | [ADR-0067](../../adr/0067-order-driven-refund-two-layer-refund-order.md) |
| 账务 | ledger-service 复式记账：**5 个预置科目**（CUSTOMER_CASH / MERCHANT_PAYABLE / PLATFORM_FEE_REVENUE / SETTLEMENT_PAYABLE / SUSPENSE），3 条来源分录模板（PAYMENT / REFUND / SETTLEMENT），借贷平衡在聚合根构造期强校验，append-only 分录，幂等键 + DB 唯一约束 | [ADR-0008~0011](../../adr/0008-ledger-design-decisions.md) |
| 对账与审计 | 基础渠道对账（4 类差异）+ **会计四核对**（账证/账账/账实/账表，11 类差异）+ 挂账/调账/recheck/关批闭环 | [ADR-0019~0021](../../adr/0019-reconciliation-decisions.md)、[ADR-0065](../../adr/0065-accounting-audit-suspense-adjustment.md) |
| 结算 | 商户资格 + 审计门禁（fail-closed）+ `ConfirmedFactGate` 逐条校验 + 净额计算（收入−退款+带符号调整）+ 批次状态机 + **模拟执行强制进 UNKNOWN（不真实出款）** | [ADR-0022~0023](../../adr/0022-settlement-decisions.md) |
| 支付限额 | 日/月/年三档额度 + 两阶段预占（RESERVE → CONFIRM/RELEASE/EXPIRED）+ 三表 + 三道幂等闸门 | [ADR-0071](../../adr/0071-user-payment-limit.md) |
| 可观测 | Micrometer 业务指标 + logback 结构化日志（`traceId`/`bizNo` MDC）+ 单条 `ACCESS_LOG` + **7 条 Prometheus 告警规则** + Grafana 主看板 + Loki/Promtail 采集 | `deployment/prometheus/rules/payment-alerts.yml`、`deployment/grafana/dashboards/` |
| 测试 | 五层：L1 单元 / L2 单服务集成（H2）/ L3 API 快照 / L4 黑盒 E2E（`e2e-tests`，默认跳过）/ L5 架构门禁（ArchUnit 8/8） | [ADR-0069](../../adr/0069-end-to-end-automated-testing.md) |

### 1.3 与「真实生产支付系统」的差距矩阵

> 判据：**能不能接一家真实渠道、能不能被审计、出故障能不能定位**。三条都不满足的项标 🔴。

| 面 | 差距 | 严重度 | 归属章节 |
|---|---|---|---|
| 渠道 | 内部契约装不下任何一家真实渠道（无商品/回调/场景/付款人字段，无凭证载体） | 🔴 | §3 |
| 渠道 | 全项目**零**「环境/染色/灰度」机制，演示页硬编码 `channelCode:'MOCK'` | 🔴 | §3 |
| 渠道 | `payment_attempts` 无渠道模态列 → 退款/查询/超时扫描三条**无入口请求**的反向路径无从判断协议 | 🔴 | §3 |
| 渠道 | 验签为预留空实现（`verifySignature()` 恒 `true`），伪造回调可翻转支付状态 | 🔴（已接受的风险，前提是不暴露公网） | §3 / §6 |
| 账务 | 有**分录**无**账**：无科目余额视图、无期间/关账、无试算平衡表；仅 CNY | 🟠 | §4 |
| 账务 | 记账失败只记指标 + 告警，**「待记账清单」无落地载体**（靠对账事后发现） | 🟠 | §4 |
| 对账 | 渠道账单来源是**本地 CSV fixture**（`period` 未命中回退 `sample.csv`），非真实账单 | 🟠 | §5 |
| 对账 | **N1**：对账事实无商户维度，存在跨商户串账风险（[ADR-0023](../../adr/0022-settlement-decisions.md) 已记录，待单独立项） | 🟠 | §5 |
| 结算 | 无真实出款、无银行对接、无多币种清分 | 🟠（有意接受） | §5 |
| 可靠性 | 退款幂等键**自指**（`RefundOrder.idempotencyKey = refundNo`，每次新雪花）→ 幂等回放分支是死代码 | 🟠 | §6 |
| 可靠性 | UNKNOWN 无**自动收敛器**（spec 019 明确不做）；重试耗尽后的处置靠人工 | 🟠 | §6 |
| 可靠性 | Resilience4j 已引入且 `circuitbreaker.enabled=true`，**与 ADR-0021「当前不引入熔断」冲突**，且无 ADR 支撑 | 🟠 | §6 |
| 可观测 | 无 SLO 度量（Constitution §Obs.5 只定义目标值，无落地指标）；Trace 无标准 span 传播 | 🟡 | §7 |
| 可观测 | 脱敏 ⛔ 本期不做（ADR-0027）；接入真实渠道前 MUST 重新引入 | 🟠（前置条件） | §7 |
| 测试 | 集成测试**全 H2（MySQL 兼容模式）**，Testcontainers 仅声明 BOM、0 处使用 | 🟠 | §8 |
| 测试 | schema 迁移**不可重放**：`01-order-schema.sql` 用 `CREATE TABLE IF NOT EXISTS`（存量库不补列）；`016-refund-channel-attempt.sql` 用 MariaDB 方言 `ADD COLUMN IF NOT EXISTS`（MySQL 8 报语法错） | 🟠 | §8 |

### 1.4 既有欠账（不新造，仅汇总）

- **code-debt-backlog 14 条**，其中 🔴 High 1 条（`01-create-databases.sql` 缺 `ledger` 库）、
  🟠 Med-High 3 条（#4 ArchUnit 拦不住运行时 RPC 环、#5 Resilience4j 去留、#9 脱敏假象）。
- **文档漂移（本轮核查新发现，须先收口）**：

  | # | 漂移 | 证据 |
  |---|---|---|
  | D-1 | `payment-service.md` §2.1 引用 **`§3.11 渠道内部契约（spec 030 / ADR-0075）`，但该章节不存在** → 悬空锚点 | `docs/architecture/systems/payment-service.md:52` |
  | D-2 | `payment-service.md` 存在**两个 `### 3.10`**（渠道路由只读端点 / 事件通道） | 同上 `:281` 与 `:302` |
  | D-3 | **ADR-0075 / ADR-0076 未登记**进 `docs/adr/README.md` 索引表；「下一可用编号」仍写 `ADR-0075` | `docs/adr/README.md:38`、`:128` |
  | D-4 | `technical-solution.md` 未同步 spec 030 的 §2.4（预留契约兑现）/ §3.5（新依赖）/ §5.2（验签不再恒放行）/ §4.3（凭证出参） | 全量检索无 `dye` / `channel_mode` |
  | D-5 | `docs/operations/runbook.md`（沙箱密钥 / 染色 / 内网穿透）、`deployment/demo/README.md`（环境开关）未补 | spec 030 §11 列为「本轮同步」 |
  | D-6 | `028-channel-routing/tasks.md` 首行标题误写「026」 | spec 030 §11 列为「顺带修」 |

- **14 个未验证项**（非阻塞）：`003` T016~T018、`006` T023、`007` T013/T031/T039/T045，
  以及各服务「无 Testcontainers 集成测试」的同类缺口。

### 1.5 结论：下一阶段的三个真问题

1. **渠道面**——系统「看起来」能接渠道，实际**接不进来**。契约是极简版、没有分流机制、
   模态不可追溯。这是唯一一个「不做就永远只是 mock」的方向，且设计已在 spec 030 定稿，只差裁决与实现。
2. **账务面**——账本**记得住、查得清单笔，但出不了「账」**。没有余额视图、没有期间、没有试算平衡表，
   对账依赖 CSV fixture，结算不出款。下一阶段的实质缺口是**从「分录」走向「可审计的账」**。
3. **工程面**——**正确性基础设施欠账**：测试跑在 H2 上（真库并发/唯一键冲突未被自动化覆盖）、
   schema 迁移不可重放、退款幂等键自指、文档漂移。这类欠账不影响演示，但**决定系统能否被信任**。

> **态度**：Constitution §I 的「真实 > 全面」在这里的解释是——**先把渠道真正接进来（问题 1），
> 再把账做到能被审计（问题 2），最后把工程欠账清掉（问题 3）**；三者都做，但顺序不能颠倒。

---

## 2. 总体架构（下一阶段目标态）

### 2.1 设计原则（沿用，不新增）

| # | 原则 | 本阶段的落地含义 |
|---|---|---|
| P1 | **不加服务、不加中间件为默认**（Constitution §IV 门槛五问） | 渠道、账务、对账、结算、可靠性、可观测、测试**全部在既有 9 服务内演进**；确需新依赖时逐条走 ADR |
| P2 | **核心领域 MUST NOT 依赖具体渠道实现**（`Payment ≠ Channel`） | 真实渠道 SDK 一律收口在 `application/channel/*Gateway` 端口之后（ADR-0076 R6） |
| P3 | **资金正确性 > 一切** | 任何新能力不得放宽「UNKNOWN 不猜成败」「借贷平衡」「幂等键 + DB 唯一约束」三条 |
| P4 | **事实不回滚（INV-1）** | 后置失败不回写前序成功；禁 2PC/XA，跨服务用 Saga + 幂等重试 |
| P5 | **配错不许静默走默认**（ADR-0049 纪律） | 染色非法值 / 沙箱未启用 / 渠道不支持真实模态 → 一律 fail fast（400） |
| P6 | **文档即事实源** | 每个 Feature 交付时同步 L0 文档；漂移按 engineering-standards §11 门禁拦截 |

### 2.2 目标架构视图

**结构上本阶段不变**——仍然是「9 服务 + 1 演示组件 + 同步 Feign + Redis Streams 通知 + 单 MySQL 9 schema」。
变化全部发生在**服务内部**与**能力纵深**上：

```text
                        ┌──────────────────────────────────────────────┐
  浏览器 / 调用方 ──────►│  order-service (8083)  订单/交易 = 业务编排者 │
                        └───────┬──────────────────────┬───────────────┘
                                │ 同步 Feign            │ 事件（Redis Streams）
                                ▼                       ▼
        ┌───────────────────────────────────┐   ┌───────────────────────┐
        │  payment-service (8084)           │   │  catalog / fulfillment │
        │  ┌─ payment 支付层 ─────────────┐  │   │  / entitlement         │
        │  │ Payment / payments          │  │   └───────────────────────┘
        │  │ 选路 → 调渠道 → 应用 → 记账 → 扇出│  │
        │  └────────────┬────────────────┘  │
        │  ┌─ channelAttempt 渠道层 ──────┐  │   ← 【本阶段主要变化】
        │  │ PaymentAttempt / attempts    │  │     统一契约 + 类型化凭证 +
        │  │ MOCK 模态 ┃ SANDBOX 模态      │  │     染色分流 + 模态落库
        │  │ Alipay│Wechat│Douyin│…       │  │
        │  └────────────┬────────────────┘  │
        └───────────────┼───────────────────┘
                        ▼  真实渠道（支付宝沙箱 → 微信 v3 → 抖音 → Stripe）
              ┌─────────────────────────────────────────────┐
              │ ledger-service (8090)  资金单一事实源        │  ← 【本阶段加深】
              │  分录 ──► 科目余额 ──► 期间/试算平衡          │
              └──────────────▲──────────────────────────────┘
                             │ 同步记账（三条链路）
              ┌──────────────┴──────────────────────────────┐
              │ reconciliation (8088) 四核对 + 挂账调账      │  ← 【本阶段加深】
              │ settlement (8089) 审计门禁 + 净额 + 批次      │     真实账单 / 商户维度
              └─────────────────────────────────────────────┘
```

### 2.3 与当前架构的差异清单（delta）

| 面 | 结构是否变化 | 具体变化 | 载体 |
|---|---|---|---|
| 服务边界 | ❌ 不变 | 不新增 / 不合并任何服务 | — |
| 通讯方式 | ❌ 不变 | 同步 Feign 为默认，Redis Streams 承载通知；**记账三条维持同步** | ADR-0074 |
| 数据所有权 | ❌ 不变 | Database-per-Service；`payment_attempts` **加列**（非破坏性） | spec 030 |
| 渠道契约 | ✅ 变化 | 极简契约 → 四组结构化字段 + `channelExtra` 扩展袋；`ChannelResult` 加类型化 `credential` | ADR-0075 |
| 渠道模态 | ✅ 新增 | `X-Dye-Tag` 全链路染色 + `payment_attempts.channel_mode` 落库 | ADR-0076 |
| 渠道实现 | ✅ 新增 | `ALIPAY` 升级双模态；`AlipayGateway` 端口收口 SDK | ADR-0076 R5/R6 |
| 回调入站 | ✅ 新增 | 支付宝 RSA2 专用端点，不复用 HMAC 过滤器 | ADR-0076 R7 |
| 账务 | ✅ 加深 | 科目余额视图 / 期间与试算平衡 / 待记账清单 | 本文件 §4（**待确认**） |
| 对账 | ✅ 加深 | 真实账单接入 / 商户维度 / 差异处置策略化 | 本文件 §5（**待确认**） |
| 结算 | ✅ 加深 | 出款边界显式化（仍不真实出款）/ 结算报表 | 本文件 §5（**待确认**） |
| 部署 | ❌ 不变 | 继续 Compose 双模式，**不做 K8s** | ADR-0070 |
| 测试载体 | ✅ 变化 | H2 → Testcontainers-MySQL（分模块渐进） | 本文件 §8（**待确认**） |

### 2.4 明确不做的架构变化

- ❌ 不引入 Kubernetes / Service Mesh / API 网关 / 配置中心（ADR-0070 D1；染色靠请求头不靠动态配置）。
- ❌ 不引入 Kafka / RocketMQ / ES 等新中间件（ADR-0074 已用 Redis Streams 满足解耦需求）。
- ❌ 不拆数据库实例、不做分库分表（ADR-0030：先立触发条件）。
- ❌ 不做 CQRS / Event Sourcing / 领域事件总线。
- ❌ 不把 Redis 当数据源或跨服务共享存储（ADR-0045）。

---

## 3. 渠道接入设计

### 3.1 现状

| # | 事实 | 证据 |
|---|---|---|
| 1 | `ChargeRequest` 5 个位置参数、零校验、无商品/回调/超时/场景/付款人字段 | `application/channel/ChargeRequest.java` |
| 2 | `ChannelResult` 只有 `status / channelReference / reason / transportCode / businessCode`，**无凭证载体** | `ChannelResult.java:39-99` |
| 3 | `QueryStatusRequest` / `RefundRequest` **都没有渠道交易号** | 同目录 |
| 4 | 「支付场景」在内部完全没有概念 | `AbstractMockChannelAdapter` |
| 5 | 三渠道 mock（Alipay / Wechat / Douyin）+ 注册表 + 规则化确定性选路 | ADR-0072 / ADR-0073 |
| 6 | 演示页下单硬编码 `channelCode:'MOCK'`；`mock-cashier.enabled` 是**全局**开关 | `demo.html`、`PaymentController.java` |
| 7 | 回调入站只有 HMAC 占位（`verifySignature()` 恒 `true`，预设 JSON body） | `ChannelCallbackSignatureFilter` |

### 3.2 目标态（已由 spec 030 / ADR-0075 / ADR-0076 定稿，待裁决后实现）

**A. 统一渠道契约**（ADR-0075）——粒度 = **四组结构化字段 + 一个渠道扩展袋**，金额保持扁平：

| 分组 | 字段 | 设计理由 |
|---|---|---|
| 扁平 | `paymentNo` / `attemptId` / `amountMinor` / `currencyCode` / `channelCode` | 全仓已是 `amountMinor` + `currencyCode`（ADR-0010），封装只增拆包噪音 |
| `Goods` | `title` / `description` | 四家对「标题/描述」切分不一致，内部取**并集语义** |
| `CallbackUrls` | `notifyUrl` / `returnUrl` | 异步通知是**资金事实来源**，同步跳转只是体验——混在一起会诱导用 `returnUrl` 推进状态 |
| `Payer` | `payerId` / `clientIp` | 「站内已登录用户」与「匿名 H5」是两种调用形态 |
| `PaymentScene` | `WEB / H5 / NATIVE / JSAPI / MINI_PROGRAM / APP` | 渠道能力声明的载体，**不是选路依据**；不搬任何一家的词表进内部契约 |
| `channelExtra` | `Map<String,String>` | 仅放渠道私有参数（键名用渠道原生名，如 `qr_pay_mode`），便于与渠道文档逐字比对 |

**「什么进通用字段、什么进扩展袋」的唯一判据**：四家**语义一致且支付必需** → 结构化字段；
仅 1~2 家有、语义不统一、或非必需 → `channelExtra`。**严禁**把渠道私有参数塞进通用字段，
也**严禁**把通用语义降级成扩展袋。

**B. 类型化付款凭证**（ADR-0075 D3/D4）——`PayCredential(kind, payload, expiresAt)`，
`kind ∈ {REDIRECT_URL, FORM_HTML, QR_CODE, H5_URL, JSAPI_PARAMS, CLIENT_SECRET}`：

- 用单一 `String payUrl` 承载四家返回会产生**语义错配**（把 HTML 塞进叫 URL 的字段、把二维码串当 URL 打开）；
- `REDIRECT_URL` 与 `H5_URL` **刻意不合并**——两者都是 URL，但后者**必须手机浏览器**（微信校验 UA）；
- **凭证不落库**：含签名、有时效、长度远超 `channel_reference VARCHAR(128)`，且不具备「渠道流水号」的唯一性语义（会撞 `uk_attempts_channel_reference`）。持久化的渠道标识**恒为** `channel_reference`。

**C. 全链路染色分流**（ADR-0076）——头名 `X-Dye-Tag`，取值 `MOCK` / `SANDBOX`，**缺省 = `MOCK`**（安全默认）：

| 环节 | 组件 | 对齐 traceId 骨架 |
|---|---|---|
| 上下文 | `dye/DyeContext`（ThreadLocal + `isSandbox` / `runWith`） | `TraceContext` |
| 入站 | `dye/DyeFilter`（`OncePerRequestFilter`，order = **-190**，MDC key `dyeMode`，finally 清理） | `TraceIdFilter`（-200） |
| 出站 | `dye/DyeRequestInterceptor`（Feign；**非空才写**） | `TraceIdRequestInterceptor` |
| 装配 | `CommonCoreAutoConfiguration` + `FeignTraceAutoConfiguration` | 同 |

- 过滤链定序：`TraceIdFilter(-200)` → `DyeFilter(-190)` → `AccessLogFilter(-100)`；
- **入站读与出站写 MUST 同一批落地**——依据先例教训（ADR-0034：入站校验上线但出站头未补 → 全线 403）；
- `order-service` 与演示代理**零改动**（common-core 被 9 服务 + 代理依赖，代理黑名单式逐头透传）。

**D. 模态落库与反向还原**（ADR-0076 R4）：

- `payment_attempts.channel_mode VARCHAR(16) NOT NULL DEFAULT 'MOCK'`，取值 `MOCK` / `SANDBOX`；
- **schema 三处齐备**（因为建表语句用 `CREATE TABLE IF NOT EXISTS`，存量库不补列）：
  ① `03-payment-schema.sql` 建表语句；② 增量迁移 `030-payment-attempt-channel-mode.sql`；③ 测试 H2 schema；
- **反向三条路径**（退款 / 主动查询 / 超时扫描）**没有入口请求**，据落库值还原模态
  （`DyeContext.runWith(attempt.getChannelMode(), () -> channel.xxx(req))`）；
  **禁止**依赖 ThreadLocal、**禁止**解析 `channel_reference` 字符串。

**E. 单 Adapter 双模态**（ADR-0076 R5）——不新建 Sandbox Adapter：

```java
AlipayChannelAdapter.charge(req):
    DyeContext.isSandbox() ? sandboxCharge(req) : super.charge(req)
```

`MOCK` 分支**必须 `super` 委托**，基类 4 件横切行为（金额尾数故障注入 / 退款受理与异步推送 /
每实例 `runId` / 场景严格枚举解析）**口径 100% 不变**。

**F. SDK 收口**（ADR-0076 R6）——引入 `com.alipay.sdk:alipay-sdk-java`，但：

- 端口 `application/channel/AlipayGateway`（`pagePay` / `query` / `refund` / `verifyNotify`），
  实现 `infra/channel/alipay/AlipaySdkGateway`；
- **`application/**` MUST NOT 依赖 `com.alipay.sdk`**（ArchUnit 断言）；
- 版本在**根 pom `dependencyManagement`** 锁定，子模块 MUST NOT 写版本（engineering-standards §9）。

**G. 回调入站多协议共存**（ADR-0076 R7）：

| 路径 | 协议 | 验签 | 状态 |
|---|---|---|---|
| `/internal/payments/*`、`/internal/refunds/*` | JSON + `X-Channel-Signature` | HMAC-SHA256（**预留空实现恒放行**） | 保留给**本地 mock** |
| `/internal/channels/alipay/notify` | `form-urlencoded` | **RSA2 + 支付宝公钥** | 新增，专用端点 |

- 处理顺序：剔除 `sign`/`sign_type` → 参数名排序拼串 → `verifyNotify` → 校验 `app_id`/`seller_id`/
  `out_trade_no`/`total_amount`（字符串比较）→ `trade_status` 映射 → 复用现有
  `PaymentCallbackService.handleCallback`（**不新建收敛链路**）；
- **必须返回恰好纯文本 `success`**（否则支付宝按递增间隔重试，约 25 小时内 8 次）；
- 映射：`TRADE_SUCCESS`/`TRADE_FINISHED` → `success(trade_no)`；`TRADE_CLOSED` → `businessFailure`；
  `WAIT_BUYER_PAY` → `businessUnknown`（**不推进**）。

**H. 密钥与配置治理**（ADR-0076 R9）：

- `payment.channel.adapters.alipay.sandbox.enabled` **默认 `false`**——既是装配门控，也是 kill switch；
- `enabled=true` 时**启动期强校验**（`app-id` / 私钥 / 支付宝公钥 / `notify-url`），缺失即启动失败并列出缺失项；
- 密钥全部 **env 注入**（`PAYMENT_ALIPAY_*`），禁硬编码 / 禁入库 / 禁明文日志（ADR-0026）；
- 沙箱 HTTP 超时**独立配置**（默认 10000ms），且 MUST **小于** `payment.reliability.timeout`(30s)——
  否则出现「还在等支付宝、支付已被判 UNKNOWN」。

### 3.3 真实渠道接入路线（逐家接，契约先兼容）

| 序 | 渠道 | 模态 | 关键差异 | 前置条件 |
|---|---|---|---|---|
| 1 | **支付宝沙箱** | `SANDBOX` | 金额**元字符串**（`BigDecimal.valueOf(minor,2).toPlainString()`）；RSA2 验签；`notify_url` 须公网可达；`page.pay` 同步响应**不含 `trade_no`** | spec 030 实现；负责人自备 APPID/密钥 + 内网穿透 |
| 2 | 微信 v3 | `SANDBOX`/`REAL` | 场景由 **URL 路径**表达（`/jsapi` `/native` `/h5` `/app`）；JSAPI 必填 `payer.openid`；H5 必填终端 IP；`notify_url` 须 HTTPS 且禁查询串 | 契约已兼容（`PaymentScene` + `Payer` + `channelExtra`） |
| 3 | 抖音支付 | `SANDBOX`/`REAL` | 存在**两套并存**的下单体系（实现期复核） | 同上 |
| 4 | Stripe | `SANDBOX`/`REAL` | 金额**最小货币单位**；币种**小写**；`payment_method_types`（用什么工具付）与 `PaymentScene`（在哪个端付）**正交**，走 `channelExtra`；webhook 是**账号级**而非按单 | 同上 |

> **共同结论**：新增一家渠道**不必再动端口**——差异要么落在 `scene`，要么落在 `channelExtra`。
> 这正是 ADR-0075 D1 判据的价值。

### 3.4 缺口与风险（诚实标注）

| # | 项 | 影响 | 处置 |
|---|---|---|---|
| C-1 | **非跳转型凭证无处承载**：`CreatePaymentResponse` 只有 `payUrl` | 二维码 / 表单 HTML / JSAPI 参数集 / `client_secret` **无法端到端**；本期只打通 `REDIRECT_URL` / `H5_URL` | 补齐需改 `common-dto`（跨服务 API 变更）→ **下期单独立项**（spec 030 §8 L1） |
| C-2 | 支付宝 SDK **34.3 MB + 4 个传递依赖 + ≥9 个已知 CVE** | 依赖体积与攻击面上升 | 收口在 `AlipayGateway` 端口后；**若走向严肃生产 MUST 复核**（登记 backlog） |
| C-3 | 染色**不参与选路** | 染色 `SANDBOX` + `WECHAT`（无真实实现）→ `400`；demo 页选沙箱时自动选 `ALIPAY` | 有意接受（ADR-0076 R3） |
| C-4 | 沙箱单待付款时 payment 停 `PROCESSING`，`TimeoutScanner` 30s 后可能转 `UNKNOWN` | 与既有 mock 延迟路径**同构**，非新增风险 | 靠 `alipay.trade.query` / notify 收敛 |
| C-5 | `notify_url` 必须公网可达；沙箱**不能进 CI** | 真机验证为**手工**；CI 只跑离线单测（固定签名向量） | ADR-0076 R10；未验证范围须在 acceptance 显式记录，**不得默认通过** |
| C-6 | 支付宝沙箱 HTTP 超时 10s 与全局 1.5s **两档并存** | 需在文档与配置注释中显式说明 | spec 030 FR-045 |
| C-7 | 存量 attempt 行**不回填** `channel_mode`（`DEFAULT 'MOCK'` 即定论） | 历史行模态口径保守失效 | 有意接受 |

---

## 4. 账务总账设计

### 4.1 现状

| 维度 | 现状 |
|---|---|
| 聚合 | `Posting`（一次业务事件 = 一组平衡分录）+ `LedgerEntry`（不可变分录，`rehydrate` 重建） |
| 科目 | **5 个**：1 `CUSTOMER_CASH`(ASSET) / 2 `MERCHANT_PAYABLE`(LIABILITY) / 3 `PLATFORM_FEE_REVENUE`(REVENUE) / 4 `SETTLEMENT_PAYABLE`(LIABILITY) / 5 `SUSPENSE`(ASSET，spec 017 新增) |
| 分录模板 | 支付：`DEBIT CUSTOMER_CASH A` = `CREDIT MERCHANT_PAYABLE (A−F)` + `CREDIT PLATFORM_FEE_REVENUE F`；退款：`DEBIT MERCHANT_PAYABLE R` = `CREDIT CUSTOMER_CASH R`；结算：`DEBIT MERCHANT_PAYABLE S` = `CREDIT SETTLEMENT_PAYABLE S` |
| 平衡门禁 | `Posting` 聚合根**构造期**校验同币种 `sum(DEBIT) == sum(CREDIT)`，不平衡抛 `LEDGER_UNBALANCED` 且**不落任何分录** |
| 幂等 | 幂等键（`PAYMENT:<key>` / `REFUND:<key>` / `SETTLEMENT:<batchId>`）+ `uk_postings_idempotency_key` 唯一约束；「先查后插 + 冲突回查」 |
| 一致性 | 记账**同步 RPC**，失败**不回滚**上游事实，只记 `ledger.posting_failed` + 告警（ADR-0009 / ADR-0018） |
| 端点 | `POST /internal/ledger/postings`、`GET /postings`（按幂等键）、`GET /balance`（全局借贷差额）、`GET /entries`（按来源追溯） |
| 表 | `accounts` / `postings` / `ledger_entries`（`09-ledger-schema.sql`） |
| 币种 | **仅 CNY**；`currency` 维度已建模（按币种隔离借贷） |
| 测试 | 6 个测试类覆盖 SC-001~005 + FR-004；**无 Testcontainers 集成测试**（领域/应用层走 `InMemoryLedgerRepository`，真库并发未覆盖） |

### 4.2 目标态【待确认】

> 以下四项均为**新增能力**，不改变任何既有分录模板与不变量。是否立项、优先级、范围由负责人裁决。

**G1. 科目余额视图（从「分录」到「账」）**

- 新增**读模型**（不改分录表）：按 `account_id + currency` 聚合 `sum(DEBIT) − sum(CREDIT)`，
  暴露 `GET /internal/ledger/accounts/{id}/balance?currency=` 与 `GET /internal/ledger/trial-balance`；
- **实现取舍待确认**：① 实时聚合 `ledger_entries`（简单、慢）；② 余额表 + 记账时同步更新（快、需保证与分录同事务）。
  **推荐 ②**，因为同事务可保证「余额 = 分录累计」永不漂移，且试算平衡可直接查余额表；
- **不变量**：`trial_balance` 全科目借贷合计 MUST 恒等（各币种独立校验），不等即 `BALANCE_BREAK`。

**G2. 期间与试算平衡**

- 引入**会计期间**概念（`period` = `YYYY-MM` 或 `YYYY-MM-DD`），`postings` 加 `period` 列（可空，落库时按 `created_at` 派生）；
- 提供「期间试算平衡表」：期初余额 + 本期借贷发生额 + 期末余额，跨期勾稽；
- **期末关账**：关账后该期间 MUST NOT 接受新分录（`POST /postings` 校验 `period` 是否已关），
  更正只能通过**下期反向分录**（与「分录不可变」一致）。

**G3. 待记账清单（记账失败的可追踪载体）**

- **现状缺口**：记账 RPC 失败只记指标 + 告警，「哪一笔没记上」没有落地载体，靠 T+1 对账事后发现；
- **目标**：新增 `pending_postings` 表（来源类型 / 来源单号 / 幂等键 / 失败原因 / 重试次数 / 状态），
  由**业务侧**在记账失败时写入，提供 `GET /internal/ledger/pending` 查询与补偿重试入口；
- **红线**：清单是**补偿辅助**，不是资金事实源——账务事实**恒以 `postings` 为准**。

**G4. 多币种与科目扩展**

- 多币种：`currency` 维度已建模，但当前无跨币种折算需求。**触发条件**：出现第一笔非 CNY 业务；
  届时 MUST 另立 ADR（跨币种折算涉及汇率来源、折算时点、汇兑损益科目——属人类决策边界）；
- 科目扩展：新增科目 MUST 走 ADR（科目表是账务语义的一部分）。

### 4.3 记账边界与一致性（保持不变）

- **只对已确认事实记账**：`UNKNOWN` / 处理中 / 失败 / 拒绝**一律不记账**（Constitution §V.7）；
- **三条记账链路维持同步**（payment→ledger、refund→ledger、settlement→ledger）——理由：借贷平衡审计对顺序敏感，
  且有 T+1 账证核对兜底（ADR-0074 明确不做异步化）；
- **禁 2PC/XA**：记账失败不回滚业务事实，靠重试 / 对账 / G3 的待记账清单兜底。

### 4.4 缺口汇总

| # | 缺口 | 严重度 | 归属 |
|---|---|---|---|
| L-1 | 无科目余额视图（只有全局借贷差额 `/balance`） | 🟠 | G1 |
| L-2 | 无期间、无试算平衡表、无关账 | 🟠 | G2 |
| L-3 | 记账失败无「待记账清单」载体 | 🟠 | G3 |
| L-4 | 仅 CNY，无跨币种折算 | 🟡 | G4（等触发条件） |
| L-5 | 无 Testcontainers 集成测试（真库并发 / 唯一键冲突未自动化） | 🟠 | §8 |

---

## 5. 对账与结算设计

### 5.1 现状

**对账（reconciliation-service，8088）**：

| 层 | 内容 |
|---|---|
| 基础渠道对账 | 按 `period` 幂等建批 → 拉 payment/refund `confirmed-facts`（只读 RPC）→ 加载 CSV 账单 → 纯函数逐笔匹配 → 4 类差异（`AMOUNT_MISMATCH` / `STATUS_MISMATCH` / `PLATFORM_ONLY` / `CHANNEL_ONLY`）；批次状态机 `PENDING → RECONCILING → CONSISTENT/HAS_DIFFERENCE → PROCESSING → CLOSED`，关闭门禁拒绝未收口差异 |
| 会计四核对 | 账证（`CertificateAuditor`）/ 账账（`LedgerAuditor`）/ 账实（`RealAuditor`）/ 账表（`ReportAuditor`），**11 类差异** × 三级 severity × 5 态状态机 |
| 处置闭环 | 挂账（`SUSPENSE` 过渡科目）→ 调账（5 类：SUPPLEMENT / REVERSE / CORRECT / TRANSFER / WRITE_OFF）→ recheck → 关批；经 ledger 标准记账通道写**平衡、append-only** 分录，**绝不修改原始 Payment/Refund/Settlement 事实** |
| 结算门禁 | `GET /internal/audit/settlement-gate?period=` → `ALLOW` / `BLOCK`（fail-closed） |
| 数据 | `reconciliation_batches`（匹配/差异内嵌 JSON）；`audit_batches` / `audit_differences` / `audit_adjustments`（`10-audit-schema.sql`） |
| 账单来源 | **本地 CSV fixture**（`{dir}/{period}.csv`，未命中显式回退 `sample.csv` + `reconciliation.statement_fallback` 指标 + WARN，**绝不静默**） |

**结算（settlement-service，8089）**：

- 建批流程：幂等键回查（命中校验 `merchantId`/`period` 一致，否则 `DUPLICATE`）→ 商户+周期回查 →
  商户资格（`ACTIVE` + `settlementEligible`）→ **审计门禁** → **`ConfirmedFactGate`** 逐条校验
  （type ∈ {PAYMENT, REFUND}、币种一致、金额 ≥ 0、周期一致）→ 加载 ACTIVE 调整项带符号汇总 →
  净额 `income − refund + ΣsignedAdjustment` → 持久化（含 `fact_count` / `source_period`）→ **模拟执行强制进 `UNKNOWN`**；
- 收敛：`resolve` 为 `SUCCEEDED` 且 `netMinor > 0` 时经 ledger 记账（`SETTLEMENT:<key>`）；
  `netMinor ≤ 0` 跳过；记账失败不回滚批次；
- 调整项：**恒正金额 + 方向枚举**（禁负数表达方向）；**批次即事实快照**，建批后禁止追登（防追溯篡改）；
- 状态机：`PENDING → CALCULATING → READY → EXECUTING → SUCCEEDED/FAILED/UNKNOWN → CLOSED`，终态吸收迟到冲突。

### 5.2 目标态【待确认】

**R1. 渠道账单从 fixture 走向真实来源**

- 现状的 `ChannelStatementLoader` 端口**已具备替换条件**（按 `period` 定位 + 显式回退 + 路径穿越校验）；
- **目标**：新增实现（如「按渠道 + 周期拉取真实账单文件」或「对账文件上传 + 解析」），
  由配置选择实现，**保留 fixture 作为离线/演示实现**；
- **红线**：账单解析失败 MUST **显式失败**，MUST NOT 静默回退到 `sample.csv`（现有回退已带指标与 WARN，
  真实账单接入后 MUST 收紧为 fail fast）。

**R2. 商户维度缺口 N1 的处置**

- **问题**：对账事实无商户维度，可能跨商户串账（ADR-0023 已记录，待单独立项）；
- **目标方案（推荐）**：在 `PlatformFact` / `ChannelStatement` / `Match` 上补 `merchantId`，
  匹配键由 `reference` 改为 `(merchantId, reference)`；
- **影响面**：`payment` / `refund` 的 `confirmed-facts` 出参需补 `merchantId`（跨服务 DTO 变更 → **人类决策边界**）；
- **备选**：接受现状并在文档中明确「单商户演示前提」——**不推荐**，因为这是真实的串账风险。

**R3. 结算侧：出款边界显式化（仍不真实出款）**

- 现状：`SETTLEMENT_PAYABLE` 只记负债，`createBatch` 强制进 `UNKNOWN`，`resolve` 才可收敛为 `SUCCEEDED`；
- **目标**：把「模拟执行」的边界**写进契约与文档**（哪些状态可收敛、谁有权收敛、收敛后的资金含义），
  并补**结算报表**（按商户/周期：收入 / 退款 / 调整 / 净额 / 批次状态）；
- **明确不做**：真实出款、银行对接、多币种清分、税费分账。

**R4. 差异处置策略化**

- 现状：差异处理是**人工**（`resolve` 填备注 / 挂账 / 调账）；
- **目标**：对**可确定**的差异类型建立处置策略表（如 `CHANNEL_ONLY` + 小额 → 自动挂账；
  `AMOUNT_MISMATCH` → 一律人工），策略**必须可审计**（每次自动处置留 `audit_adjustments` 痕迹）；
- **红线**：自动处置 MUST NOT 修改原始事实，MUST 经 ledger 平衡分录，MUST 受「累计 ≤ 差异额」硬规则约束。

### 5.3 缺口汇总

| # | 缺口 | 严重度 | 归属 |
|---|---|---|---|
| R-1 | 账单来源是本地 fixture，非真实账单 | 🟠 | R1 |
| R-2 | **N1 跨商户串账风险**（无商户维度） | 🟠 | R2 |
| R-3 | 结算不出款、无结算报表 | 🟡（有意接受） | R3 |
| R-4 | 差异处置全靠人工，无策略化 | 🟡 | R4 |
| R-5 | 对账出站 Feign 无熔断/降级（ADR-0021 明确不引入） | 🟡（有意接受） | 保持 |

---

## 6. 可靠性设计

### 6.1 现状

| 机制 | 落地 |
|---|---|
| 超时 | 渠道 RPC 1s / HTTP 1.5s（ADR-0015）；沙箱独立 10s（ADR-0076 R9） |
| 超时语义 | **超时 ≠ 失败/成功**，一律进 `UNKNOWN`（ADR-0004） |
| 重试 | 仅对**幂等**外部调用；**请求内联重试、不落库**；同 attempt 重放（ADR-0013/0014）；退避 + 上限 |
| 错误分类 | **双响应码**（`transportCode` + `businessCode`）+ `retryable` 派生（ADR-0012）；通信失败一律重试 |
| 终态冲突 | 迟到成功**不覆盖**已失败；终态吸收（ADR-0007） |
| UNKNOWN 收敛 | ① 主动查询（`ChannelQueryScheduler`）；② 后续回调；③ 对账；④ **人工** `POST /payments/{ref}/resolve`（`ResolveAuthorizationInterceptor`，未配置 `PAYMENT_ADMIN_TOKEN` 时 503 拒绝） |
| 超时扫描 | `TimeoutScanner` 30s 阈值把 `PROCESSING` 转 `UNKNOWN` |
| 补偿 | 限额域 `LimitCompensationScheduler`（以 payment 为事实源） |
| 消息通道容灾 | Redis 开 AOF + `maxmemory-policy noeviction`（拒绝写入而非驱逐）；通道故障可 `payment.mq.enabled=false` 回落同步 Feign；Redis 全丢系统**仍正确**，只需人工重放 |
| 熔断 | Resilience4j 已在 payment-service 引入且 `circuitbreaker.enabled=true`，**无 ADR 支撑**，与 ADR-0021 冲突（backlog #5） |

### 6.2 目标态【待确认】

**B1. 熔断去留（backlog #5 / R2）——最高优先级的「先裁决」项**

| 选项 | 论证 |
|---|---|
| **移除（推荐）** | ADR-0021 已判定「当前无熔断的真实需求证据」；保留一个**已开启却无 ADR 支撑**的熔断，会让运维误以为存在该保护层，**反而更危险** |
| 保留并补 ADR | 若确有需求（如渠道出站保护），须新立 ADR 说明场景、阈值、降级行为，并回写 `systems/payment-service.md` |

**B2. UNKNOWN 自动收敛器**

- 现状：spec 019 明确「不做 UNKNOWN 自动收敛器」，靠查询 / 回调 / 对账 / 人工；
- **目标**：为**超过 N 小时仍未收敛**的 `UNKNOWN` 建立分级升级（`payment.unknown_age` 分桶指标 →
  告警 → 自动进入人工队列），**不自动判定成败**（Constitution §V.7 红线）；
- **明确不做**：任何「超时即失败」的自动终态化。

**B3. 幂等键治理（退款幂等键自指）**

- **问题**：`RefundOrder.idempotencyKey = refundNo`（每次新雪花）→ **幂等回放分支是死代码**，
  实际只有「在途守卫」生效；终态后同参提交 = 新建退款；
- **目标**：把幂等键改为**调用方提供**（order 侧按 `transactionNo + paymentNo + 请求序列` 派生），
  使「同参重放返回首次结果」真正成立；
- **注意**：这是**行为变更**，会影响现有「终态后同参 = 新建退款」的既定设计 → **人类决策边界**。

**B4. 后置 RPC 失败的补偿载体**

- 现状：履约→权益失败靠人工补发（backlog #14）；
- **目标**：复用 G3「待记账清单」的同一模式，为**关键后置 RPC** 建立可查询的失败台账 + 补偿重试入口；
- **红线**：补偿是**辅助**，事实恒以各域自身状态为准；后置失败**不得**回写前序成功事实。

**B5. 故障演练（不引工具）**

- 现状：E2E 已有**确定性故障注入**（金额尾数 `11`=渠道超时 / `12`=无结论 / `15`=业务拒绝；
  审计 FAULT 走 DB 直改 + 还原；渠道账单差异经 `statement-dir-override` 注入）；
- **目标**：把故障注入矩阵**扩展**到新链路（染色 / 沙箱 / 真实账单 / 消息通道 DLQ），
  仍**不引入** Chaos 工具（保持「不加中间件」原则）。

### 6.3 缺口汇总

| # | 缺口 | 严重度 | 归属 |
|---|---|---|---|
| B-1 | Resilience4j 无 ADR 支撑、与 ADR-0021 冲突 | 🟠 | B1（**先裁决**） |
| B-2 | 退款幂等键自指 → 幂等回放是死代码 | 🟠 | B3 |
| B-3 | UNKNOWN 无分级升级机制（仅裸告警） | 🟠 | B2 |
| B-4 | 后置 RPC 失败无台账（靠人工补发） | 🟠 | B4 |
| B-5 | 鉴权 / 验签空实现（**接入真实渠道前 MUST 补齐**） | 🔴（前置条件） | §3.2 G / 保持 |

---

## 7. 可观测性设计

### 7.1 现状

| 维度 | 落地 |
|---|---|
| Metrics | Micrometer + `/actuator/prometheus`；业务计数覆盖支付（成功/失败/超时/渠道/重试）、退款、履约、权益、对账（差异数量/金额）、结算（成功率/失败数）、限额、消息通道（8 项 `mq.*`） |
| Logs | logback 结构化；MDC 关联 `traceId` + **`bizNo`**（orderNo/paymentNo/refundNo）+ `dyeMode`；**资金动作写 `FINANCIAL_AUDIT`**；`AccessLogFilter` 在请求结束时输出**单条 `ACCESS_LOG`**（method/URI/status/costMs + 受限 req/resp，4KB 截断，`/actuator` 排除，可开关）；logback 注入 `service` 字段 |
| Traces | **自研**：`TraceIdFilter`（-200）+ `TraceContext`（ThreadLocal）+ `TraceIdRequestInterceptor`（Feign）；**traceId 跨异步边界连续**（写入事件信封 + 消费端恢复 MDC，ADR-0074 D14）；**无标准 span 传播**（Micrometer Tracing `[目标] 未落地`） |
| 采集与展示 | Prometheus（**7 条告警规则**：`PaymentUnknownBacklog` / `PaymentRetryExhausted` / `PaymentQueryExhausted` / `PaymentOrderIllegalStateRejected` / `RefundFailure` / `RefundDownstreamFailure` / `ReconciliationDifference`）+ Grafana 主看板 + 消息通道面板 + Loki/Promtail |
| 日志检索 | `deployment/logs/<service>.log`；`grep -a "<traceId>"`；`/demo/trace?orderId=` 查全链路 |

### 7.2 目标态【待确认】

**O1. SLO 从「定义」到「度量」**

- 现状：Constitution §Obs.5 定义了目标（可用性 / P99 / 对账达成率），但**没有落地指标**；
- **目标**：把 4 项 SLO 落成**可查询的 Recording Rule + 看板**（同步查询 P99 ≤ 500ms / 同步命令 P99 ≤ 1s /
  资金入口可用性 ≥ 99.9% / 对账达成率 ≥ 99%），并配**错误预算**告警；
- **注意**：目标值本身是 `[目标]`（technical-solution §5.1），MUST 先确认再落规则。

**O2. 告警规则补齐**

- 现状 7 条规则偏「支付/退款/对账」；**缺失**：结算 `UNKNOWN` 堆积、记账失败堆积（`ledger.posting_failed`）、
  限额在途占用泄漏、消息通道 DLQ 增长、染色非法值拒绝率；
- **目标**：按 Constitution §Obs.4「业务告警而非基础设施告警」补齐，并明确**每条规则的处置动作**（写入 runbook）。

**O3. 脱敏重新引入（前置条件，非可选）**

- 现状：脱敏 ⛔ **本期不做**（ADR-0027），`SensitiveDataMasker` 已删除，`StructuredAuditLogger.mask()` 保留但**生产零调用**；
- **硬约束（Constitution §Security.4）**：**接入真实支付渠道、或开始处理真实卡号 / 密钥 / 证件号之前**，
  MUST 重新引入脱敏并补齐审计日志调用点；
- **本阶段的含义**：spec 030 接入支付宝沙箱时**不处理卡号**（只有商户密钥），
  但**密钥 MUST NOT 进日志**（ADR-0076 K8）——因此至少要在沙箱适配器与 notify 端点落地
  「密钥 / 完整报文不入日志」的显式约束与测试。

**O4. Trace 标准化的取舍**

| 选项 | 论证 |
|---|---|
| **维持自研（推荐）** | 现有三段式骨架已在 9 服务 + 代理全覆盖，且已解决「跨异步边界连续」这一最难的问题；引入 Micrometer Tracing 需新增依赖 + 采样决策 + 后端（Tempo/Jaeger），收益（span 粒度）低于成本 |
| 引入 Micrometer Tracing | 若要做**单请求跨服务耗时分解**（渠道调用占了多少）才值得；届时 MUST 另立 ADR |

### 7.3 缺口汇总

| # | 缺口 | 严重度 | 归属 |
|---|---|---|---|
| O-1 | SLO 无落地指标与看板 | 🟡 | O1 |
| O-2 | 告警规则缺 5 类（结算 UNKNOWN / 记账失败 / 限额泄漏 / DLQ / 染色拒绝） | 🟠 | O2 |
| O-3 | 无标准 span 传播（有意接受） | 🟡 | O4 |
| O-4 | 脱敏不做（**接真实渠道前的前置条件**） | 🟠（前置条件） | O3 |

---

## 8. 测试策略

### 8.1 现状（五层）

| 层 | 载体 | 现状 |
|---|---|---|
| L1 单元 | 各服务 `src/test` | JUnit 5 + Mockito + AssertJ；资金逻辑与状态机覆盖充分（表驱动优先） |
| L2 单服务集成 | 同上 | **全 H2（MySQL 兼容模式）**；Testcontainers 仅声明 BOM、**0 处使用** |
| L3 API 快照 | `e2e-tests/contract/InternalApiSnapshotTest` | 字段集合 + 类型快照，基线在 `src/test/resources/api-snapshots/` |
| L4 黑盒 E2E | `deployment/e2e-tests` | JDK HttpClient + 多 schema JDBC；**默认跳过**，`-De2e.env=local\|ci` 显式激活；`Invariants` 断言原语库（9 个）；失败落 `target/e2e-dump/<case>/` |
| L5 架构门禁 | `deployment/architecture-tests` | ArchUnit **8/8**（含 INV-4 `application.channel..` 不得依赖 `infra.channel..`、INV-5 `application..` 不得调 `PaymentAttemptRepository.save`） |

**已知测试缺口**：

- 真库并发未被自动化（H2 无法覆盖乐观锁 / 唯一键冲突的真实行为）；
- `common-mybatis` **0 测试覆盖**（backlog #6）；
- ArchUnit **拦不住运行时 RPC 环**（backlog #4：order ↔ payment 存在运行时双向 RPC，构建仍全绿）；
- schema 迁移**不可重放**（backlog #1/#13：`01-order-schema.sql` 用 `CREATE TABLE IF NOT EXISTS` 不补列；
  `016-refund-channel-attempt.sql` 用 MariaDB 方言 `ADD COLUMN IF NOT EXISTS`，MySQL 8 报语法错）；
- 沙箱**不能进 CI**（ADR-0076 R10）。

### 8.2 目标态【待确认】

**T1. Testcontainers-MySQL 渐进迁移（最高优先级的工程欠账）**

- **现状**：Constitution §Engineering.3 与 engineering-standards §4 均已标注 `[目标] 未落地`；
- **目标路径**（分三步，**不一次性全量切换**）：
  1. **先建基础设施**：在根 pom 提供 `testcontainers` profile + 可复用基类（单例容器、schema 自动重放）；
  2. **先切「并发与唯一键」类测试**：ledger 幂等（`uk_postings_idempotency_key` 冲突）、settlement 批次唯一约束、
     payment 幂等键 —— 这些正是 H2 覆盖不到、且**直接关系资金正确性**的场景；
  3. **再逐模块迁移**：每迁一个模块 MUST 逐个验证 H2 与 MySQL 的方言差异（**约束、时区、JSON 类型**）；
- **前置条件**：先解决**本机 Docker 可用性与 CI 环境一致性**（这是宪法标注的真实阻塞原因）。

**T2. Schema 迁移可重放**

- **目标**：所有 `deployment/schema/*.sql` 迁移脚本 MUST 幂等可重放（按 `018-schema-normalization.sql` 的
  `information_schema` 守卫 + `PREPARE` 动态 SQL 模式），MUST NOT 使用方言专属语法；
- **门禁**：新增一条 CI 检查——在**空库**与**存量库**上各重放一次，两次结果一致才算通过。

**T3. ArchUnit 补运行时依赖环规则（backlog #4）**

- **目标**：新增规则扫描 `@FeignClient` 调用图，检测**服务级循环依赖**；
- **或**：明确接受并写入 ADR（二选一，须裁决）。

**T4. 沙箱测试策略（替代「不能进 CI」）**

- 离线单测：mock `AlipayGateway` + **固定签名向量**钉死签名 / 验签 / 参数排序（ADR-0076 R10）；
- 真机验证：**手工 live**，且若因环境不可用未做，**必须在 ADR 与 acceptance 中显式记录未验证范围，不得默认通过**；
- **明确不做**：SCC/Pact 契约测试、k6/Gatling 压测框架化（ADR-0069 已否决）。

### 8.3 缺口汇总

| # | 缺口 | 严重度 | 归属 |
|---|---|---|---|
| T-1 | 集成测试全 H2，真库并发/唯一键未覆盖 | 🟠 | T1 |
| T-2 | schema 迁移不可重放（2 处方言问题） | 🟠 | T2 |
| T-3 | ArchUnit 拦不住运行时 RPC 环 | 🟠 | T3 |
| T-4 | `common-mybatis` 0 覆盖 | 🟡 | 登记 |
| T-5 | 沙箱不可自动化（有意接受） | 🟡 | T4 |

---

## 9. 设计总结

### 9.1 一句话结论

**下一阶段不做架构扩张，做能力纵深**：把**渠道真正接进来**（spec 030 落地 + 逐家渠道）、
把**账做到能被审计**（余额视图 / 期间 / 待记账清单 / 真实账单 / 商户维度）、
把**工程欠账清掉**（Testcontainers / 迁移可重放 / 幂等键治理 / 文档漂移），
三者全部落在既有 9 个服务内，**不新增服务、不新增中间件、不改状态机**。

### 9.2 建议的 Feature 拆分（编号自 031 起，⚠️ 立项前 MUST 复核水位）

> 水位复核方法见 `.workbuddy/memory/MEMORY.md`：`git fetch origin` + `git ls-tree origin/master -- docs/adr docs/specs`
> + 查全部远端分支 / worktree。当前 `030` 已占用，ADR 内部号 `0075` / `0076` 已占用（Proposed）。

| 序 | Feature（建议名） | 范围 | 依赖 | 类型 |
|---|---|---|---|---|
| 0 | **文档收口**（非 Feature，先做） | 修 §1.4 的 D-1~D-6 六项漂移；ADR-0075/0076 登记进 README 两张表 + traceability | — | docs-only，可直推 master |
| 1 | `030-channel-contract-sandbox-callback` | **渠道契约实现轮**：统一契约 + 类型化凭证 + 染色 + 模态落库 + 支付宝沙箱适配器 + notify 端点 + demo 开关 | ADR-0075/0076 ✅ **已转 Accepted** | 代码（**Spec + Plan 已就绪**） |
| 2 | `032-ledger-account-view` | §4 G1（科目余额视图）+ G2（期间与试算平衡）+ G3（待记账清单） | 031 无强依赖，可并行 | 代码 + **Schema 变更** |
| 3 | `033-reconciliation-real-statement` | §5 R1（真实账单来源）+ R2（**N1 商户维度**）+ R4（差异处置策略化） | 涉及 `common-dto` 变更 | 代码 + **跨服务 API 变更** |
| 4 | `034-test-infrastructure` | §8 T1（Testcontainers 渐进）+ T2（迁移可重放门禁）+ T3（ArchUnit RPC 环） | 无 | 工程 |
| 5 | `035-reliability-hardening` | §6 B1（熔断裁决）+ B2（UNKNOWN 分级）+ B3（幂等键治理）+ B4（后置失败台账） | B1 须先裁决 | 代码 + **行为变更** |
| 6 | `036-observability-slo` | §7 O1（SLO 落地）+ O2（告警补齐）+ O3（密钥不入日志约束） | 依赖 O1 目标值确认 | 配置 + 少量代码 |

**顺序理由**：0 是「把账先对平」；1 是唯一「不做就永远只是 mock」的方向，且设计已定稿；
2/3 在资金纵深上互为支撑；4 是 2/3 的**质量前置**（不做 Testcontainers，2/3 的真库并发就测不到）；
5 含行为变更，须单独裁决；6 依赖前序产生的新指标。

### 9.3 与治理的关系：必须先由人类确认的清单

按 Constitution §Governance「人类决策边界」，以下项 **AI MUST NOT 自行执行**：

| # | 项 | 边界类型 | 出处 |
|---|---|---|---|
| H1 | **ADR-0075 / ADR-0076 由 Proposed 转 Accepted**（转 Accepted 即可进入实现） | 重大架构变化 + 新增依赖 | spec 030 §10 |
| H2 | `payment_attempts.channel_mode` 加列 + 迁移脚本 | Database Schema Migration | ADR-0076 R4 |
| H3 | 引入 `alipay-sdk-java`（34.3 MB + ≥9 CVE） | 新增依赖 / 重大架构变化 | ADR-0076 R6 |
| H4 | 退款幂等键口径变更（B3，行为变更） | 资金路径行为变更 | 本文件 §6.2 |
| H5 | 对账事实补 `merchantId`（R2，跨服务 DTO 变更） | API Breaking Change | 本文件 §5.2 |
| H6 | ledger 新增余额表 / `period` 列（G1/G2） | 新增关键资金表 | 本文件 §4.2 |
| H7 | Resilience4j 去留（B1） | 架构取舍 | backlog #5 |
| H8 | SLO 目标值确认（O1） | 非功能目标 | technical-solution §5.1 |
| H9 | 阶段（stage-05）命名与 Feature 编号分配 | 计划权威 | roadmap.md / specs README |
| H10 | 是否放宽「不引入 Testcontainers」的现状（T1 前置：Docker 与 CI 一致性） | 测试载体变更 | Constitution §Engineering.3 |

### 9.4 风险总表

| 风险 | 影响 | 缓解 | 应急 |
|---|---|---|---|
| 沙箱不可复现 → 真机验证被跳过 | 「接了真实渠道」是假绿 | ADR-0076 R10：未验证范围**必须显式记录**，不得默认通过 | 手工 live + 固定签名向量单测兜底 |
| 染色只做一半（入站读 or 出站写） | 重演「全线 403」先例 | K1：入站读与出站写**同一批落地** | 回滚染色批次，`X-Dye-Tag` 缺省即 `MOCK` |
| 真实账单接入后仍静默回退 fixture | 假对账 | R1：真实来源 MUST fail fast，不回退 | 关闭真实来源开关，回 fixture |
| N1 商户维度未补 | 跨商户串账 | R2 优先立项 | 单商户演示前提 + 文档明示 |
| 余额表与分录漂移 | 账实不符 | G1 推荐「同事务更新余额表」+ 试算平衡校验 | 以 `postings` 重算并冲正 |
| 幂等键治理引入行为变更 | 存量调用方语义变化 | B3 单独 Feature + 兼容期 | 保留旧键派生路径 |
| Testcontainers 迁移引入方言差异 | 测试在新载体上假绿 | T1 分三步，逐模块验证约束/时区/JSON | 按模块回退 H2 |
| SDK CVE | 供应链风险 | 端口收口 + 仅 `infra` 依赖 + 不参与对外解析 | 换纯 JDK 实现（只替换一个类） |

### 9.5 验收方式汇总（「我怎么验证」）

| 能力 | 验证方式 |
|---|---|
| 统一契约能容纳四家 | 契约单测：四家参数映射表驱动用例 + 兼容构造器零改动编译（6 处测试桩、4 处构造点） |
| 凭证能回到浏览器 | demo 页选「支付宝沙箱」→ 下单 → `payUrl` 即沙箱收银台地址（**手工 live**） |
| 染色全链路透传 | 响应头回写 `X-Dye-Tag` + MDC `dyeMode` + 跨服务调用链头透传（单测 + E2E） |
| 模态可追溯 | 沙箱单退款 / 主动查询 / 超时扫描**走真实协议**（断言 `payment_attempts.channel_mode = SANDBOX` 后被正确还原） |
| 账能被审计 | 试算平衡表借贷恒等（各币种）；期间关账后拒绝新分录；待记账清单可查可补偿 |
| 真实账单可对 | 真实来源 fail fast；差异可重复识别、可查询、可处置；原始事实不被静默改写 |
| 幂等键真实有效 | 同参重放返回首次结果（**当前是死代码，改后必须由测试钉死**） |
| 工程欠账 | `mvnw clean verify` 全绿 + 迁移在空库/存量库各重放一次一致 + ArchUnit 新规则通过 |
| 零回归 | 不染色路径下既有支付/退款/可靠性/集成/E2E 测试**零改动**通过 |

---

## 附：本文件与其它文档的关系

| 文档 | 关系 |
|---|---|
| `roadmap.md` | 本文件是**阶段目标的输入**，不是计划权威；阶段切换时 MUST 同步 roadmap |
| `docs/specs/README.md` | 本文件对应阶段 `stage-05-channel-and-finance-deepening`；确认后 MUST 在该索引登记并分配 Feature 编号 |
| `technical-solution.md` / `systems/*.md` | 只描述**当前事实**；本文件的 `【目标】` 项在实现完成前 MUST NOT 写入这些文档 |
| `docs/adr/*.md` | 本文件中每一项**新决策**在落地前 MUST 先立 ADR（不得以本文件替代 ADR） |
| `code-debt-backlog.md` | §1.4 的欠账汇总自该清单；闭环后 MUST 回写状态 |
