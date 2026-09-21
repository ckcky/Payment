# Plan: 035-observability-slo

> Spec：[spec.md](spec.md)（§0–§19）。本 Plan 是实施落点与决策记录；三件套纪律同 032/034。

## 0. 裁决记录（2026-09-21，负责人按推荐批准）

**H-035-1~H-035-4 全部按 spec §16 推荐方案批准**（负责人批量裁决，与 031~034 的 23 项同批收口）：

| # | 裁决 | 落地含义 |
|---|---|---|
| H-035-1 | 采纳 4 项 SLO 目标值（500ms / 1s / 99.9% / 99%，30 天窗 + 双窗 burn rate） | Recording Rule + A-01/A-02 可 page（burn rate 判据）；文档保留 `[目标]` 标注，实测只进 `slo-report.md` |
| H-035-2 | 接受 notify 端点正文不落日志（路径排除），**不推翻 ADR-0027** | `AccessLogProperties` 默认排除增补 `/internal/channels/**` + 禁令条文 + 测试 |
| H-035-3 | 立 ADR 收口观测基线（自研 trace + 无 APM + 高基数政策） | ADR-0083 🟡→🟢 Accepted，同步 adr/README 索引与速查 |
| H-035-4 | 不为 TrialBalanceBreak 追加自动冻结结算 | 仅告警 + runbook；沿用 031 §10 既有 fail-closed 门禁 |

## 1. 现状核对（实施前事实盘点，spec §1 的 2026-09-21 增量）

- 告警基线已不是 7 条：034 已加 4 条（`LedgerPostingPendingBacklog`/`MqDlqNonEmpty`/`UnknownAgedOver24h`/`LedgerPostingAbandoned`），现共 **11 条**。spec §9 的 20 条设计中 A-05/A-06 已被 `UnknownAgedOver24h` 合并覆盖、A-08/A-09/A-18 已有同名实现——**不重命名、不重复添加**（M-1/AC-6），新增 13 条即达成 §9 全表覆盖（规则总数 24 = 20 项设计，2 项合并 + 2 项细化）。
- spec §5.2 若干标【已有】的指标实际不存在（`trial_balance_break`、`ledger_unbalanced`、`balance_rebuild_applied`、`channel_request/timeout`、`settlement_pending_amount`、`limit_inflight_leak`、`reconciliation_pending`、`mq_pending_count`）——按 M-3「告警不得早于指标存在」，本 Feature 以**最小观测埋点**补齐（零领域语义变更），或改用已有信号表达（见 §3）。此为 spec 假设与代码的事实漂移，已在 acceptance 记录。

## 2. 模块落点

| 切片 | 落点 | 内容 |
|---|---|---|
| A 高基数政策 | `docs/guides/engineering-standards.md` §7；`deployment/architecture-tests/.../MetricsCardinalityTest.java`；`deployment/test-infra/.../MetricsAssert.java` | HC-1~HC-4 条文 + 全仓 metrics 调用静态扫描（标签键白名单 + `*No`/`*Id` 值禁令 + period-仅-Gauge 例外 + 存量基线棘轮）+ 可复用的 registry 遍历断言机制（033 载体） |
| B 密钥/报文 | `common/common-core/.../AccessLogProperties.java`（默认排除 `/internal/channels/**`）+ `AccessLogFilterTest` + engineering-standards §7/§8 禁令 | C-1 排除 + C-3 禁令与测试；不动 `PassThroughBodyMasker`（ADR-0027） |
| 指标补齐 | ledger-service（`ledger.unbalanced` / `trial.balance.break` / `balance.rebuild.applied`）、payment-service（`channel_request` / `channel_timeout` / `limit_inflight_leak`）、settlement-service（`settlement_pending_amount`） | 全部单行级埋点/gauge，标签只用有界枚举（HC-1/HC-2） |
| C SLO | `deployment/prometheus/rules/slo-recording.yml`（新）+ `prometheus.yml` rule_files + `docs/operations/slo-report.md`（新） | 4 项 SLI + error_budget_remaining + burn_rate fast/slow；SLO-4 按 §8.2 用「未入账绝对条数（`ledger_posting_pending`）+ 账龄（`payment_unknown_age{bucket=gt_24h}` 出现即悬置）」双信号表达 |
| D/E 告警 | `deployment/prometheus/rules/payment-alerts.yml` 扩 13 条 + 既有 11 条补 `runbook_url`/`dashboard`/`owner` | A-01/02/03/07/10/11/13/14/15/16/17/19/20；A-13 用 `difference−resolved` 差值表达（无新指标）；A-17 用 redis-exporter `redis_streams_pending_messages`（compose `REDIS_EXPORTER_CHECK_STREAMS` 修正为 `mq:stream:*`，当前 pattern 不匹配键名 ⇒ 面板空采） |
| F runbook | `docs/operations/runbook.md` §5（指标目录收口为唯一登记处，G1）+ §5.3 新告警五要素段（M-4） | 每条新告警一段：症状→影响面→第一步查询→判定分支→处置→升级/Owner |
| G 看板 | `deployment/grafana/dashboards/payment-arch.json` 新增 row「⑥ SLO 与错误预算」+「⑦ 资金健康」 | 目标值以 threshold 线、实测独立系列（§8.3 视觉分离）；资金健康六积压一屏 |
| 文档同步 | ADR-0083 翻 Accepted + adr/README；technical-solution §5.3；specs/README；roadmap；CHANGELOG；acceptance.md | 三件套收口同 032/034 |

## 3. 关键取舍

1. **`reconciliation_pending` / `mq_pending_count` 不新增代码指标**：分别用 PromQL 差值与 redis-exporter 表达——避免为看板造第二个事实源（AC-6 精神：政策化已有事实）。指标目录（runbook §5）按"表达式 = 指标"登记。
2. **`trial_balance_break` 落为 Counter**（发生次数，N-4 纪律：积压用 Gauge、次数用 Counter；关账校验不平是**事件**非持续态），spec 表格的 Gauge+period 与 HC-2 period 例外冲突时取 HC 政策优先，acceptance 记录该偏差。
3. **`channel_timeout` 口径**：渠道契约（`PaymentChannel` javadoc）规定超时/断连 MUST 映射 UNKNOWN，故 counter 在「查询返回 UNKNOWN 或调用抛错」时 +1——目录注明代理语义，A-03 阈值 `timeout/request > 0.1 sustained 10m`。
4. **既有 93 指标零改名零删除**；`dye_tag_rejected_total` 风格例外登记不改（M-1）。
5. 不新增任何依赖（AC-5：依赖清单变化 = 0）。

## 4. 验证

- 单模块：`./mvnw -o verify -pl <module> -am -fae`（JDK26）；全量：`./mvnw -o clean verify -fae` ≥ 基线 968 tests 全绿。
- `promtool check rules`（payment-alerts.yml + slo-recording.yml，经 payment-prometheus 容器）；Grafana JSON `python3 json.load` 校验。
- demo 五场景（reset + happy-path + audit + reconciliation + mq）全 PASS（worktree jar 重建 + 容器 `--build` 后）。
