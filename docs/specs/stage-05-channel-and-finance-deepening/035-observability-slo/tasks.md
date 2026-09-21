# Tasks: 035-observability-slo

> 状态图例：✅ 已完成并验证 · 🟢 进行中 · ⬜ 待做。按 Phase 提交（脏文件 ≤15 必 commit）。
> 注：负责人已裁决「跑完自动合并」，T81 的「不 merge」按该指令更新为测试全绿后自动合并。

## Phase 0 — 规划

- [x] ✅ T01 通读 spec + 现状证据盘点（§1 增量核对：11 条告警、缺失指标清单）
- [x] ✅ T02 plan.md（含 H-035-1~4 批量裁决记录）+ tasks.md

## Phase 1 — 035-A 高基数政策 + 机器断言（G2）

- [x] ✅ T11 `engineering-standards.md` §7 增补 HC-1~HC-4 条文 + trace/日志关联九 ID 政策（G6 §6.1/§6.2 两纪律）+ 密钥禁令条文（§10 C-3 之一部分）（`c7ac302`）
- [x] ✅ T12 `MetricsCardinalityTest` 静态扫描门禁：键白名单 / `*No`/`*Id` 值禁令 / period 棘轮基线 13 / 阳性对照防空转（`c7ac302`；首跑捕获 032 `outcome` 漂移 → 登记后白名单增长，HC-3 闭环自证，`0ebe853`）
- [x] ✅ T13 `MetricsAssert.assertTagValuesWithinAllowedSet` registry 遍历断言 + 正负 self-test（`c7ac302`）

## Phase 2 — 035-B 密钥/完整报文不入日志（G5）

- [x] ✅ T21 `AccessLogProperties` 默认 `exclude-paths` 增补 `/internal/channels/**`（C-1）+ javadoc 同步（`75f9798`）
- [x] ✅ T22 `AccessLogFilterTest`：notify form（`sign=`/`PRIVATE KEY`/`app_cert` 不落日志）+ 非排除路径无密钥基线（C-3）（`75f9798`）；另落地 §6.2 纪律 2 的 uri 归一化（BEST_MATCHING_PATTERN，实测救援轮发现代码未跟上条文，`0ebe853` + 双用例）

## Phase 3 — 指标补齐（G1/G4 前置，M-3）

- [x] ✅ T31 ledger：`ledger_unbalanced` / `trial_balance_break` / `period_close_rejected` / `balance_rebuild_applied`（Counter 语义，偏差记录 acceptance §3.1）+ 单测（`3cd6e8e`）
- [x] ✅ T32 payment：`channel_request{channelCode,result}` / `channel_timeout`（ChannelQueryService 出站）+ `limit_inflight_leak` Gauge + 单测（`3cd6e8e`）
- [x] ✅ T33 settlement：`settlement_pending_amount{state}` 净额绝对值 Gauge + 单测 `SettlementBacklogGaugeTest`（三态/终态排除/坏仓储降级 NaN）（`3cd6e8e` + `0ebe853`）
- [x] ✅ T34 compose `REDIS_EXPORTER_CHECK_STREAMS/GROUPS` → `mq:stream:*`；exporter 重建后 `redis_stream_group_lag`/`messages_pending` 序列实测非空（`ea677aa`，A-17 与面板表达式同步回填）

## Phase 4 — 035-C SLO Recording Rules + 错误预算（G3）

- [x] ✅ T41 `slo-recording.yml` 4 组 SLI + error_budget_remaining + burn fast(1h)/slow(6h)；SLO-4 双信号非比率（§17 判据 4）；prometheus.yml `rule_files` 挂载（`9f6e843`）
- [x] ✅ T42 `docs/operations/slo-report.md` 新建（目标 `[目标]` 标注 + 调优追加表 + 月度模板）；technical-solution §5.3 指针化（`7836285`）
- [ ] 🟢 T43 `promtool check rules` 两文件（payment-prometheus 容器内）——待 demo 重建后执行

## Phase 5 — 035-D/E 告警扩容（G4）

- [x] ✅ T51 新增 13 条（A-01/02/03/07/10/11/13/14/15/16/17/19/20）五要素齐全（`ea677aa`）
- [x] ✅ T52 既有各条补 `runbook_url`/`dashboard`/`owner`，零删除零改名（AC-6 对照 master 逐名核验）（`ea677aa`）
- [ ] 🟢 T53 runbook §5.4 二十四段处置（症状→第一步→判定→处置→升级，M-4）已落（`7836285`，编号 §5.3→§5.4 因染色段落顺延）；promtool 复验随 T43

## Phase 6 — 035-G Grafana（G7）

- [x] ✅ T61 `payment-arch.json` 新增「⑦ SLO 与错误预算」（6 面板，目标=threshold 线）与「⑧ 资金健康」（10 面板值班首屏）；61 panels、id 唯一、JSON 校验通过（`7836285`；⑥/⑦ 编号顺延偏差见 acceptance §3.3）
- [ ] ⬜ T62 demo 栈重建后看板数据路径抽查（`/api/v1/rules` 装载 + 新 Gauge 序列非空，尽力而为）

## Phase 7 — 文档收口

- [x] ✅ T71 runbook §5 重写为指标目录唯一登记处（六域分表 + 值域 + 告警映射 + Owner + 黑名单）；technical-solution §5.3 指针化（`7836285`）
- [x] ✅ T72 ADR-0083 🟢 Accepted + adr/README 索引/速查/水位 + traceability stage-05 五行落点收口（`72cc867`）
- [ ] 🟢 T73 acceptance.md 框架已落（AC-1~8 + 偏差 §3 七条 + 七域映射 §5），全量回归与 demo 数值待回填
- [ ] 🟢 T74 specs README 035→🟢 + 状态说明、roadmap stage-05 状态行 + 035 已实现条目（27 项裁决全收口）已改未提交；CHANGELOG `feat(035)` 待全量数回填
- [ ] ⬜ T75 全量 `./mvnw -o clean verify -fae` ≥968 全绿 + demo 五场景 PASS

## Phase 8 — 收口

- [ ] ⬜ T81 push + PR + **自动合并**（负责人指令「跑完自动合并」覆盖原「不 merge」）+ 完成报告
