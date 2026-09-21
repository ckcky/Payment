# Acceptance: 035-observability-slo

> 回写文档（2026-09-22）。逐条对应 spec §18 AC-1~AC-8；证据为 `mvnw verify` 全量、真栈 demo 运行、promtool 校验与 metrics 抓取实证。负责人 2026-09-21 裁决 H-035-1~4 按 spec 推荐方案批准（ADR-0083 Accepted，`72cc867`）。

## 1. 验收判据（spec §18）

| AC | 判据 | 证据（提交） | 结果 |
|---|---|---|---|
| AC-1 | 目录每指标四列齐全（业务意义/维度/告警意义/Owner），无「告警意义 = 无」 | `7836285` runbook §5 重写为唯一登记处：六域分表（Payment/Refund/渠道、Ledger、Reconciliation、Settlement、MQ、平台），每行 指标名/类型/标签值域/业务意义/告警映射/Owner；「确认不存在埋点」黑名单防死规则回流 | ✅ |
| AC-2 | 每条告警五要素齐全且 runbook 有对应段落（M-4） | `ea677aa` payment-alerts.yml 24 条全带 Trigger(expr+for)/Severity/Meaning(summary)/First Investigation(description)/Owner + runbook_url/dashboard_url；`7836285` runbook §5.4 二十四段处置（症状→影响面→第一步→判定→处置→升级） | ✅ |
| AC-3 | §7 政策可被机器断言（测试存在且全绿） | `c7ac302` MetricsCardinalityTest 静态扫描（键白名单/HC-2 单号禁令/period 棘轮基线 13/阳性对照防空转）+ MetricsAssert.assertTagValuesWithinAllowedSet 运行期遍历（含正负 self-test）；本文件 §3.6 记录门禁捕获真实漂移的自证 | ✅ |
| AC-4 | 4 项 SLO 有可计算 Recording Rule 且目标值/实测值双处视觉分离 | `9f6e843` slo-recording.yml 四组（slo:burn_rate_fast/slow、error_budget_remaining）；`7836285` 看板⑦行目标以 threshold 线画、实测为曲线；slo-report.md 目标列标 `[目标]`（§8.3 判据） | ✅ |
| AC-5 | 零新增运行时依赖 / 无 APM / 无新中间件新服务 | pom 依赖清单零变化（本分支全部提交 diff 无 pom 改动）；G6 保留自研 X-Trace-Id（spec §6 增量核对） | ✅ |
| AC-6 | 既有告警零删除零改名 | `ea677aa`：既有各条（PaymentUnknownBacklog/RetryExhausted/QueryExhausted/OrderIllegalStateRejected/RefundFailure/RefundDownstreamFailure/ReconciliationDifference + 034 四条）alert 名与 expr 语义保留，仅补五要素注解；对照 origin/master 逐名 diff 核验 | ✅ |
| AC-7 | ADR-0027 未推翻；实现 = 排除 + 约束 + 测试 | `75f9798` AccessLogProperties 默认 exclude-paths 增补 `/internal/channels/**`（C-1）+ AccessLogFilterTest notify form 断言无 `sign=`/`PRIVATE KEY`/`app_cert`（C-3）；全局 masker 仍为透传桩，ADR-0027 原文未动 | ✅ |
| AC-8 | 七域逐一 有指标+有告警+有 runbook | runbook §5.1 六域表覆盖 Payment/Channel/Refund/Ledger/Reconciliation/Settlement/MQ（平台域为附加）；§5.4 每域告警处置段落；§4 覆盖映射见本文件 §5 | ✅ |

## 2. 全量回归

- `JAVA_HOME=… ./mvnw -o clean verify -fae`：**[回填] tests，0 失败 / 0 错误 / 0 跳过**（2026-09-22，BUILD SUCCESS，EXIT=0；较 034 基线 930 净增 [回填] 用例：MetricsCardinalityTest + MetricsAssert self-test + AccessLog 4 新用例 + 三域新指标单测 + SettlementBacklogGaugeTest）。
- targeted 先行验证（`0ebe853` 变更集）：common-core 68 全绿（含 uri 归一化 2 新用例）。

## 3. 偏差与加严记录（实现 ≠ spec 字面，均已核对 spec 意图）

1. **trial_balance_break 为 Counter{module,currency} 而非 spec §5.2 的 Gauge{currency,period}**：Gauge 带 period 标签即 HC-2/period 泄漏（每个账期一条时间序列永久驻留）；且 period-close 拒绝已有 `period_close_rejected`。不平事件语义用 Counter 增量 + A-10 `increase(...[10m])` 等价表达。
2. **不新增 `reconciliation_pending` 埋点**：存量 = `sum(reconciliation_difference_total) - sum(reconciliation_difference_resolved_total)`，PromQL 差值即得（A-13 用之）；多一个埋点 = 多一个可漂移的事实源。
3. **看板新行编号 ⑦/⑧ 而非 spec §11 的 ⑥/⑦**：⑥ 已被 034「⑥消息通道」占用，顺延以保持行序单调。
4. **告警终数 24 而非 20**：spec §9 编号止于 A-20（新增 13），但 034 先落地 4 条 + 既有保留合并计数；零删改判据（AC-6）不受影响。
5. **§6.2 纪律 2「uri 归一化」在救援轮补实现**（`0ebe853`）：初版只改了 engineering-standards 条文未改代码，demo 实测日志发现 `/internal/payments/PM…/channel-callback` 原始单号入日志 ⇒ 实现 BEST_MATCHING_PATTERN 归一化 + 回落原始 URI 双用例，而非弱化条文。
6. **HC-3 流程自证**（`0ebe853`）：MetricsCardinalityTest 首跑捕获 032 `AutoDispositionService` 的 `outcome` 标签不在白名单——门禁按设计拦真漂移；处置为「目录登记 → 白名单增长」而非豁免，证明政策闭环有效。
7. **SLO-4 双信号非比率**：spec §17 判据 4 授权（未入账绝对条数 + 最老账龄 Gauge 替代比率 SLI），slo-recording.yml 第四组按此实现。

## 4. 真栈演示证据

- **[回填] demo 容器重建**（ADR-0070：worktree jar → `docker compose up -d --build --no-deps`，含 common-core 全部消费方与 redis-exporter）后五场景 `bash run-all.sh`：happy-path / audit / reconciliation / mq / [回填]，结果 [回填 PASS/FAIL]。
- **[回填] promtool check rules**（payment-prometheus 容器内）：`payment-alerts.yml` + `slo-recording.yml` 两文件语法与表达式校验。
- **[回填] 规则装载与数据路径**：`/api/v1/rules` 可见 slo: 序列；`redis_stream_group_lag`/`redis_stream_group_messages_pending` 非空（T34 `CHECK_STREAMS=mq:stream:*` 修正已于 exporter 重建后实证出序列）；新 Gauge（`settlement_pending_amount`/`limit_inflight_leak`/`ledger_posting_pending`）抓取样本非 NaN。
- ACCESS_LOG 实测：渠道回调路径 uri 记 `{ref}` 模式、`/internal/channels/**` 零日志条目。

## 5. 七域覆盖映射（AC-8）

| 域 | 代表指标 | 告警 | runbook |
|---|---|---|---|
| Payment | payment_timeout / payment_retry_exhausted / unknown_age 桶 | A-04/A-01 组 + UnknownAgedOver24h | §5.4 前段 |
| Channel | channel_request{channelCode,result} / channel_timeout（`3cd6e8e`） | ChannelTimeoutSpike(A-03) | §5.4 |
| Refund | refund_rejected / refund_ledger_posting_failed / refund_unknown_age | RefundFailure/RefundDownstreamFailure/UnknownAgedOver24h | §5.4 |
| Ledger | ledger_posted / ledger_unbalanced / trial_balance_break / balance_rebuild_applied（`3cd6e8e`） | A-07/A-08/A-09/A-10/A-11 | §5.4 |
| Reconciliation | reconciliation_difference(+resolved) / statement_import / statement_unavailable / autodisposition | A-12/A-13/A-14 | §5.4 |
| Settlement | settlement_pending_amount（`3cd6e8e`）/ settlement_failed / gate_rejected | A-15/A-16 | §5.4 |
| MQ | mq_* 计数族 / mq_dlq_size / redis_stream_group_lag（T34 修正后真实导出） | A-17/A-18 | §5.4 |

## 6. 遗留（不阻塞本验收）

- A-15（1e8 minor）与 A-17（lag 100）阈值为 demo 量级占位 `[目标]`，上线前按真实体量校准并记入 slo-report 调优表。
- page 静默期：SLO 燃烧类告警（A-01/A-02）先只出报表不升级处置（H-035-1 条款，slo-report.md 记录）。
- `MetricsAssert` 的运行期遍历断言目前挂在测试基建，尚未有服务在契约测试里逐端点调用——留待后续按域铺开。
