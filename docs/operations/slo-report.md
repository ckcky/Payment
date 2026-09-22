# SLO 报告与阈值调优记录（spec 035 / §14 Runbook Boundary）

> **用途**：告警阈值调优历史的唯一记录处（spec 035 §14）。SLO **目标值**是裁决产物（H-035-1，
> 见 [ADR-0083](../adr/0083-observability-baseline-slo-and-cardinality.md)），**不随实测漂移**；本文件记录的是
> 实测表现、错误预算消耗与**告警阈值**（非目标值）的调整。
>
> **口径纪律（§8.3 防虚假合规）**：目标值一律标 `[目标]`；本文件任何「达成」结论必须附
> Prometheus 实测查询与其窗口，无数据就写「无数据」，禁止用 100% 占位。
> （目标值裁决见 [ADR-0083](../adr/0083-observability-baseline-slo-and-cardinality.md)。）

## 1. 四项 SLO（目标值 = H-035-1 批准口径）

| # | SLO | 目标 | 窗口 | SLI / Recording Rule | 告警 |
|---|---|---|---|---|---|
| SLO-1 | 同步查询延迟 P99（`/internal/**` GET） | ≤ 500 ms `[目标]` | 30 天 | `slo:query_latency_p99_seconds` + `slo:query_latency_error_ratio{window}` | A-02 |
| SLO-2 | 同步命令延迟 P99（排除 `/internal/channels/**`） | ≤ 1 s `[目标]` | 30 天 | `slo:command_latency_p99_*` | A-02 |
| SLO-3 | 资金入口可用性（order/payment POST，5xx=错误） | ≥ 99.9 % `[目标]` | 30 天 | `slo:availability_ratio{group="payment_entry"}` | A-01 |
| SLO-4 | 账务完整性（**非比率型**：未入账绝对条数 + 1h 持续窗） | ≥ 99 % 语义 ⇒ **PENDING 存量=0 且无 ABANDONED** `[目标]` | 滚动 | `slo:posting_integrity_unposted_facts` / `_unposted_1h_min` | A-07/A-08/A-09 |

burn rate 双窗判据：fast(1h) > 14.4 **且** slow(6h) > 6 才 page（Google SRE 多窗多速率，
单窗越阈只记报表）。demo 栈数据量级下该组合几乎不可能自然触发——静默属预期，不解读为健康证明。

## 2. 首次运行基线（2026-09-22，demo 栈）

- 状态：**首次挂载 Recording Rule，历史数据不足 30 天窗口 ⇒ 全部「无数据/积累中」**。
- demo 实测采集见验收记录（acceptance.md §demo）：规则加载无报错（promtool + Prometheus
  `/api/v1/rules` 计数）、面板⑦/⑧ 数据路径非空抽查。
- 结论：**page 静默期维持**（H-035-1：未实测前只出报表），进入生产观测满一周期后启用。

## 3. 阈值调优记录（追加式，勿删历史）

| 日期 | 告警/阈值 | 旧值 → 新值 | 依据（误报/漏报样本） | 决策人 |
|---|---|---|---|---|
| 2026-09-22 | （初始登记，无调优） | — | A-15 净额阈值 100 万元、A-17 lag 阈值 100 为 demo 量级占位 `[目标]`，上线前按真实体量校准 | 随 035 验收 |

## 4. 月度报表模板

```
月份：            数据窗口起止：
SLO-1 达成：  P99 实测曲线 max/均值 ｜ 预算消耗 % ｜ 越阈事件数
SLO-2 达成：  同上
SLO-3 达成：  可用性实测 % ｜ 预算剩余 % ｜ burn 越阈（fast/slow 分开计）
SLO-4 达成：  未入账条数峰值 ｜ ≥1h 存量时长 ｜ ABANDONED 新增数
告警事件：    critical/warning/info 各计数 ｜ 误报标记（进 §3 调优）
```
