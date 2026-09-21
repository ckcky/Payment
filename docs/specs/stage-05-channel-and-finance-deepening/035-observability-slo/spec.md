# Spec: 035-observability-slo（可观测性与 SLO）

**Feature**：035　**标题**：Observability & SLO
**版本**：v1.0（Draft，设计轮产物）　**日期**：2026-09-21
**状态**：🟡 **设计完成，待 Architecture Review 与负责人裁决**（本轮**只出设计，不改代码、不改配置、不加依赖**）
**前置**：031（账务事件/试算平衡/期间）、032（对账差异与渠道资金事实）、034（失败台账与恢复出口）产出新指标；本 Feature 是它们的**观测收口**
**输入权威**：[stage-design §7](../stage-design.md)、[design-review §12.2 H7、§12.3 M8/M9、§13 H14](../design-review.md)、
[Constitution §Observability](../../../../.specify/memory/constitution.md)

**阅读约定**：`【现状】`= 已存在的事实（附 `path:line`）；`【目标】`= 设计意图，**尚未实现**；`【待确认】`= 人类决策。

---

## 0. 定位与一句话目标

**让运行时能回答七个问题：支付是否正常 / 哪个渠道异常 / 哪个环节异常 / UNKNOWN 有多少 / 资金是否对得上 / 账务是否失败 / 结算是否积压。**

今天的实情（§1）是：**指标很多（93 个业务计数器）、告警很少（7 条）、SLO 为零、
且最重要的三个资金面（账务 / 结算 / MQ）一条告警都没有。**
所以本 Feature 的重点不是「加更多指标」，而是：

1. **把已有事实接上告警**——034 的台账、031 的试算不平、032 的差异积压、MQ 的 DLQ 现在**全都无人被通知**；
2. **给 SLO 一个可计算的定义**——Recording Rule + 错误预算，且**明确区分目标值与实测值**；
3. **把基数与密钥两条纪律写成政策**——今天基数纪律是「碰巧做对了」（§1.4），密钥纪律是**反向的**（§1.5）。

**明确不做**：不引入 APM / Trace 后端 / 日志平台新组件；不建「看板竞赛」；不做指标全量重构。

---

## 1. Background：证据化的现状

### 1.1 采集面已闭环（本 Feature 不动它）

| # | 【现状】 | 证据 |
|---|---|---|
| 1 | 各服务 `/actuator/prometheus` 暴露；Prometheus 以 9 个 `job_name` 静态抓取宿主端口 | `payment-service/src/main/resources/application.yml:154-163`、`deployment/prometheus/prometheus.yml:13-40` |
| 2 | 业务指标统一经 `BusinessMetrics` 端口 → `MicrometerBusinessMetrics`（`Counter`/`Timer`），无 registry 时回落 Noop | `common/common-core/.../observability/MicrometerBusinessMetrics.java:15-33` |
| 3 | 结构化日志：`logback-spring.xml` 单行 kv，含 `traceId` / `bizNo` MDC | `common/common-core/src/main/resources/logback-spring.xml:13` |
| 4 | 两条专用日志流：`ACCESS_LOG`（method/uri/status/costMs + 正文）、`FINANCIAL_AUDIT`（单行 JSON，含幂等键/金额/币种/前后态） | `accesslog/AccessLogFilter.java:19,100-101`、`observability/StructuredAuditLogger.java:14,21,35` |
| 5 | Loki + Promtail 采集，Grafana 单看板已含四信号分区（Latency / Traffic / Errors / Saturation）+ RPC + 业务区 + 日志区 | `deployment/grafana/dashboards/payment-arch.json`（面板标题核对） |
| 6 | 自研 Trace：`X-Trace-Id` 头 + `TraceContext`（ThreadLocal + MDC）跨服务透传，**无** span 概念 | `common/common-core/.../trace/TraceIdFilter.java:19,30`、`TraceContext.java:48,64` |

### 1.2 告警面：只有 7 条，且**资金三域零告警**

| # | 【现状】 | 证据 |
|---|---|---|
| 7 | 全部规则：`PaymentUnknownBacklog`、`PaymentRetryExhausted`、`PaymentQueryExhausted`、`PaymentOrderIllegalStateRejected`、`RefundFailure`、`RefundDownstreamFailure`、`ReconciliationDifference` | `deployment/prometheus/rules/payment-alerts.yml:17-81`（`grep -c alert:` = **7**） |
| 8 | **`ledger.posting_failed` 有指标、无告警**——记账失败今天只能靠人看盘或对账事后发现 | 指标：`payment/infra/client/FeignLedgerPostingGateway.java:53`、`refund/.../RefundFeignLedgerPostingGateway.java:58`；规则文件无此项 |
| 9 | **结算零告警**（`settlement.*` 指标存在，规则文件无一条）；**MQ 零告警**（`mq.*`/DLQ 无指标亦无规则） | 同上规则文件；`grep` 指标名清单 |
| 10 | 规则只有 `summary`/`description` 两类 annotation，**无 `runbook_url`、无 `dashboard` 链接、无 owner/team 标签** | `payment-alerts.yml:22-24,32-34,42-44` |
| 11 | 无 recording rule 文件，`rule_files` 只挂告警文件一个 | `prometheus.yml:10-11` |

### 1.3 SLO：Constitution 有目标值，系统里零落地

| # | 【现状】 | 证据 |
|---|---|---|
| 12 | stage-design §7.2 O1 列 4 项 SLO（同步查询 P99 ≤500ms / 同步命令 P99 ≤1s / 资金入口可用性 ≥99.9% / 对账达成率 ≥99%），**全部标 `[目标]`，无 Recording Rule、无错误预算、无看板面板** | [stage-design §7.2](../stage-design.md)、`deployment/prometheus/`（无 recording 文件） |
| 13 | 无 SLO 相关人类裁决记录（= design-review §13 **H14**「SLO 目标值确认」，仍未裁决） | design-review |

### 1.4 基数纪律：**目前是碰巧做对了，没有条文**

| # | 【现状】 | 证据 |
|---|---|---|
| 14 | 93 个业务指标名，标签键只有 **16 种低基数维度**：`module`(84) / `reason`(11) / `op`(9) / `result`(6) / `topic`(5) / `period`(3) / `group`(3) / `source`(2) / `phase`(2) / `kind`(2) / `cause`(2) / `target` / `status` / `state` / `scope` / `bucket` 各 1 | 全仓 `counter("...")` / `timer("...")` 提取统计 |
| 15 | **没有任何文档规定「ID 不得作为 label」**，也无 lint/测试守住 ⇒ 下一个人加 `paymentNo` 标签不会被拦住 | `docs/guides/`、`.specify/memory/constitution.md` 无对应条文 |
| 16 | `period`（`YYYY-MM`）作为标签出现 3 处：基数有界（12/年）但**随时间单调增长且不回收**，属慢性基数泄漏 | 同上统计 |
| 17 | 命名风格不统一：`payment.query_exhausted`（点分，Micrometer→Prometheus 转换后为 `payment_query_exhausted_total`）与 `dye_tag_rejected_total`（**已直接写 Prometheus 名**）并存 | `ChannelQueryService.java:115` vs `dye/DyeFilter.java:50` |

### 1.5 密钥 / 报文：今天的默认行为**与 Constitution §Security.4 相反**

| # | 事实 | 证据 |
|---|---|---|
| 18 | `ACCESS_LOG` 用 `ContentCachingRequestWrapper` **完整记录请求/响应正文** | `accesslog/AccessLogFilter.java:74-76,100-101` |
| 19 | 脱敏只有**钩子**，实现是 `PassThroughBodyMasker`（原样透传）——负责人已拍板「本期不真脱敏」（ADR-0027 / spec 021 D3） | `accesslog/SensitiveBodyMasker.java:6-9`、`PassThroughBodyMasker.java` |
| 20 | 排除路径默认**只有 `/actuator/**`** ⇒ **`/internal/channels/alipay/notify` 的 form 报文（含 `sign`、`out_trade_no`、`total_amount`）今天会被整段写入 ACCESS_LOG** | `AccessLogProperties.java:14,20-26` |
| 21 | `StructuredAuditLogger.mask()` 提供了脱敏工具但只用于审计字段，不覆盖访问日志 | `observability/StructuredAuditLogger.java:53-61` |
| 22 | design-review 把这列为 **H7 / §12.2 H7**（支付宝 notify 端点纳入「密钥/完整报文不入日志」的**显式约束与测试**），且 Constitution 的口径是「不引入通用脱敏时，MUST 靠显式约束 + 测试」 | design-review §12.2 H7、stage-design §7.2 O3 |

---

## 2. Goals / Non-Goals

### Goals

| # | 目标 | 完成判据 |
|---|---|---|
| **G1** | 指标目录成型：**每个指标**有「业务意义 / 维度 / 告警意义 / Owner Feature」四列，新增项由 031/032/034 驱动 | §5 目录表；无「用途不明」条目 |
| **G2** | 高基数政策成为**条文 + 可检查断言**（ID 禁入 label，含 `period` 例外规则） | §7，且能被 033 的载体测试 |
| **G3** | 4 项 SLO 落成 Recording Rule + 错误预算告警，**目标值与实测值在文档与看板上分离表达** | §8 |
| **G4** | 告警从 7 条扩到覆盖七个域（含账务/结算/MQ/限额/DLQ/染色），**每条有 Trigger / Severity / Meaning / First Investigation Path / runbook 链接** | §9 |
| **G5** | 密钥与完整报文不入日志：以**显式约束 + 路径排除 + 测试**实现（不启用通用脱敏） | §10 |
| **G6** | Trace / 日志关联标准：9 个业务 ID 明确「哪里放 trace attribute、哪里放 log field、哪里绝不进 Metrics label」 | §6 |
| **G7** | Golden Signals 到业务域的映射成型并落到看板分区 | §4 |

### Non-Goals

- ❌ 不引入 Tempo / Jaeger / Zipkin / SkyWalking / OTel Collector / Datadog（P1：不加中间件；brief §12）。
- ❌ 不启用 Micrometer Tracing / 不引入 `micrometer-tracing-*` 依赖（§1.1#6 的自研 traceId 保留，理由见 §11）。
- ❌ 不做通用报文脱敏引擎（ADR-0027 裁决不变；§10 用**排除 + 约束**而非 mask）。
- ❌ 不做指标全量重命名（§12 迁移政策：**新指标按新规范，老指标不改名**，避免看板与告警断链）。
- ❌ 不做容量预测 / AIOps / 异常检测模型。
- ❌ 不把 Grafana 拆成 N 个看板（一个看板 + 分区，见 §13）。
- ❌ 不做 24×7 oncall 值班制度（组织问题，不在工程 Spec 内）。

---

## 3. Observability Architecture（目标态，结构不变）

```text
业务代码 ──BusinessMetrics(端口)──► Micrometer ──► /actuator/prometheus ──► Prometheus ─┬─► 告警(规则文件)
   │                                                                    │
   ├─ MDC(traceId,bizNo,dyeMode) ─► logback 单行 kv ─► 文件 ─► Promtail ┴─► Loki ─► Grafana(日志区)
   │                                                                          │
   └─ FINANCIAL_AUDIT(单行 JSON, 独立 logger) ─────────────────────────────► Grafana(审计区)

【本 Feature 只动三处】
 ① rule_files: 增加 recording-rules.yml（SLO/错误预算）与 alerts 扩容        ← §8 §9
 ② Grafana: 新增「SLO 与错误预算」分区 + 资金健康分区                        ← §13
 ③ 约束与测试: 高基数政策 + 密钥/报文不入日志                                 ← §7 §10
```

不新增服务、不新增采集器、不改 `logback-spring.xml` 的 pattern（kv 单行格式已满足 Loki 解析）。

---

## 4. Golden Signals 映射（不机械套用）

| 信号 | Payment | Channel | Ledger | Reconciliation | Settlement | MQ |
|---|---|---|---|---|---|---|
| **Latency** | HTTP P99（同步命令）；**UNKNOWN 收敛耗时**（→终态的墙钟，已有面板） | 渠道调用耗时（分 `channelCode`，**仅 3~4 值可作 label**） | 记账 RPC 耗时（决定资金事实可见延迟） | 单批次核对耗时 | 批次执行耗时 | 发布→消费的端到端延迟（**MQ 唯一有意义的 latency**） |
| **Traffic** | 下单/支付请求速率 | 按渠道的请求速率（渠道健康分母） | `ledger.posted{source}` 速率 = **记账吞吐** | 批次频率 × 事实条数 | 批次净额绝对值 | 各 topic 生产/消费速率 |
| **Errors** | `payment.failed` / `payment.unknown_rate` / 5xx | 渠道失败与超时率（分渠道） | **`ledger.posting_failed` + `ledger.unbalanced` + `trial_balance_break`** | 差异条数与类型分布 | 门禁拒绝 + 批次失败 | **DLQ 增量 + 半消息超次** |
| **Saturation** | HikariCP（已有）、JVM（已有） | — | `pending_postings` 中 `PENDING` 行数（**账务积压**） | 未收口差异数（**对账积压**） | `PENDING/UNKNOWN` 批次数与金额（**结算积压**） | `XPENDING` 长度、stream `XLEN`、消费组 lag |

**判据**：Saturation 对本系统的正确含义是**「待办积压」**而非 CPU——资金系统的故障形态是「事情堆着没人做」，
所以 §5 的指标目录里每个域都 MUST 至少有一个 gauge 型积压指标（这是本表的主要产出）。

---

## 5. Metrics Catalog

### 5.1 命名与类型规范（新指标适用；老指标不改名）

| 规则 | 内容 | 现状动因 |
|---|---|---|
| N-1 | 业务指标名用 **`<domain>_<thing>_<unit>`** 小写下划线，**不带** `_total`（Micrometer→Prometheus 自动补 counter 后缀） | 修 §1.4#17 的双风格（`DyeFilter.java:50` 手写 `_total` 是反例） |
| N-2 | 一个指标一个语义；**MUST NOT** 用标签值表达「不同指标」（例：`xxx_count{kind=a}` 与 `xxx_count{kind=b}` 语义不同 ⇒ 拆） | 防目录腐化 |
| N-3 | 金额类指标 MUST 用**最小货币单位整数**（`amountMinor`）表达，MUST NOT 出现浮点 | AGENTS.md 红线在观测面的延伸 |
| N-4 | 积压类 MUST 是 `Gauge`（可增可减），发生次数 MUST 是 `Counter` | §4 Saturation 判据 |

### 5.2 目标目录（本 Feature 新增/收口的部分；`【已有】` 标注复用）

| 指标 | 类型 | 维度（labels） | 业务意义 | 告警意义 | 来源 |
|---|---|---|---|---|---|
| `payment_success_rate` | 由 recording rule 计算（非新 counter） | `service` | 成功数 ÷ 终态数 | SLO-3 分子 | 【已有】`payment.*` 计数器派生 |
| `payment_failure_rate` | 同上 | `service` | 失败占比 | 突增 = 渠道或配置问题 | 派生 |
| `payment_unknown_rate` | 同上 | `service` | UNKNOWN 占比 | **资金事实未定比例**——本系统最重要的单一健康度 | 派生 |
| `payment_unknown_age` | Counter | `bucket`(`0_5m`/`5_30m`/`30m_24h`/`gt_24h`) | 老化分布 | A-05（§9） | **034 §7.2 新增** |
| `channel_request` | Counter | `channelCode`,`result` | 渠道调用量与结果 | 分渠道失败率 | 【已有】 |
| `channel_timeout` | Counter | `channelCode` | 渠道超时（**不是**平台错误） | A-03 | 【已有】 |
| `channel_query_success_rate` | recording rule | `channelCode` | 主动查询收敛成功率 | UNKNOWN 出口健康度 | **034 派生** |
| `refund_success_rate` / `refund_unknown_age` | recording / Counter | `bucket` | 退款健康与积压 | A-06 | 034 §7.3 |
| `ledger_posted` | Counter | `source`(`PAYMENT_CAPTURE`/`REFUND`/`CHANNEL_SETTLEMENT`/…) | **记账吞吐 = 资金事实入账速率** | 分母 | 031 |
| `ledger_posting_failed` | Counter | `caller` | 入账失败 | **A-07（critical）**——今天零告警的 P0 缺口 | 【已有指标，新增告警】 |
| `ledger_posting_pending` | Gauge | `caller` | `pending_postings` 中 `PENDING` 行数 = **账务积压** | A-08 | **034 §9 新增** |
| `ledger_posting_abandoned` | Counter | `caller` | 重试耗尽需人工 | **A-09（critical）** | 034 |
| `ledger_unbalanced` | Counter | `currency` | 构造期平衡校验拒绝次数 | **A-10（critical，>0 即告警）** | 031 |
| `trial_balance_break` | Gauge | `currency`,`period` | 试算不平（`Σ借 ≠ Σ贷`） | **A-10** | 031 §10 |
| `balance_rebuild_applied` | Counter | — | 投影 rebuild 次数（**正常应≈0**） | A-11 | 031 §10 |
| `period_close_rejected` | Counter | `reason` | 关账被拒（不平 / 有 PENDING 残留） | 运营信号，非告警 | 031 §11 |
| `reconciliation_difference` | Counter | `kind`（8 类，032 §8.3） | 差异产生速率 | A-12（已有雏形） | 032 |
| `reconciliation_pending` | Gauge | `period` | **未收口差异条数 = 对账积压** | A-13 | 032 |
| `statement_import` | Counter | `result`(`NORMALIZED`/`REJECTED`)、`channelCode` | 账单导入健康度 | A-14 | 032 |
| `statement_fallback` | Counter | — | 静默回退 `sample.csv`（**032 后应恒 0**） | A-14（>0 即告警） | 【已有指标，新增告警】 |
| `settlement_pending_amount` | Gauge | `state`(`PENDING`/`PROCESSING`/`UNKNOWN`) | **积压金额**（不是条数——结算的痛是钱压着） | A-15 | 032/034 |
| `settlement_failure` | Counter | `reason` | 批次失败 / 门禁拒绝 | A-16 | 【已有】 |
| `settlement_gate_block` | Counter | `kind` | fail-closed 拦截次数 | 高值 = 上游账务/对账不健康 | 【已有】 |
| `mq_publish` / `mq_consume` | Counter | `topic`,`result` | 事件吞吐 | 分母 | 【已有】 |
| `mq_pending_count` | Gauge | `topic`,`group` | `XPENDING`（在途未 ACK） | A-17 | **034 §10 新增** |
| `mq_dlq_size` | Gauge | `topic` | **DLQ 积压 = 已丢失的自动化出口** | **A-18（critical）** | 034 |
| `half_message_check_exhausted` | Counter | `topic` | 半消息超次回查进 DLQ | A-18 | 034 |
| `limit_inflight_leak` | Gauge | `window` | 额度预占在途泄漏（027 两阶段） | A-19 | 【已有补偿扫描器，补 gauge】 |
| `dye_tag_rejected` | Counter | `reason` | 非法染色值被拒 | **>0 通常意味着配置错误或上游未对齐** | 【已有】 |

**总量控制**：新增约 18 个指标 + 派生 recording rule，**不是**为了凑 brief 的清单——
每一行都对应 §4 Saturation 判据或 031/032/034 的新事实。凡「无法说出告警意义」的一律不进目录（brief §8 反数量竞赛）。

---

## 6. Trace & Log Correlation Standard

### 6.1 九个业务 ID 的落点政策

| ID | Metrics label | Trace/log 关联字段 | 出现位置 | 理由 |
|---|---|---|---|---|
| `transactionNo` | ❌ 禁 | ✅ MDC `bizNo`（单笔链路主键）+ `FINANCIAL_AUDIT.entityId` | 日志 / Loki | 高基数 |
| `paymentNo` | ❌ 禁 | ✅ MDC `bizNo` + 日志 kv | 日志 | 高基数 |
| `paymentAttemptId` | ❌ 禁 | ✅ 日志 kv（渠道层排障必需） | 日志 | 高基数 |
| `channelRequestId` / `channel_reference` | ❌ 禁 | ✅ 日志 kv | 日志 | 高基数；渠道方沟通凭据 |
| `refundNo`（TXRF/PMRF） | ❌ 禁 | ✅ MDC `bizNo` | 日志 | 高基数 |
| `channelRefundNo` | ❌ 禁 | ✅ 日志 kv | 日志 | 高基数 |
| `ledgerTransactionNo` | ❌ 禁 | ✅ 日志 kv + 审计 | 日志 | 高基数 |
| `reconciliationId` / `batchNo` | ❌ 禁 | ✅ 日志 kv | 日志 | 中基数但随时间无界 |
| `settlementNo` | ❌ 禁 | ✅ 日志 kv | 日志 | 高基数 |
| **`merchantId`** | ❌ **禁**（数量级随入驻增长无界） | ✅ 日志 kv；**按商户分析走 SQL/Loki，不走 Metrics** | 日志 / DB | brief §8 与 §1.4 纪律的正面回答 |
| `channelCode` | ✅ **允许**（枚举 ≤ 少量） | ✅ | 两者 | 有界 |
| `currency` | ✅ 允许（枚举） | ✅ | 两者 | 有界 |
| `period` | ⚠️ **例外条款**：只允许出现在 `Gauge`（瞬时值、单序列覆盖写）且 MUST 配 `max_periods_in_memory` 检查；Counter 上 **禁**（单调累积 ⇒ 序列只增不减） | ✅ | — | 修 §1.4#16 |

### 6.2 关联链（可执行判据）

【目标】给 `paymentNo` 起，**四跳内**必须能拼出完整链路，且不依赖 Metrics：

```text
grep bizNo=<paymentNo> payment-service.log     → 入口/状态迁移/记账出站
  └ 同 traceId 横向拉通 order / ledger / settlement 日志
      └ FINANCIAL_AUDIT(action,idempotencyKey,entityId) → 账务事实与幂等键
          └ deployment/demo/trace-grep.sh（既有工具）串全链
```

现有工具与 MDC 键（`traceId`/`bizNo`/`dyeMode`）已足够 ⇒ **本 Feature 不改日志格式**，
只补两条纪律：
1. **所有资金写操作日志 MUST 带 `bizNo`**（缺失视为缺陷，可由 033 的载体加断言）；
2. `ACCESS_LOG` 的 `uri` MUST 归一化路径变量（`/payments/{ref}` 而非具体号），否则访问日志自己就成了高基数源。

---

## 7. High Cardinality Policy（条文 + 机器可检查）

【目标】写入 `engineering-standards` 可观测章节，四条：

| # | 条文 | 检查方式 |
|---|---|---|
| HC-1 | Metrics label 值集合 MUST 在**发布前可穷举**（枚举类型、有界字符串、状态机态） | 代码 review + 单测（§7.1） |
| HC-2 | **MUST NOT** 以业务单号、用户/商户 ID、时间戳、UUID 作为 label 值 | 静态扫描：`counter(`/`timer(` 调用的标签实参不得是 `*No`/`*Id` 变量名 |
| HC-3 | 新 label 引入 MUST 在指标目录（§5.2）登记其**值域** | 目录 PR 门禁 |
| HC-4 | 单指标序列数上限预算 **200**；预计超出 MUST 改用 log + Loki 查询或 Gauge 覆盖写 | CI 可选步骤（`/actuator/prometheus` 序列数抓取） |

### 7.1 可执行断言（不建平台，用现成载体）

- 【目标】单测：遍历 `MeterRegistry` 已注册 meters，断言**每个 meter 的每个 tag value 属于注册过的允许集合**；
- 【目标】033 的 L2a 载体跑该测试（无需真库）；序列数检查挂 `verify.yml` 的可选步骤（本地/CI 均可跑）；
- 该测试是**增量守护**，不追溯既有 93 指标（§1.4 显示今天全绿，所以零改动即可通过——这是「政策化已有事实」而非重构）。

---

## 8. SLO 与错误预算

### 8.1 四个 SLO（**目标值 ≠ 实测值**）

| # | SLO | 目标 `[目标]` | SLI 定义（Recording Rule 可计算式） | 错误预算 | 备注 |
|---|---|---|---|---|---|
| **SLO-1** | 同步查询延迟 | P99 ≤ **500ms**（30 天窗口） | `histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{uri=~"/internal/.*",method="GET"}[30d])))` | 0.1% 请求超阈 | 只覆盖只读入口 |
| **SLO-2** | 同步命令延迟 | P99 ≤ **1s**（30 天） | 同上 `method!="GET"`，排除 `/internal/channels/**`（渠道回调由对端计时，我方无法承诺） | 0.1% | — |
| **SLO-3** | 资金入口可用性 | ≥ **99.9%**（30 天） | `1 - (服务端 5xx 与业务不可用响应) / 总请求`，入口 = 下单 + 支付 + 退款受理三端点 | 43.2 min/30d | **不含渠道不可用**（渠道属外部依赖，另立渠道 SLO） |
| **SLO-4** | 账务完整性（对账达成率） | ≥ **99%**（当期账单周期） | `已入账的已确认事实数 / 应入账的已确认事实数`（Ledger 侧 `ledger_posted` 与调用方事实数比对；**不是**时间型 SLI） | 1% 事实待查 | **本 Feature 最有业务价值的一项**，也是唯一跨服务 SLI |

> 【待确认 **H-035-1**】四个目标值确认（= design-review §13 **H14**）。
> 表格里的数字全部继承 stage-design §7.2 的 `[目标]` 值，**不是实测结果**。
> **未获裁决前**：告警以「burn rate」形式上线但**静默（不 page）**，SLO-4 允许先只出报表不告警。

### 8.2 错误预算的表达形式（Recording Rules）

【目标】`deployment/prometheus/rules/slo-recording.yml`（新增文件，挂在 `prometheus.yml` 的 `rule_files`）：

```text
slo:availability_ratio{group="payment_entry"}          = 1 - error_ratio
slo:error_budget_remaining{group="payment_entry"}      = (1 - sli) / (1 - target)
slo:burn_rate_fast{group="payment_entry",window="1h"}  = error_ratio_1h / (1-target)
slo:burn_rate_slow{group="payment_entry",window="6h"}  = error_ratio_6h / (1-target)
```

- **多窗口多速率**（fast 1h / slow 6h）是 Google SRE 的标准做法，也刚好适配本系统 15s 抓取与 30 天窗口；
- 告警只在 **fast 与 slow 同时越阈**时 page（§9 A-01），避免单窗抖动误报；
- 延迟类 SLO MUST 用 `histogram_quantile` 且 MUST NOT 用平均值（本仓 HTTP 直方图 buckets 沿用 Actuator 默认，够用）；
- SLO-4 无法用比率型 burn rate 表达（分母是事实数不是请求数），MUST 以**「未入账事实绝对条数 + 最老账龄」**两个 Gauge 表达（→ A-20）。

### 8.3 目标与实测在文档上的分离（防虚假合规）

- 【目标】SLO 表写入 `technical-solution.md` 时 MUST 保留 `[目标]` 标注；
- 【目标】实测值只在 `docs/operations/slo-report.md`（新增，人工/脚本按月填写）出现，**首次运行前该文件内容 = 「无数据」**；
- 判据：**任何文档 MUST NOT 出现「SLO 已达成」的表述，除非 `slo-report.md` 中有对应时间段的真实数据**。

---

## 9. Alert Rules（从 7 条扩到 20 条，每条五要素）

> 五要素：`Trigger` / `Severity` / `Meaning`（业务后果，不是技术指标）/ `First Investigation Path`（第一步看什么）/ `Owner`。
> 新增的 13 条里，**8 条直接对应今天「有指标无告警」的资金缺口**。

| # | Alert | Trigger | Sev | Meaning | First Investigation Path | Owner |
|---|---|---|---|---|---|---|
| A-01 | `SloAvailabilityBudgetBurn` | fast>14.4 且 slow>6（1h/6h 双窗） | **critical** | 资金入口正在超烧预算 | ①SLO 面板看哪个入口 ②`ACCESS_LOG status=5xx` 聚合 ③依赖服务健康（Nacos/MySQL） | oncall |
| A-02 | `SloLatencyP99Breached` | P99 双窗越阈 | warning | 同步接口变慢，用户在等 | ①`http_server_requests` 分 uri ②HikariCP 活跃 ③慢 SQL | oncall |
| A-03 | `ChannelTimeoutSpike` | `increase(channel_timeout[5m]) / increase(channel_request[5m]) > 0.1` 持续 10m | critical | 渠道不健康，UNKNOWN 将成批产生 | ①分渠道看是否单渠道 ②沙箱/公网可达性 ③超时档（034 §8） | payment |
| A-04 | `PaymentUnknownBacklog` | 【已有】`increase(payment_timeout_total[5m])>0` | warning→**保留** | 超时转 UNKNOWN | 【已有 runbook 项】 | payment |
| A-05 | `UnknownAgeEscalation` | `payment_unknown_age{bucket="gt_24h"}` >0 | **critical** | 跨期风险：资金事实悬置将影响关账 | ①`GET /internal/payments/unknown?age=` ②人工 resolve（034 §7.1） | 账务+oncall |
| A-06 | `RefundUnknownAge` | `refund_unknown_age{bucket="gt_24h"}`>0 | critical | 用户钱该退未退 | ①PMRF/TXRF 状态 ②034 §7.3 扫描器是否生效 | payment-refund |
| A-07 | **`LedgerPostingFailure`** | `increase(ledger_posting_failed[5m])>0` | **critical** | **账没记上——资金事实与账本开始背离** | ①`GET pending-postings?status=PENDING`（034 §9）②Ledger 服务健康 ③试算面板 | **账务** |
| A-08 | `LedgerPostingBacklog` | `ledger_posting_pending > 0` 持续 30m | warning | 补偿重试未收敛 | 台账明细 → 人工 replay | 账务 |
| A-09 | **`LedgerPostingAbandoned`** | `increase(ledger_posting_abandoned[1h])>0` | **critical** | 重试耗尽，**只能人工** | 台账 `ABANDONED` 列表 + 对账 `MISSING_POSTING` | 账务（升级） |
| A-10 | **`TrialBalanceBreak`** | `trial_balance_break > 0` 或 `increase(ledger_unbalanced[5m])>0` | **critical（最高）** | **复式记账被破坏 = 账务不可信** | ①`GET /trial-balance` 定位科目 ②冻结结算（031 §10 门禁）③rebuild 前后分录比对 | 账务 owner |
| A-11 | `BalanceRebuildUsed` | `increase(balance_rebuild_applied[1d])>0` | warning | 投影漂移过（正常应≈0） | 漂移原因（是否有绕过 PostingEngine 的写入） | 账务 |
| A-12 | `ReconciliationDifferenceSpike` | `increase(reconciliation_difference[1d]) > 基线` | warning | 外部资金事实与内部不一致 | ①分 `kind` 看形态 ②是否单渠道/单商户 ③032 差异队列 | 财务 |
| A-13 | `ReconciliationPendingAging` | `reconciliation_pending > 0` 且最老 > 3 天 | warning | 对账积压，关账将被门禁挡 | 032 差异生命周期 `PENDING` 列表 | 财务 |
| A-14 | **`StatementSourceDegraded`** | `increase(statement_fallback[1d])>0` 或 `statement_import{result="REJECTED"}`>0 | **critical** | **账单不可信或对不上 = 对账失去外部锚点** | ①渠道账单文件/接口可用性 ②解析失败明细（032 §11） | 财务+recon |
| A-15 | `SettlementBacklog` | `settlement_pending_amount > 阈值` 持续 1h | warning | 商户的钱压着 | ①批次状态 ②门禁拒绝原因（`settlement_gate_block`） | 结算 |
| A-16 | `SettlementBatchFailure` | `increase(settlement_failure[1h])>0` | critical | 出款批次失败 | ①同 `batchNo` 是否可安全重放（034 X-14）②Ledger 侧是否已记（避免双记） | 结算 |
| A-17 | `MqConsumerLag` | `mq_pending_count{topic,group} > N` 持续 10m | warning | 事件在堆，下游状态滞后 | ①消费组是否活 ②`claimStale` 是否触发 | 各域 |
| A-18 | **`MqDlqGrowing`** | `increase(mq_dlq_size[1h])>0` | **critical** | **已有消息永久失去自动处理机会** | ①DLQ 条目与原因（`dlqReason`）②034 §10 重放 | oncall |
| A-19 | `LimitInflightLeak` | `limit_inflight_leak > 0` 持续 30m | warning | 额度预占泄漏，用户可能被误限 | ①`LimitCompensationScanner` 是否工作 ②`RESERVE` 无对应 CONFIRM/RELEASE | payment |
| A-20 | `DyeRejectedAnomalous` | `increase(dye_tag_rejected[5m])>0` | info→**ticket** | 有调用方发了非法染色值 | ①来源服务/调用方 ②配置漂移 | 平台 |

**已有 7 条的处置**：全部保留（A-04/A-05 是其中 UNKNOWN 类的细化），
`RefundFailure` / `RefundDownstreamFailure` / `PaymentRetryExhausted` / `PaymentQueryExhausted` / `PaymentOrderIllegalStateRejected` 原样保留并**补 `runbook_url` 与 `Owner`**。
**判据**：035 不删任何既有告警；只细化与扩容。

---

## 10. 密钥与完整报文不入日志（H7 / O3，本 Feature 唯一的「纠偏」性设计）

**问题精确描述**（§1.5）：`ACCESS_LOG` 记录完整 req/resp 正文；脱敏钩子是**故意的透传桩**（负责人裁决）；
排除路径只有 `/actuator/**` ⇒ 支付宝 notify 的 form 报文（含 `sign`）今天进日志。

**约束：本 Feature MUST NOT 借机启用通用脱敏**（会推翻 ADR-0027 裁决 = 越界）。
Constitution §Security.4 的口径正是「脱敏未重新引入时，靠**显式约束 + 测试**」。

### 10.1 三层做法

| 层 | 内容 | 影响面 |
|---|---|---|
| **C-1 路径排除** | `access-log.exclude-paths` 增加 `/internal/channels/**`（渠道回调入口一律不落正文） | 纯配置，零代码 |
| **C-2 定向实现** | 允许**单个服务**注册自己的 `SensitiveBodyMasker` Bean（`@ConditionalOnMissingBean` 已预留覆盖点）——若渠道团队后续要字段级 mask，走这条路，**不动 common-core 默认实现** | 零默认行为变更 |
| **C-3 显式禁令 + 测试** | ①条文：`sign` / `privateKey` / `token` / `app_cert` / 完整渠道报文 MUST NOT 出现在任何日志/指标/审计字段；②测试：notify 端点与渠道出站用例 MUST 断言捕获日志中不含 `sign=` 与私钥片段；③`FINANCIAL_AUDIT` 保持只记必要字段（现状即对，`:35-45`） | 新增约束与测试 |

### 10.2 排除后如何排障？

`/internal/channels/**` 不落正文，但保留：`method`/`uri`/`status`/`costMs`/`traceId`/`bizNo`（**由 `out_trade_no` 提供 `bizNo`，本就存在**）
+ `AlipayNotifyController` 既有处理日志（映射结果、拒绝原因）。**排障能力损失 = 拿不到原始 form**，
补偿手段是渠道方提供报文或本地重放（沙箱可重发），可接受。

> 【待确认 **H-035-2**】是否接受「notify 端点正文不落日志」换来的排障能力损失（vs 引入字段级 mask 推翻 ADR-0027）。
> 推荐：**接受 C-1+C-3**，不动 ADR-0027。

---

## 11. Trace Standard（保留自研，显式否决 APM）

| 方案 | 内容 | 判定 |
|---|---|---|
| **A 保留自研 traceId** | `X-Trace-Id` + `TraceContext` + MDC 三键（`traceId`/`bizNo`/`dyeMode`），过滤链定序已存在 | ✅ **推荐** |
| B 引入 Micrometer Tracing + OTel bridge | 获得 span 树与采样 | ❌ 新增运行时依赖 + 新后端（Collector/Tempo），违反 P1 与 brief §12；**本仓跨服务链路只有 2~3 跳**，span 树收益低 |

**推荐理由（要能说服 reviewer）**：本系统的排障主键是**业务单号**不是 trace（`Invariants.java`、`trace-grep.sh` 都以 `orderNo`/`paymentNo` 为中心）；
自研方案已能「一个 `traceId` 横向拉通 9 服务日志」。补 span 会引入采样与后端运维，
而**资金链路一个都不能采样丢**——即「上了 APM 也不敢用」。

【目标】只补两项低成本纪律：
1. **`traceId` MUST 在进入异步/MQ 时传递**：envelope 已携带（029），消费侧 MUST 恢复 MDC（现状部分做到，需补断言）；
2. **MUST NOT** 引入 `spanId`/`traceparent` 双权威——保持单头 `X-Trace-Id`。

---

## 12. 迁移与兼容政策

| # | 政策 |
|---|---|
| M-1 | 既有 93 指标 **不改名**（§2 Non-Goals）；`DyeFilter.java:50` 的 `dye_tag_rejected_total` 属**风格例外**，登记不改（改名会断已有面板） |
| M-2 | 新指标 MUST 进 §5.2 目录后才允许上线（目录 PR 与代码 PR 同一批次） |
| M-3 | 告警扩容 MUST 分批：`critical + 资金三域`（A-07/A-09/A-10/A-18/A-14）先行，其余随 031/032/034 实现各自落地（**告警不得早于它所依赖的指标存在**） |
| M-4 | 每条新告警 MUST 同时新增 runbook 段落（同 PR），否则不算交付（brief「每条告警都有处置动作」） |

---

## 13. Dashboard（一个看板 + 新分区）

【目标】`payment-arch.json` 在现有 6 个分区（总览 / 四信号 / RPC / 业务指标 / 日志）后新增两个：

| 新分区 | 面板 | 说明 |
|---|---|---|
| **⑥ SLO 与错误预算** | 4 个 SLO 的 SLI 曲线 + 剩余预算百分比 + burn rate（fast/slow）双值 | 目标值以 `threshold` 线画，实测值单独系列 ⇒ 视觉上强制区分（§8.3） |
| **⑦ 资金健康（Money Health）** | `ledger_posting_pending/abandoned`、`trial_balance_break`、`reconciliation_pending`、`settlement_pending_amount`、`mq_dlq_size`、`payment_unknown_age` 分桶 | **六个「积压/悬置」Gauge 放一屏** = 本系统的值班首屏；判据是「这一屏全 0 才算健康」 |

不拆新看板、不加变量竞赛（保持现有 datasource 与模板变量）。

---

## 14. Runbook Boundary

| 内容 | 归属 |
|---|---|
| 每条告警的**处置动作** | `docs/operations/runbook.md`（已有文件，§12 M-4 要求同 PR 补段落） |
| 指标含义与值域 | 本文 §5（唯一目录权威） |
| 告警阈值调优记录 | `docs/operations/slo-report.md`（新增，月度） |
| **不做** | 不自建告警平台、不接 PagerDuty/飞书机器人（brief §12 反过度设计；Alertmanager 通知渠道属部署配置，非本 Spec 范围） |

**Runbook 段落模板（五要素齐，缺一不收）**：
`症状 → 影响面（哪些资金受影响）→ 第一步查询（具体 PromQL/端点）→ 判定分支 → 处置动作（含人工 resolve/replay 端点）→ 升级路径与 Owner`

---

## 15. 交付切片

| 切片 | 内容 | 依赖 |
|---|---|---|
| **035-A** | §7 高基数政策条文 + HC-1~HC-4 的可执行断言（§7.1） | **无**（今天已全绿，可立即做，纯守护） |
| **035-B** | §10 密钥/报文（C-1 路径排除 + C-3 禁令与测试） | **H-035-2** |
| **035-C** | §8 Recording Rules + 错误预算 + A-01/A-02 + 看板⑥ | **H-035-1** |
| **035-D** | 资金三域告警补挂：A-07（已有指标即可上）；A-08/A-09/A-10/A-11 随 031/034 实现 | 031/034 |
| **035-E** | 积压类：A-05/A-06/A-13/A-15/A-17/A-18/A-19 + 看板⑦ | 032/034 的指标 |
| **035-F** | runbook 全面补齐（含既有 7 条补 `runbook_url`/Owner） | 随各批 |

> 【目标】**035-A/B/D(仅 A-07) 不依赖任何其他 Feature 的实现**，可在 031~034 实现期间并行落地。

---

## 16. Human Decisions Required

| # | 决策项 | 边界类型 | 出处 | 推荐 |
|---|---|---|---|---|
| **H-035-1** | 4 项 SLO 目标值确认（500ms / 1s / 99.9% / 99%）与错误预算窗口（30 天 / 双窗 burn rate） | 非功能目标 | design-review §13 **H14**、stage-design §7.2 O1 | 采纳表值；未实测前 page 静默，只出报表 |
| **H-035-2** | notify 端点正文不落日志（路径排除）是否接受，vs 启用字段级脱敏（会推翻 ADR-0027） | 安全口径 / 排障能力 | design-review §12.2 **H7** | **接受排除**，不推翻 ADR-0027 |
| **H-035-3** | 保留自研 traceId（否决 APM 引入）是否正式立 ADR 收口 backlog #9 的「脱敏假象」与 tracing 长期疑问 | 架构取舍 | §11、code-debt-backlog | **立一条 ADR**（观测基线：自研 trace + 无 APM + 高基数政策） |
| **H-035-4** | A-10 `TrialBalanceBreak` 是否连带**自动冻结结算**（今天 031 §10 的结算门禁已 fail-closed，是否再加告警级熔断） | 资金路径行为 | 031 §10、§9 | **不额外自动冻结**（既有门禁已挡，重复自动化会掩盖根因） |

**最小裁决集**：**H-035-1、H-035-2**。

---

## 17. Risks & Mitigations

| # | 风险 | 缓解 |
|---|---|---|
| 1 | 告警噪声导致 oncall 麻木（7→20 条） | §12 M-3 分批上线；§8.2 双窗判据；info 级只开工单不 page（A-20） |
| 2 | 阈值无实测数据支撑即拍脑袋 | 全部标 `[目标]`；page 静默期先只出报表（H-035-1）；slo-report 记录调优历史 |
| 3 | 指标依赖未实现（告警引用不存在的指标 ⇒ 永不触发） | §12 M-3 硬规则「告警不得早于指标存在」；CI 可选校验：规则文件中的指标名 MUST 出现在 `/actuator/prometheus` 抓取样本 |
| 4 | SLO-4（对账达成率）跨服务分母不可靠 | 以「未入账绝对条数 + 最老账龄」双 Gauge 替代比率型 SLI（§8.2），避免伪造精确度 |
| 5 | `/internal/channels/**` 排除后无法取证原始报文 | §10.2 说明补偿手段；需要时走 C-2 定向 mask，不改全局 |
| 6 | 高基数政策被后续「临时排查」绕过 | HC-3 目录 PR 门禁 + §7.1 遍历断言（红即拦）|

---

## 18. Acceptance Criteria

| # | 判据 |
|---|---|
| AC-1 | §5.2 目录中每个指标四列齐全（业务意义/维度/告警意义/Owner），且无一行「告警意义 = 无」 |
| AC-2 | §9 每条告警五要素齐全，且 runbook 有对应段落（§12 M-4） |
| AC-3 | §7 政策条文可被 §7.1 的断言机器检查（测试存在且当前全绿） |
| AC-4 | 4 项 SLO 有可计算的 Recording Rule 表达式，且**目标值与实测值在文档与看板两处视觉上分离** |
| AC-5 | 未新增任何运行时依赖、未引入 APM/新中间件/新服务（依赖清单变化 = 0） |
| AC-6 | 既有 7 条告警与既有 93 指标零删除、零改名 |
| AC-7 | ADR-0027（不启用通用脱敏）未被推翻；§10 的实现是排除 + 约束 + 测试 |
| AC-8 | 覆盖七域（Payment/Channel/Refund/Ledger/Reconciliation/Settlement/MQ）逐一有指标 + 有告警 + 有 runbook |

---

## 19. Out of Scope

- ❌ APM / Trace 后端 / 采样与 span 传播标准（W3C traceparent）引入。
- ❌ 通用报文脱敏引擎、字段级 mask 的默认启用（ADR-0027 裁决不变）。
- ❌ 指标体系全量重构与改名。
- ❌ 业务分析型看板（商户经营报表属 032 结算报表，不属观测）。
- ❌ 日志平台扩容、存储周期治理（Loki 保留策略属部署运维）。
- ❌ 告警值班制度、事件复盘流程（组织过程）。
- ❌ 自动自愈（「看到 DLQ 增长就自动重放」类）——恢复动作归属 034，且 MUST 由人或有界扫描器执行。
- ❌ 成本/计费类指标、容量预测模型。
