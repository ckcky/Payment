<a id="adr-0083"></a>

# ADR-0083：观测基线——SLO/错误预算落地、高基数政策、密钥与报文不入日志、保留自研 trace（Feature 035）

> 承载 [spec 035-observability-slo](../specs/stage-05-channel-and-finance-deepening/035-observability-slo/spec.md) 的四条决策。
> 状态：**🟢 Accepted（2026-09-21 负责人批量裁决，H-035-1~4 按 spec 推荐方案批准；随 spec 035 实现落地）**
> ——SLO 目标值属 Constitution §Governance「非功能目标」人类决策（design-review §13 **H14**），
> **实测数据积累满一周期前告警只出报表、不 page**（H-035-1 静默期口径，见 docs/operations/slo-report.md）。

---

## Context

1. **指标很多、告警很少、SLO 为零**：93 个业务指标，`payment-alerts.yml` 只有 7 条规则，且
   **账务 / 结算 / MQ 三个资金相关域零告警**——`ledger.posting_failed` 有指标没规则（`FeignLedgerPostingGateway.java:53` vs 规则文件），
   DLQ 连指标都没有；`rule_files` 只挂一个告警文件（`prometheus.yml:10-11`），无 recording rule；
   Constitution §Obs.5 定义的 SLO 目标从未变成可计算的东西（design-review M8）。
2. **基数纪律是「碰巧做对了」**：93 个指标的标签键只有 16 种低基数维度（`module` 84 次、`bucket`/`period` 各 3 次…），
   **但没有任何条文或检查守住**——下一个人给 counter 加 `paymentNo` 标签不会被拦（design-review 无此项，本轮新发现）。
   另有两处小缺陷：`period` 出现在 Counter 上属慢性基数泄漏；命名风格双轨
   （`payment.query_exhausted` 与已直接写 Prometheus 名的 `dye_tag_rejected_total`，`DyeFilter.java:50`）。
3. **密钥/报文纪律今天是反向的**：`ACCESS_LOG` 用 `ContentCachingRequestWrapper` 记录完整 req/resp 正文
   （`AccessLogFilter.java:74-76,100-101`），脱敏是**故意的透传桩**（`PassThroughBodyMasker`，ADR-0027 / spec 021 D3 裁决），
   排除路径默认**只有 `/actuator/**`**（`AccessLogProperties.java:20`）⇒
   **`/internal/channels/alipay/notify` 的 form 报文（含 `sign`、`out_trade_no`、`total_amount`）今天整段进日志**。
   design-review H7 要求的正是「显式约束 + 测试」，不是推翻 ADR-0027。
4. **Saturation 在本系统有非标准含义**：CPU/连接池已有面板，但资金系统的故障形态是「事情堆着没人做」——
   待记账行数、未收口差异数、DLQ 长度、悬置金额这类**积压 Gauge** 目前几乎不存在。

---

## Decision

### 决策 1：SLO 以 Recording Rule 表达，**目标值与实测值在文档与看板上强制分离**

四个 SLO（同步查询 P99 ≤ 500ms / 同步命令 P99 ≤ 1s / 资金入口可用性 ≥ 99.9% / **账务完整性 ≥ 99%**）
落成 `slo-recording.yml`：SLI、剩余错误预算、**双窗多速率 burn rate**（fast 1h / slow 6h），
告警只在双窗同时越阈时 page（避免单窗抖动误报）。
SLO-2 排除 `/internal/channels/**`（渠道回调时延由对端决定，我方无法承诺）；
SLO-3 不含渠道不可用（外部依赖另立渠道指标）；
**SLO-4 不用比率型 SLI**——跨服务分母不可信，改用「未入账绝对条数 + 最老账龄」两个 Gauge 表达，
宁可少精确也不伪造精确。
实测值只出现在 `docs/operations/slo-report.md`（首次运行前内容 = 「无数据」）；
**任何文档 MUST NOT 出现「SLO 已达成」，除非该文件有对应时段的真实数据**。

### 决策 2：高基数政策成文 + 可机器检查

四条条文（HC-1 标签值域发布前可穷举；HC-2 **业务单号 / 用户与商户 ID / 时间戳 / UUID 一律禁入 label**；
HC-3 新 label 必须在指标目录登记值域；HC-4 单指标序列预算 200）+ 一个可执行断言：
遍历 `MeterRegistry` 已注册 meters，断言每个 tag value 属于登记的允许集合。
`merchantId` 明确判为**禁入 label**（按商户分析走 SQL/Loki）；
`period` 开一条**例外条款**：只允许出现在 Gauge（覆盖写、单序列），Counter 上禁用。
既有 93 个指标**零改名、零删除**（新指标按新规范）——政策的价值在守增量，不在追历史。

### 决策 3：密钥与完整报文不入日志 = **路径排除 + 显式禁令 + 测试**，不启用通用脱敏

- `access-log.exclude-paths` 增补 `/internal/channels/**`（渠道回调入口不落正文）；
- 字段级 mask 若将来需要，走**已预留的 Bean 覆盖点**（`@ConditionalOnMissingBean`）由单个服务实现，
  **MUST NOT 改 common-core 默认透传实现**（那会推翻 ADR-0027 的负责人裁决）；
- 成文禁令：`sign` / `privateKey` / `token` / `app_cert` / 完整渠道报文 MUST NOT 出现在日志、指标、审计字段；
- 配套测试：notify 端点与渠道出站用例 MUST 断言捕获日志不含 `sign=` 与私钥片段（design-review H7 的「显式约束 + 测试」原样落地）。

**代价被显式接受**：排除后拿不到原始 form 取证；补偿 = 渠道方提供报文 / 沙箱可重发 + 保留
`traceId`/`bizNo`（由 `out_trade_no` 提供）/`status`/`costMs` 与控制器既有处理日志。

### 决策 4：**保留自研 traceId，明确不引入 APM / Micrometer Tracing**

`X-Trace-Id` + `TraceContext` + MDC 三键（`traceId`/`bizNo`/`dyeMode`）沿用。理由：
① 本系统排障主键是**业务单号**，不是 trace（`Invariants.java`、`trace-grep.sh` 都以 `orderNo`/`paymentNo` 为中心）；
② 跨服务链路只有 2~3 跳，span 树收益低；
③ 资金链路**一个都不能采样丢**——「上了 APM 也不敢用采样」；
④ 引入 Collector/Tempo/OTel 违反 P1「不加中间件为默认」与 brief §12 禁止清单。
只补两条零成本纪律：异步/MQ 边界 MUST 恢复 MDC；MUST NOT 引入 `traceparent` 与 `X-Trace-Id` 双权威。

### 决策 5：告警扩容到 20 条，但**分批上线且不得早于其依赖的指标存在**

13 条新增里 8 条直接补今天「有指标无告警」的资金缺口（A-07 `LedgerPostingFailure` critical 最优先）。
每条 MUST 五要素齐全（Trigger / Severity / Meaning（业务后果）/ First Investigation Path / Owner）+ 同 PR 补 runbook 段落，
缺一不算交付；既有 7 条全部保留，只补 `runbook_url` 与 Owner。
新增约 18 个指标（积压 Gauge 为主）——**每个必须能说出告警意义，说不出就不进目录**。

---

## 备选方案

- **A. 引入 Micrometer Tracing + OTel bridge**：获得 span 树与采样，但新增运行时依赖 + 新后端运维，
  且资金链路不能用采样 ⇒ **否决**（决策 4）。
- **B. 启用通用报文脱敏**：一次性解决 H7 与「密钥不入日志」，但推翻 ADR-0027 的负责人裁决，且 JSON 字段级 mask
  在跨 9 服务上的正确性需要独立设计评审 ⇒ **本轮否决**，留 C-2 定向覆盖点。
- **C. 只做告警不做 SLO**：短期省事，但 Constitution §Obs.5 的目标继续只是文字，
  且 031/032/034 的新事实没有「是否变好」的度量口径 ⇒ 否决。
- **D. 采纳**：Recording Rule + 基数政策 + 路径排除 + 保留自研 trace + 分批告警。

---

## Consequences

**正面**
- 值班首屏变成一屏可判定的「六个积压 Gauge 全 0 才算健康」（§看板⑦），而非 93 个指标的海洋；
- 账务失败、试算不平、DLQ 增长、账单来源退化——四类**静默资金风险**第一次会主动通知人；
- 基数与密钥从「口头纪律」变成有条文、有测试、有门禁的对象；
- 031/032/034 的每个新事实有登记处（指标目录 = 跨 Feature 的观测接口）。

**代价 / 风险**
- 7 → 20 条告警的噪声风险：靠双窗判据 + info 级只开工单 + 分批上线控制；
- 阈值全部是 `[目标]` 无实测支撑：故未裁决前 page 静默、只出报表（H-035-1）；
- 排除 notify 正文换走一份取证能力（显式接受）；
- 新指标若与 031/032/034 实现脱节会出现「告警引用不存在的指标」：M-3 顺序禁令 + 可选 CI 校验（规则文件指标名须出现在抓取样本）。

---

## 待人类裁决（详见 spec 035 §16）

| # | 决策项 | 边界类型 | 推荐 |
|---|---|---|---|
| H-035-1 | 4 项 SLO 目标值 + 30 天窗口 + 双窗 burn rate（= design-review §13 **H14**） | 非功能目标 | 采纳表值；未实测前只出报表 |
| H-035-2 | notify 端点正文不落日志（路径排除）vs 启用字段级脱敏（会推翻 ADR-0027） | 安全口径 / 排障能力 | **接受排除** |
| H-035-3 | 观测基线（自研 trace + 无 APM + 高基数政策）是否正式立 ADR 收口 | 架构取舍 | 是（即本 ADR） |
| H-035-4 | 试算不平是否连带**自动冻结结算**（031 §10 已有 fail-closed 门禁） | 资金路径行为 | 不额外自动化 |
