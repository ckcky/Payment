# Acceptance: 034-reliability-hardening

> 回写文档（2026-09-21）。逐条对应 spec §12 测试矩阵（TT-1~TT-12）与恢复三不变式 R-1/R-2/R-3；证据为 `mvnw verify` 全量与真库用例。负责人 2026-09-21 裁决 H-034-1~5 按 spec 推荐方案批准（ADR-0082 Accepted）。

## 1. 测试矩阵（spec §12）

| TT | 验收点 | 落点（提交） | 结果 |
|---|---|---|---|
| TT-1 | C-19 崩溃窗口：TXRF save 后 / order save 前 kill → 重放回调收敛一致（L2b 真库） | `33aa041`（order real-db + FaultHooks） | ✅ |
| TT-2 | 台账唯一键兜底：并发撞 uk 单行（L2b 真库） | `4b4f1b3` | ✅ |
| TT-3 | 重放零改写不双记：REPOSTED 后重投幂等回放（L2b 真库） | `4b4f1b3` | ✅ |
| TT-4 | ABANDONED 人工 replay 复活：retry_count 重置 + 补投成功（L2b 真库） | `4b4f1b3` | ✅ |
| TT-5/TT-6 | RefundUnknownQueryScheduler 75s 窗收敛 + query_exhausted 一次性（payment 真库） | `5ce99b0` | ✅ |
| TT-7 | StrandedRefundOrderScanner ≤2 次红线 + 耗尽 FINANCIAL_AUDIT + R-3 不判成败（order L1） | `82dd681` | ✅ |
| TT-8 | reconciliation 调账记账真库回归：失败留痕/唯一键吸收/补投零双记（L2b） | `04233f3` | ✅ |
| TT-9 | late success：CLOSED 上 SUCCESS → `late=true` 不覆盖终态（payment L1） | `1a33953` | ✅ |
| TT-10 | DLQ 读/replay/清空 + `mq_dlq_size`（embedded-redis L2） | `ae47864` | ✅ |
| TT-11 | settlement 同 batchNo 重放不重复发记账事件 | `4ce4d5b` | ✅ |
| TT-12 | 全量回归：ServiceBoundary / RpcEdgeAllowList / schema-lint / schema-replay 零回归 | 本文件 §2 | ✅ |

## 2. 全量回归

- `JAVA_HOME=… ./mvnw -o clean verify -fae`：**930 tests，0 失败 / 0 错误 / 0 跳过**（2026-09-21，15 模块，BUILD SUCCESS，EXIT=0；较 033 基线 915 净增 15 用例）。
- schema 双路径重放与 lint 随 `68d430c` DDL 批次验证通过（591 结构事实全等）。

## 3. 关键行为证据

- **成功路径零改动**：四 gateway 包装仅在失败分支落台账（`e9ceedb`/`d728226`），audit 上抛语义保留（上抛前落痕）。
- **重试政策**：10s 扫描、1s/5s/30s/2m/10m 五档退避、retry_count=6 → ABANDONED；`POST /internal/postings/{id}/replay` admin token 守卫。
- **移除 Resilience4j**（`bbac72a`）：全仓 grep 零引用；yml 留 WHY 注释指向台账/扫描替代语义。
- **可观测三件**：诊断①调度入口 `runWithNewTrace`（10+ scheduler 全覆盖，grep 实证）；诊断②毒丸隔离一次性 warn；诊断③`DemoProxyController` GET 代理日志降级（`de408e8`）。
- **告警规则**：`deployment/prometheus/rules/payment-alerts.yml` A-05/A-06/A-08/A-09。

## 4. 文档同步

- 超时三档政策表进 `technical-solution.md` §5.1.1（T25，含 Resilience4j 口径修正）；`application.yml` 相应项 WHY 注释核对。
- L0 系统文档四份增补（T27）：payment-service §10 / order-service §9 / settlement-service §7 / reconciliation-service §8。
- CHANGELOG 头条 `feat(034)`、specs/README 034 → 🟢、roadmap 034 已实现条目 + 待裁决 15→10（T29）；ADR-0082 🟢 已随 `f2f94d0` 翻转。

## 5. 遗留（不阻塞本验收）

- UNKNOWN 队列视图与分桶 Counter 的 SLO/面板口径归 035（034 交付指标与规则文件本身）。
- pending_postings 的运维手册（runbook）视角并入 035 告警联动章节统一收口。
