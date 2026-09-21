# Tasks: 035-observability-slo

> 状态图例：✅ 已完成并验证 · 🟢 进行中 · ⬜ 待做。按 Phase 提交（脏文件 ≤15 必 commit）。

## Phase 0 — 规划

- [x] ✅ T01 通读 spec + 现状证据盘点（§1 增量核对：11 条告警、缺失指标清单）
- [x] ✅ T02 plan.md（含 H-035-1~4 批量裁决记录）+ tasks.md

## Phase 1 — 035-A 高基数政策 + 机器断言（G2）

- [ ] ⬜ T11 `engineering-standards.md` §7 增补 HC-1~HC-4 条文 + trace/日志关联九 ID 政策（G6 §6.1/§6.2 两纪律）+ 密钥禁令条文（§10 C-3 之一部分，与 T21 同文件收口）
- [ ] ⬜ T12 `architecture-tests`：`MetricsCardinalityTest` —— 全仓 `metrics.counter/timer/gauge(` 调用扫描：标签键 ∈ 有界白名单；值实参不得为 `*No`/`*Id` 变量；`period` 仅 Gauge（Counter 违规 = 存量基线棘轮，逐处登记）
- [ ] ⬜ T13 `test-infra/MetricsAssert`：`assertTagValuesWithinAllowedSet(registry, allowed)` registry 遍历断言（§7.1 机制，L2a 可复用）+ self-test

## Phase 2 — 035-B 密钥/完整报文不入日志（G5）

- [ ] ⬜ T21 `AccessLogProperties` 默认 `exclude-paths` 增补 `/internal/channels/**`（C-1）；javadoc/README 同步
- [ ] ⬜ T22 `AccessLogFilterTest` 新增：notify form（含 `sign=`/`out_trade_no`）不落正文；`/actuator/**` 行为回归；断言捕获日志无 `sign=` 片段（C-3 测试）

## Phase 3 — 指标补齐（G1/G4 前置，M-3）

- [ ] ⬜ T31 ledger-service：`ledger.unbalanced{currency}`（PostingEngine 构造期拒绝计数）、`trial.balance.break{currency}`（关账试算不平次数）、`balance.rebuild.applied`（rebuild 端点计数）+ 单测断言（PostingBalanceGate/PeriodClose 路径）
- [ ] ⬜ T32 payment-service：`channel_request{channelCode,result}` / `channel_timeout{channelCode}`（ChannelQueryService 出站查询处）+ 单测；`limit_inflight_leak{window}` Gauge（LimitCompensationScanner 残留候选数）+ 单测
- [ ] ⬜ T33 settlement-service：`settlement_pending_amount{state}` Gauge（批状态分组净额绝对值，InMemory/真库两仓储的 `listBatches` 复用）+ 单测
- [ ] ⬜ T34 compose：`REDIS_EXPORTER_CHECK_STREAMS/GROUPS` 由 `payment:*` 修正为 `mq:stream:*`（现状 pattern 与 `mq:stream:{topic}` 键名不匹配 ⇒ PEL 面板/告警空采），容器重建实测 exporter 出 `redis_stream*_pending` 序列并回填 A-17 表达式与面板⑰

## Phase 4 — 035-C SLO Recording Rules + 错误预算（G3）

- [ ] ⬜ T41 `deployment/prometheus/rules/slo-recording.yml`：4 项 SLI + availability_ratio + error_budget_remaining + burn_rate fast(1h)/slow(6h)；SLO-4 双信号表达式；prometheus.yml `rule_files` 挂载
- [ ] ⬜ T42 `docs/operations/slo-report.md` 新建（首次运行前 = 无数据）；technical-solution §5.3 SLO 表（`[目标]` 标注，§8.3 分离判据）
- [ ] ⬜ T43 `promtool check rules` 两文件（payment-prometheus 容器内）

## Phase 5 — 035-D/E 告警扩容（G4）

- [ ] ⬜ T51 `payment-alerts.yml` 新增 13 条（A-01/02/03/07/10/11/13/14/15/16/17/19/20），每条五要素（expr/labels.severity/annotations.summary+description+runbook_url+dashboard/labels.owner+domain）
- [ ] ⬜ T52 既有 11 条补 `runbook_url`/`dashboard`/`owner`（零删除零改名，AC-6）
- [ ] ⬜ T53 promtool 复验 + runbook §5.3 逐条五要素段落（M-4，20 项覆盖映射表）

## Phase 6 — 035-G Grafana（G7）

- [ ] ⬜ T61 `payment-arch.json` 新增 row「⑥ SLO 与错误预算」（SLI 曲线 + 剩余预算 + burn rate，target 以 threshold 线画）与「⑦ 资金健康」（六积压 Gauge 一屏）；JSON 语法 + panels 结构校验
- [ ] ⬜ T62 demo 栈重建后看板数据路径抽查（PromQL 序列非空验证，尽力而为）

## Phase 7 — 文档收口

- [ ] ⬜ T71 runbook §5 升级为**指标目录唯一登记处**（G1：四列齐全 + 值域 + 告警映射，031/032/034 新增项收口）；technical-solution §5.3 指针化
- [ ] ⬜ T72 ADR-0083 🟡→🟢 Accepted + adr/README 索引/速查/水位行同步
- [ ] ⬜ T73 acceptance.md（§18 AC-1~8 逐条 + 全量回归 + demo 五场景 + promtool/Grafana 校验）
- [ ] ⬜ T74 specs README 035→🟢、roadmap stage-05 状态行（031~035 全部已实现、27 项裁决批准收口）、CHANGELOG `feat(035)`
- [ ] ⬜ T75 全量 `./mvnw -o clean verify -fae` ≥968 全绿 + demo 五场景 PASS

## Phase 8 — 收口

- [ ] ⬜ T81 push + PR（不 merge）+ 完成报告
