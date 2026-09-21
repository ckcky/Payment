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

- `JAVA_HOME=… ./mvnw -o clean verify -fae`：**981 tests，0 失败 / 0 错误 / 0 跳过**（2026-09-22，18 模块，BUILD SUCCESS，EXIT=0；较 032/034 基线 930 净增 51 用例：MetricsCardinalityTest + MetricsAssert self-test + AccessLog 新用例 + 三域新指标单测 + SettlementBacklogGaugeTest）。
- targeted 先行验证（`0ebe853` 变更集）：16 模块链 970 tests 全绿（含 uri 归一化 2 新用例，payment-service 真 Spring MVC 上下文中实测日志 `uri=/internal/payments/{paymentNo}/channel-callback`）。

## 3. 偏差与加严记录（实现 ≠ spec 字面，均已核对 spec 意图）

1. **trial_balance_break 为 Counter{module,currency} 而非 spec §5.2 的 Gauge{currency,period}**：Gauge 带 period 标签即 HC-2/period 泄漏（每个账期一条时间序列永久驻留）；且 period-close 拒绝已有 `period_close_rejected`。不平事件语义用 Counter 增量 + A-10 `increase(...[10m])` 等价表达。
2. **不新增 `reconciliation_pending` 埋点**：存量 = `sum(reconciliation_difference_total) - sum(reconciliation_difference_resolved_total)`，PromQL 差值即得（A-13 用之）；多一个埋点 = 多一个可漂移的事实源。
3. **看板新行编号 ⑦/⑧ 而非 spec §11 的 ⑥/⑦**：⑥ 已被 034「⑥消息通道」占用，顺延以保持行序单调。
4. **告警终数 24 而非 20**：spec §9 编号止于 A-20（新增 13），但 034 先落地 4 条 + 既有保留合并计数；零删改判据（AC-6）不受影响。
5. **§6.2 纪律 2「uri 归一化」在救援轮补实现**（`0ebe853`）：初版只改了 engineering-standards 条文未改代码，demo 实测日志发现 `/internal/payments/PM…/channel-callback` 原始单号入日志 ⇒ 实现 BEST_MATCHING_PATTERN 归一化 + 回落原始 URI 双用例，而非弱化条文。
6. **HC-3 流程自证**（`0ebe853`）：MetricsCardinalityTest 首跑捕获 032 `AutoDispositionService` 的 `outcome` 标签不在白名单——门禁按设计拦真漂移；处置为「目录登记 → 白名单增长」而非豁免，证明政策闭环有效。
7. **SLO-4 双信号非比率**：spec §17 判据 4 授权（未入账绝对条数 + 最老账龄 Gauge 替代比率 SLI），slo-recording.yml 第四组按此实现。
8. **demo 套件级修复（035 收口轮实测暴露，先于本 Feature 存在）**：
   ① `run-all.sh` 顺序改为审计紧跟主链（happy → audit → mq → recon → routing → refund → unknown）——CERTIFICATE 核对读 `ledgerPostings()` 不分期间，退款类场景先跑会把当日的 PM/PMRF 分录做成 ORPHAN_POSTING 差异挂进审计批、关批被拒（CI e2e 一直是 audit 排第二的序，run-all 未同步）；
   ② `lib.sh` 增 `python`→`python3` 兼容垫片并 `export -f`（多个场景脚本用裸 `python -c`，macOS 无 `python`；`bash -c` 子壳同需）；
   ③ `scenario-refund.sh` 订单侧终态核对改轮询（034 C-19 后退款收口与订单通知分事务，单次读取把最终一致做成竞态）；
   ④ `restart-payment.sh` 健康 200 后增 Nacos 实例注册等待（容器重建窗口内 order 侧 Feign 报 No servers available）。

## 4. 真栈演示证据（2026-09-22，容器模式重建自本分支 jar）

- `bash deployment/demo/run-all.sh`：**七场景全 PASS，EXIT=0**（主链 / 审计闭环 / 消息通道 / 每日对账 / 渠道路由 S1~S6 / 退款 / UNKNOWN 收敛；含 CI nightly 五场景子集）。过程中发现并修复三处**先于 035 存在**的套件级时序/环境问题（详见 §3.8）。
- `promtool check rules`（payment-prometheus 容器内）：`payment-alerts.yml` **SUCCESS: 24 rules**；`slo-recording.yml` **SUCCESS: 21 rules**。`/api/v1/rules` 实载核对一致（4 个 slo 组 + business 组 24 条）。
- 数据路径抽查（Prometheus 实时序列数）：`settlement_pending_amount`=3、`limit_inflight_leak`=1、`ledger_posting_pending`=3、`redis_stream_group_lag`=18（T34 修正后真实导出）、`slo:burn_rate_fast`=3、`slo:error_budget_remaining`=6。
- ACCESS_LOG 真栈实证：order-service demo 期间 `uri=/orders/{ref}/payments` 模式化、原始单号形态 `/orders/OR…` 计数为 0；payment 真 Spring MVC 测试上下文 `uri=/internal/payments/{paymentNo}/channel-callback`；`GET /internal/channels`（200）与 alipay notify form（含 `sign=` 探测载荷）后 ACCESS_LOG 增量为 0、密钥片段零泄漏（G5/C-1/C-3）。
- `channel_request/channel_timeout` 为惰性注册计数器：demo UNKNOWN 窗口（034 毒丸守卫路径，无落库 attempt 的单不经渠道查询）未触发出站调用 ⇒ 实时零序列属预期；埋点接线由 T32 单测证明，首笔真实主动查询即出序列（M-3 判据 = 指标先于告警存在于**代码**，目录已注明注册语义）。

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
