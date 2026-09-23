# Stage-05 设计总结（031~035）：全局架构、边界与实施顺序

**日期**：2026-09-21　**版本**：v1.0
**状态**：🟡 **Proposed**（设计轮收口，待 Architecture Review；本文件是 031~035 的**跨 Feature 一致性视图**，不替代任何 spec，**不是** L0 当前系统事实源）
**范围**：`031 Ledger & Accounting Foundation` / `032 Reconciliation & Real Statement` /
`033 Test Infrastructure & Business Verification` / `034 Reliability & Failure Recovery` / `035 Observability & SLO`
**输入**：[stage-design.md](stage-design.md)（阶段总目标）、[design-review.md](design-review.md) v1.1（C-01~C-24 冲突清单与 B/H/M 分级）、
五份 Feature Spec、[ADR-0077~0079](../../adr/0077-ledger-accounting-foundation-decisions.md)、
[ADR-0080](../../adr/0080-reconciliation-statement-and-fund-facts.md)、
[ADR-0081](../../adr/0081-test-carrier-and-schema-replayability.md)、
[ADR-0082](../../adr/0082-failure-recovery-ownership-and-compensation.md)、
[ADR-0083](../../adr/0083-observability-baseline-slo-and-cardinality.md)

**本文件的判据**：任何两节之间出现矛盾、或某 Feature 的 acceptance 引用了另一 Feature 未承诺的能力，都算缺陷。

---

## 1. 全局架构（目标态）

阶段目标未变（[stage-design §1.5](stage-design.md) 的三个真问题：**渠道接得进来 / 账出得来 / 工程欠账清得掉**）。
030 收口了「渠道」，031~035 收口「账务 + 对账结算 + 工程」。**结构上零变化**：仍是 9 服务 + 1 演示组件、
同步 Feign + Redis Streams 通知、database-per-service 单 MySQL 实例、Compose 双模式。

```text
                     ┌────────────── 031 账务地基（Ledger）──────────────┐
   业务事实           │  Accounting Event → Posting Rule → Account        │
   (payment/refund/  │  Instance → LedgerTransaction → LedgerEntry       │
    settlement/audit)│  → Balance Projection（同事务） + Period/关账      │
        │            └───────────────▲───────────────▲──────────────────┘
        │ 只发事件                    │ 试算不平/余额  │ CHANNEL_SETTLEMENT
        ▼                            │                │ /CHANNEL_FEE
  ┌───────────────┐          ┌───────┴────────┐  ┌────┴─────────────────────┐
  │ 034 可靠性     │          │ 032 对账纵深    │  │ 032 结算口径              │
  │ 出站失败台账   │─────────►│ 账单导入对象     │─►│ 可结算事实 =              │
  │ 有界扫描/重放  │ 兜底发现  │ 三级匹配(商户,期)│  │ 全部已确认事实 − 未收口差异│
  │ C-19 同事务    │          │ 差异行表+5态复用 │  └──────────────────────────┘
  └───────┬───────┘          └────────────────┘
          │ 指标 / 告警 / SLO 的**语义**由此三域产生
          ▼
  ┌──────────────────────────────────────────────────────────────┐
  │ 035 观测基线：指标目录（唯一登记处）+ Recording Rule(SLO)      │
  │  + 20 条告警(五要素) + 高基数政策 + 密钥/报文不入日志          │
  └──────────────────────────────────────────────────────────────┘
          ▲ 载体（真库/门禁/断言工具）由下方向上供给
  ┌──────────────────────────────────────────────────────────────┐
  │ 033 测试基础设施：test-infra(L2b) + schema 双路径重放门禁      │
  │  + RPC 边允许清单 + 业务验证矩阵（归属表）                     │
  └──────────────────────────────────────────────────────────────┘
```

**一句话读法**：031 定「什么算账务事实」，032 定「外部资金事实如何校验并进入账务」，
034 定「事实传递失败时谁负责到底」，035 定「这一切在运行时是否正常的度量」，033 定「我们凭什么相信以上四条」。

---

## 2. Feature 依赖图

```
                        ┌──────────────[ 031 Ledger ]──────────────┐
                        │ (事件契约/科目两级/余额投影/期间)          │
                        │  ▲ 前置：ADR-0077~0079 Accepted          │
             账务事实读出口│                                          │渠道资金事实入账契约(事件类型预留)
                        ▼                                          ▼
              [ 034 Reliability ]                        [ 032 Reconciliation ]
              台账落地=031§12 的实现归属                   是 CHANNEL_SETTLEMENT/CHANNEL_FEE 的唯一产生方
              C-19 同事务 / 有界扫描                       前置：H11 事实维度 + 结算口径先于 R4
                        │                                          │
                        └────────────┬─────────────────────────────┘
                                     ▼ 指标语义产生处
                          [ 035 Observability ]  ◄── 依赖各域新指标存在后才可挂告警
                          SLO/告警/基数政策/日志禁令

              [ 033 Test Infrastructure ]  ── 纵向覆盖 031 / 032 / 034 / 035
                  033-A/B/C 依赖 H-033-1（Testcontainers 裁决）
                  033-D（RPC 边清单）与 035-A（基数断言）**零前置**，可立即并行
```

与 stage-design §9.2 的**四处修正**（评审时需确认）：

| # | stage-design 的说法 | 设计轮结论 | 依据 |
|---|---|---|---|
| 1 | 「031 余额/期间视图是 032 的前提」 | ✅ 成立，且**双向**：032 的渠道事实入账要 031 的事件类型；031 的 `CHANNEL_FEE` 产生方挂在 032 | 031 §16 / 032 §1.3 |
| 2 | 「033 与 031/032 并行」 | ✅ 成立，但 **033 的 L2b 落地必须早于 031 的实现验收**，否则 031 §10 的并发缺口继续挂着 | 033 §5.3、031 §10 |
| 3 | 「034 依赖 031 完成（复用台账模式）」 | ⚠️ **部分**：034-A（C-19 事务）、034-C（refund 扫描）、034-E（DLQ 读/重放）、034-F（熔断移除）**不依赖 031**，可提前 | 034 §14 |
| 4 | 「035 依赖 031/032/034 产出新指标」 | ⚠️ **部分**：035-A（基数政策）与 A-07（`ledger_posting_failed` 告警）用**已有**指标即可上线 | 035 §15 |

---

## 3. 每个 Feature 的职责（一句话 + 核心交付物）

| Feature | 一句话职责 | 核心交付物（不多不少） |
|---|---|---|
| **031** | **账务的编译器**：把业务事实编译成复式分录，并让余额/期间可出 | `AccountingEvent` 契约（6 类）、科目 Definition/Instance 两级 + 新科目、`PostingRule` 族 + `AccountResolver`、`LedgerTransaction`/`LedgerEntry`、`account_balances` 投影 + rebuild、`ledger_periods` 与关账、派生幂等键 `{eventType}:{sourceId}`、试算平衡端点 |
| **032** | **外部资金事实的接入口**：渠道账单成为可核对、可入账、可处置差异的事实源 | `statement_imports`/`statement_lines`、三级匹配、`reconciliation_differences` 行表、事实链补 `merchantId`+`period`、`CHANNEL_SETTLEMENT`/`CHANNEL_FEE` 产生方、结算事实口径改造、退款渠道引用精确关联 |
| **033** | **可信度的地基**：提供「能证伪」的载体与门禁，不提供业务用例 | `deployment/test-infra`（L2b 基座）、schema 双路径重放门禁、迁移守卫规范 + lint、RPC 边允许清单 `rpc-edges.txt`、业务验证矩阵与归属表 |
| **034** | **失败的收口人**：给每个失败面出口、上限与 Owner | `pending_postings` 落地（031 §12 的实现归属）+ 统一出站失败台账、`onRefundResult` 事务边界、refund 侧有界扫描 + `REQUESTED` 在途扫描、UNKNOWN 老化分桶、DLQ 可读可重放、Resilience4j 移除 |
| **035** | **运行时的度量**：让「是否正常」可被回答且有阈值 | 指标目录（唯一登记处）、`slo-recording.yml`（4 SLO + 错误预算）、20 条告警五要素、高基数政策 + 可执行断言、密钥/报文不入日志（排除 + 禁令 + 测试）、看板两个新分区 |

---

## 4. 每个 Feature 的边界（不越界的判据）

| Feature | 边界内 | **越界即缺陷** |
|---|---|---|
| 031 | 借贷方向、科目、分录结构、幂等键派生、余额投影、期间关账 | 查询/计算费率；接受调用方传入 `accountId`/`direction`；改写已 POSTED 分录 |
| 032 | 账单导入与归一、匹配、差异记录与处置编排、渠道资金事实**发出**、结算事实口径 | 修改 Payment/Refund 状态；直接改 `LedgerEntry`；引入渠道 SDK；自动改判弱匹配 |
| 033 | 测试载体、DDL 装配、重放门禁、架构规则、矩阵与归属 | 写业务断言用例；以「033 会补」替代业务 Feature 的 acceptance；为覆盖率改名/删测试 |
| 034 | 失败台账、有界重试/扫描、事务边界、DLQ 重放、依赖去留 | 判定资金成败（只能查/只能重放）；把台账当事实源；给 Payment 加账务进度状态；改 MQ 协议 |
| 035 | 指标目录、SLO 表达式、告警与 runbook、基数与日志政策 | 执行恢复动作（看到 DLQ 增长不自动重放）；启用通用脱敏（推翻 ADR-0027）；改业务代码以「方便打点」 |

---

## 5. Feature 之间禁止发生的依赖（硬清单）

| # | 禁令 | 出处 | 违反后果 |
|---|---|---|---|
| **P-1** | 032 **MUST NOT** 直接修改 `LedgerEntry`；修正只能发 `ADJUSTMENT` 事件 | 032 §17、031 §16、brief §10 | 分录不再 append-only，审计链断裂 |
| **P-2** | 031 **MUST NOT** 承担费率查询与计算（`merchantFee`/`channelFee` 由业务侧算好传入） | 031 §6/§13、brief §5 | 账务域持有业务定价权，账务无法独立审计 |
| **P-3** | 034 **MUST NOT** 为「账务进度」给 Payment/Refund 新增状态 | 034 §6.4 | 同一事实双主（支付域 vs 账务域） |
| **P-4** | 035 **MUST NOT** 引入新的运行时依赖或 APM/新中间件；**MUST NOT** 启用通用脱敏 | 035 §2/§10、ADR-0083 决策 3/4 | 违反 Constitution §IV 门槛；推翻负责人裁决 |
| **P-5** | 033 **MUST NOT** 拥有业务断言用例；业务 Feature **MUST NOT** 把验收外包给 033 | 033 §4、ADR-0081 决策 4 | 033 变成无底洞，业务域零验收 |
| **P-6** | 032 与 034 **MUST NOT** 各自新建一套差异/失败生命周期；032 复用 audit 5 态、034 复用台账三态 | 032 §7、034 §9 | 同一待办有两处权威，人工处理互相看不见 |
| **P-7** | 任何 Feature **MUST NOT** 重新设计 030（渠道契约/模态/验签）、029（Redis Streams 半消息协议）、C-11（surplus 自动退款流程） | brief §4/§10、033 §11、034 §2/§6.3 | 已交付能力被推翻，回归面失控 |
| **P-8** | 031/032 的新告警 **MUST NOT** 早于其依赖的指标存在（035 §12 M-3）；035 **MUST NOT** 用未实测数据声称 SLO 达成（§8.3） | 035 §12/§8.3 | 永不触发的告警 = 假安全；虚假合规 |
| **P-9** | 跨 Feature 的 schema 变更 MUST 全部走 033 §6.2 守卫模式；MUST NOT 用 MariaDB 方言 | 033 §6、ADR-0081 决策 2 | 存量库升级失败（`016` 已发生过一次） |

---

## 6. Domain Ownership（谁拥有什么）

| 域 | 拥有的权威 | 载体 | 不得由他人写入 |
|---|---|---|---|
| **Payment（payment-service）** | 支付事实、渠道 attempt 两层事实、自动退款触发 | `payments`、`payment_attempts` | 账务分录、订单状态 |
| **Order/Transaction（order-service）** | 交易与订单状态、**已退额累加**、surplus 判定、TXRF 交易层退款单 | `orders`、`transactions`、`transaction_refunds` | 支付成败判定 |
| **Refund（payment-service/refund）** | PMRF 支付层退款事实 | `refunds` | TXRF 状态 |
| **Ledger（ledger-service）** | 复式分录、科目、余额投影、期间 | `ledger_transactions`、`ledger_entries`、`account_balances`、`ledger_periods` | 上游业务事实 |
| **Reconciliation（reconciliation-service）** | 渠道账单事实、匹配结果、差异及其处置 | `statement_imports`、`statement_lines`、`reconciliation_differences`、`audit_*` | 分录本体（P-1） |
| **Settlement（settlement-service）** | 结算批次、净额、门禁 | `settlement_batches`、`settlement_adjustments` | 对账差异判定 |
| **Reliability（横切，034）** | 失败台账（**调用方各自持有**）、恢复出口政策、DLQ 重放工具 | `pending_postings`（3 个 schema）、`mq:dlq:*` | 业务事实本身 |
| **Observability（横切，035）** | 指标目录、SLO 定义、告警与基数/日志政策 | `docs/`、prometheus 规则文件 | 业务行为 |
| **Test Infra（横切，033）** | 测试载体、重放门禁、架构规则 | `deployment/test-infra`、`deployment/schema`、CI | 用例内容 |

---

## 7. Source of Truth

| 事实 | Source of Truth | 派生视图（可重建，不是真相） | 反例禁令 |
|---|---|---|---|
| 支付是否成功 | `payments.status`（由渠道权威答复推进） | 订单 `paid_minor`、余额、报表 | 不得用余额反推支付成败 |
| 资金会计事实 | `ledger_entries`（append-only） | **`account_balances` 投影**、试算平衡表、结算报表 | 「不得把 `account.balance` 当唯一真相」（brief §5）⇒ 投影可 rebuild |
| 商户应付多少 | `MERCHANT_PAYABLE` 商户实例的分录（031 §7.2） | 结算批次草稿 | 不得由结算侧自行累计 |
| 渠道实际收到多少 | **032 的 `statement_lines`**（外部事实）+ `CHANNEL_SETTLEMENT` 分录 | 对账差异、毛利报表 | 不得用内部事实冒充渠道事实（今天 `sample.csv` 回退正是此病） |
| 哪些事实可结算 | 032 §8.4 公式（全部已确认事实 − 未收口差异净影响） | 结算批次 | 不得以「匹配成功的 `matches`」为集合（C-13） |
| 是否已入账 | Ledger 的 `postings`（幂等键 `{eventType}:{sourceId}`） | `pending_postings`（**台账是补偿辅助，不是事实源**） | 034 §9.2 红线 |
| 失败是否已收口 | 台账/差异行的**终态**（`REPOSTED`/`ABANDONED`、`RESOLVED`） | 指标与告警 | 指标不得当队列用 |
| SLO 是否达成 | `slo-report.md` 的实测数据（**首次运行前 = 无数据**） | 看板曲线 | 035 §8.3 禁令 |

---

## 8. Idempotency Boundary

| 边界 | 幂等键 | 拥有者 | 唯一约束 | 幂等后的语义 |
|---|---|---|---|---|
| 渠道调用 | `paymentNo` + attempt 复用（同 attempt 重放） | payment | `uk_attempts_*` | 回放首次结果 |
| 记账事件 | **`{eventType}:{sourceId}`（Ledger 派生）** | **Ledger**（上游不拼键） | `uk_postings_idempotency_key` + `uk_event_source(event_type, source_id)` | 返回首次 `LedgerTransaction`，**绝不双记**（031 §9 / ADR-0077 D4） |
| 结算批次 | `batchNo`（031 收口 M1，原为数值 `batchId`） | settlement | `uk_settlement_batch_no` + 033 真库验证 | 同 `batchNo` 可安全重放 |
| 账单导入 | `(channel_code, period, content_fingerprint)`；行级 `(import_id, line_no)`；差异级 `(period, kind, sourceType, sourceId)` | reconciliation | 三层 UK（032 §8.3） | 重复导入不重复产差 |
| 退款（交易层） | TXRF 在途守卫（区分重放/重试，030/B7 已修） | order | `RefundPolicy` 累计上限为最后防线 | 未受理可同号重放；已受理回放 |
| 事件消费 | `msgId` + 业务键（消费端幂等） | 各消费方 | — | **DLQ 重放 MUST 依赖它**（034 §10） |
| 待记账重放 | 复用记账事件幂等键 | 调用方台账 | `uk(event_type, source_id)` | 5 次重投 ⇒ Ledger 只有 1 套分录 |

**唯一一条全局原则**：幂等键 MUST 由**事实的权威方**派生，MUST NOT 由调用方拼字符串（C-01 双前缀键的根因）。

---

## 9. Failure Recovery Boundary

| 层 | 负责到什么程度 | 交给谁 | 硬上限 |
|---|---|---|---|
| 渠道层（030 已有） | 瞬时失败内联重试、超时转 UNKNOWN | payment 查询收敛 | 3 次 / 30s |
| 支付收敛层 | 主动查询到权威答复；耗尽显性化告警 | 人工 resolve / 032 对账 | 5 次 / 15s |
| 账务传递层（034） | 落台账 + 退避重投 + 耗尽 `ABANDONED` | 账务负责人（人工 replay） | 5 次 / `1s→10m` |
| 事务内一致性（034） | 后置累加与终态同事务，失败即回滚重来 | 框架 | 单本地事务 |
| 退款推进层（034） | `REQUESTED` 在途重放、PMRF UNKNOWN 查询 | 人工登记 + 审计 | ≤2 次 / ≤5 次 |
| 事件层（029 已有 + 034） | 退避重试 → DLQ；DLQ 可读可重放 | 人工触发 replay | 3 次；回查 ≤10 次 |
| 事实校验层（032） | 差异行 + 5 态处置 + 挂账/调账 | 财务人工（自动处置范围极窄） | 关账门禁阻断 |
| 最后防线 | **032 审计（`MISSING_POSTING` 等 BLOCKER）+ 031 试算平衡** | 账务 owner | 不平即 `BALANCE_BREAK` + 冻结结算 |

**三条禁令**（brief §8 的「不能只是失败就重试」的正面回答）：
① 不猜成败（R-3）；② 不因后置失败回滚前序事实（R-1）；③ 无界重试一律视为缺陷（R-2）。

---

## 10. Observability Boundary

| 归 035 管 | 不归 035 管 |
|---|---|
| 指标目录（唯一登记处）、命名与类型规范、高基数政策 | 指标所依赖的**业务语义**（由各域 spec 定义） |
| SLO 表达式、错误预算、burn rate 告警 | 恢复动作本身（属 034；告警只通知，不重放） |
| 告警五要素与 runbook 模板 | 值班制度与组织流程 |
| 日志关联标准（traceId/bizNo/9 个 ID 的落点） | 日志存储与保留策略（属部署运维） |
| 密钥与报文不入日志的约束与测试 | 通用脱敏引擎（ADR-0027 裁决不变） |
| 看板分区（新增 SLO + 资金健康两区） | 业务经营报表（属 032 结算报表） |

**跨 Feature 的接口约定**：031/032/034 新增任何指标，MUST 同时更新 035 §5.2 目录（含值域与告警意义）；
035 MUST NOT 要求其他 Feature 为「方便打点」改业务逻辑。

---

## 11. Testing Boundary

| 测试类型 | 归属 | 判据 |
|---|---|---|
| L1/L2a/L3/L5 用例本体 | 各业务 Feature（031~035 自己） | spec 的 acceptance 必须自己列全 |
| L2b（真库）用例本体 | 各业务 Feature | 「只有一个赢家 / 不双记 / 累加不丢」类断言 MUST 落此层 |
| L2b **载体**、Docker 契约、DDL 装配 | 033 | 只有基础设施代码，零业务断言 |
| schema 重放与 lint 门禁 | 033 | 与业务无关的静态断言 |
| 架构规则演进（含 RPC 边清单、`src/main` 禁 testcontainers、`@Transactional` 内禁 Gateway） | 033（034/035 提需求） | 规则由 033 实现，语义由各域定义 |
| 故障注入钩子 | 033 提供，031/032/034 使用 | — |
| 指标断言工具 | 033 提供，035 与各域使用 | — |
| demo 场景脚本挂载 | 033（不重写为 Java） | nightly 跑既有 shell |

**矩阵权威**：[spec 033 §8 业务验证矩阵](033-test-infrastructure/spec.md)。其余四份 spec 的 acceptance
凡涉及「并发 / duplicate / UNKNOWN / compensation」，MUST 引用矩阵行号而不是自行描述层次。

---

## 12. Implementation Order（建议，待 Architecture Review 确认）

```
门 0  裁决（一次性拿全，避免逐个 Feature 卡住）
      ├─ 已有：H1/H2/H3/H4/H15/H16（design-review §13）
      ├─ 031：ADR-0077~0079 的 D-1~D-7（含 H3 负净额语义、H12 余额表/period）
      ├─ 032：H-032-1(API)、H-032-2(schema)、H-032-4(渠道事实产生方)
      ├─ 033：H-033-1(Testcontainers)、H-033-4(RPC 环口径)
      ├─ 034：H-034-1(熔断去留)、H-034-2(A/B 方案)、H-034-5(自动重放)
      └─ 035：H-035-1(SLO 目标值)、H-035-2(notify 日志排除)
        ▼
门 1  无前置的三件事（可与裁决并行，风险最低）
      ├─ 033-D  RPC 边允许清单 + rpc-edges.txt      ← 零新依赖
      ├─ 035-A  高基数政策 + MeterRegistry 断言      ← 今天已全绿，纯守护
      └─ 035-D(仅 A-07) ledger.posting_failed 告警   ← 补一个已有指标的缺口
        ▼
门 2  031 实现（账务地基）——含 033-A/B 的载体先行（否则 §10 并发验收无处落）
        ▼
门 3  ┌─ 032 实现（账单对象 + 事实维度 + 渠道事实入账 + 结算口径先于策略）
      ├─ 034-A  C-19 事务边界（**不依赖 031**，且是唯一静默资金损失项 ⇒ 可与门 2 并行）
      ├─ 034-C/E/F  refund 扫描 / DLQ 读重放 / 熔断移除（不依赖 031）
      └─ 033-C/E  真库三类落点 + nightly 增强
        ▼
门 4  034-B 台账落地（依赖 031 的派生幂等键）+ 034-D 迟到成功追回
        ▼
门 5  035-B/C/E  SLO recording rules + 积压告警 + 看板两分区 + runbook 全量补齐
        ▼
门 6  L0 文档收口（technical-solution / systems/*.md）+ code-debt-backlog 销账
```

**排序的两条硬约束**（不可协商）：
1. **034-A（C-19）MUST 与 032 的 R4 之前完成**——`refunded_minor` 少计会让 032 的可结算事实集合建立在错数字上；
2. **032 的结算口径改造（决策 5）MUST 先于其差异策略化**——顺序颠倒会让自动化在错误分母上决策。

---

## 13. Out of Scope（阶段级不做，五份 spec 的 out-of-scope 之上）

| 类别 | 不做 | 出处 |
|---|---|---|
| 架构形态 | K8s / Service Mesh / API 网关 / 新中间件 / 分库分表 / CQRS / Event Sourcing / 多 Region / 多活 | stage-design §2.4、brief §12 |
| 事务与协调 | 2PC / XA / Seata / Saga 框架 / Temporal / Camunda | Constitution、brief §12 |
| 账务 | 规则 DSL / 可视化规则引擎 / 配置化会计平台 / Drools / 多币种清分 / 税费分账 / 完整会计准则 / FX Engine | brief §5/§12、031 §16 |
| 资金动作 | 真实出款 / 银行对接 / 自动改单 / 自动判定资金成败 | stage-design §5.2 R3、032 §18、034 §18 |
| 观测 | APM/Trace 后端 / 通用报文脱敏 / 指标全量重构 / 异常检测与容量预测 | 035 §2/§19、ADR-0083 |
| 测试 | 覆盖率门禁 / 变异测试 / Pact-WireMock-Gatling / 测试平台 / demo 脚本 Java 化 | 033 §2/§18 |
| 依赖 | 新增运行时依赖（033 的 Testcontainers 属**测试作用域**，且需 H-033-1 裁决；034 的方向是**删**依赖） | brief §12 |
| 组织 | 值班制度、双人复核流程改造、真实渠道 CVE 治理决策 | 035 §14、spec 030 C-2 |

---

## 14. 待人工 Review 的问题（Architecture Review 清单）

| # | 问题 | 为什么需要人 |
|---|---|---|
| 1 | **五份 spec 的裁决集是否合并为一次门 0**（031 的 D-1~D-7 + 032 六项 + 033 五项 + 034 五项 + 035 四项 ≈ 27 项） | 逐个 Feature 等裁决会让实现反复卡住；但一次问 27 项也是负担 ⇒ 需负责人选择节奏 |
| 2 | **033 与 031 的先后**：031 的并发验收依赖 L2b 载体，但 033-A 依赖 H-033-1。若不放宽 Testcontainers，031 §10 的投影并发只能降级为「手工验证 + 登记未验证」 | 决定 031 acceptance 的真实形态，不是技术问题 |
| 3 | **032 的「渠道 + 周期」级聚合入账粒度**（一条 `CHANNEL_SETTLEMENT` 表示整期净额）是否满足未来逐笔审计需要 | 账务粒度一旦定下，逐笔化需新的历史重构 |
| 4 | **034 对 C-19 取 A 方案（同事务）**：`onRefundResult` 里 `order`/`transactions`/`transaction_refunds` 三表同库同服务，理论上可事务；需负责人确认「订单状态推进」与「已退额累加」确实应同生同灭（业务上是否有允许部分成功的场景） | 事务边界即业务语义 |
| 5 | **035 的 SLO-4（账务完整性）是否作为对外承诺** | 目标值一旦进 L0，就是可被追责的指标 |
| 6 | **031 代码已在工作区（162 文件未提交）但本轮只做设计**：设计文档描述的 031 目标态与已写代码是否一致，需在 Architecture Review 时逐条对表（尤其 §10 投影与 §14 迁移集） | 设计与实现脱节的风险，只有对代码才判得出 |
| 7 | 本轮新增 ADR **0080~0083** 的编号与分组是否接受（每组含多条决策、全部 🟡 Proposed） | 编号是计划权威（design-review §13 H15 同类） |

---

## 15. 本轮设计未做的事（诚实标注）

- ❌ **未改任何 Java / SQL migration / 测试代码 / 配置文件**（brief §1 的禁止项）。
  文中所有 `path:line` 均为 master 现状证据，非改动。
- ❌ **未运行 demo / E2E 验证设计可行性**——032 的三级匹配、034 的台账重放属「设计自洽但未实测」，实现期须按各自 acceptance 实测。
- ❌ **未复核 031 工作区代码与设计的一致性**（031 有 162 个未提交文件在另一 worktree，本 Summary 的 031 描述**基于 spec 文档**，见 §14 问题 6）。
- ❌ **未验证 SLO 目标值的可达性**——无真实运行数据，全部标 `[目标]`。
- ❌ **未做工作量/排期估算**（tasks.md 属 Spec Kit 下一阶段产物）。
- ⚠️ **文档同步未完成即不得进实现**：`technical-solution.md` 与 `systems/*.md` 仍是 030/031 之前的 L0 事实，
  031~035 的 `【目标】` **MUST NOT** 被当作现行事实引用（stage-design §2.1 P6 与 brief §13 同一纪律）。
