<a id="adr-0055"></a>

# ADR-0065: 会计四核对与挂账·调账闭环——对账升级为完整会计闭环（spec 017 立项）

- 状态：✅ **Accepted**（2026-09-06 负责人拍板全部 6 个决策点；**本 ADR 为文档立项，代码尚未实施**，实施自 spec 017 tasks 批次 C 起）
- 关联：ADR-0019~0021（对账）、ADR-0023（结算批 / 门禁现状）、ADR-0054（支付编排职责归位 / N4 退款流水号落库）、spec 004（SC-005 账证核对声明）、spec 006（结算）、spec 017（[spec](../specs/017-accounting-audit/spec.md) / [plan](../specs/017-accounting-audit/plan.md)）、Constitution §8（人类决策边界：SUSPENSE 科目与门禁策略均已获负责人裁决）
- 需求源头：负责人「账账核对，账证核对，账实核对，还有个账什么核对来着。你看看我们项目需要做哪些怎么搞法」+「加上测试和验收，要有模拟数据，最好在 demo 搞个界面能看到对账任务的触发执行、结果、挂账调账这些」+ 2026-09-06 对 6 个决策点的逐条拍板（见 §决策）。

## 背景

现有对账（spec 006 / ADR-0019~0021）只覆盖「业务台账 ↔ 渠道账单」单向比对，且对差异**只记录、不处置**（`resolveDifference` 仅写 `resolution_note`，账上差额原地不动）——差异可以"嘴上处理完"而资金缺口无人兜底。经逐项核实（spec 017 §G1~G7），七类缺口：

| # | 缺口 | 核实证据 |
|---|---|---|
| G1 | **账证核对从未实现** | spec 004 SC-005 声明「覆盖率由 reconciliation 校验」，但 `reconciliation-service` 源码对 ledger **零引用** |
| G2 | 科目勾稽缺失 | 无人验证「按业务口径推导的科目应计」== 「账本实计」 |
| G3 | 跨账勾稽缺失 | ledger 与 settlement 之间无一致性校验 |
| G4 | 账实核对只有单轨 | 仅「业务台账 ↔ 渠道」，**账本被跳过**——账本分录错账不会被渠道比对发现 |
| G5 | 账表核对缺失 | 无任何报表口径校验 |
| G6 | 差异无处置闭环 | 只写 note，无挂账 / 调账动作，账实差额永远悬空 |
| G7 | 差异不阻塞资金出口 | 结算创建不看对账差异（仅看关账），未收口差异期间仍可能错付商户 |

## 决策（负责人 2026-09-06 逐条拍板）

1. **落位：扩展 `reconciliation-service`，新增 `audit` 包**（不新建服务）。复用既有批次 / 差异骨架，新增 `audit_*` 表族（批次 / 差异 / 调账分录引用），**只写 audit_* 表与 ledger 的 `ADJUSTMENT` posting，绝不 UPDATE/DELETE 既有分录、绝不改 payment/refund/settlement 业务状态**。

2. **账实核对升级双轨**：保留「业务台账 ↔ 渠道账单」，**新增「账本资金科目发生额 ↔ 渠道账单」**第二轨；任一轨有差异即本期账实不符。退款方向按**真实渠道退款流水号**匹配（见 §4）。

3. **结算门禁分级**（修正原「一刀切拦截」设想，负责人明确质疑「一笔异常为什么报废整期结算」——门禁**不报废任何批次**，拦截的是结算批的**创建**）：
   | 差异状态 | 结算批创建 | 理由 |
   |---|---|---|
   | `BLOCKER` 且未挂账未调账（PENDING） | ❌ 拦截 | 钱对不上且未隔离，存在错付商户风险 |
   | 已挂账（SUSPENDED）/ 已调账（ADJUSTED） | ✅ 放行（结算单标记「本期含未收口挂账 ¥X」，留痕） | 差额已隔离在 SUSPENSE 过渡科目，不会错付 |
   | 借贷不平衡（试算平衡 ≠ 0） | ❌ 硬拦 | 账本结构性损坏，任何出口都必须停 |

4. **N4（退款渠道流水号落库）已在 spec 016 修复**：`payment_attempts(attempt_type=REFUND, channel_reference)` 已落库 + 唯一约束幂等，`RefundFactsService` 取真实号。**负责人裁决：迁移前存量数据不处理**（沿用 `"refund-{id}"` 合成引用兜底 + WARN），不单列开发任务；spec 017 的 P0 仅复核确认。

5. **新增 `SUSPENSE(id=5)` 过渡科目**（Constitution §8 变更，**负责人批准**）：「待处理差错款」，ASSET 型。挂账语义——账少记 → 借 `CUSTOMER_CASH` / 贷 `SUSPENSE`；账多记 → 反向。差额不进损益、不进应付商户；**`SUSPENSE` 余额 ≡ Σ 未收口差异挂账净额**（勾稽恒等式，本身就是可监控指标）。改动面 = `Account` 枚举 + 一行幂等 seed。

6. **双人复核降级为软提示**（单人项目）：缺 reviewer / `operator == reviewer` / `WRITE_OFF` / 超阈值（¥100）调账**只 WARN 留痕、不阻断**；`operator + reason` 必填保持硬约束。预留配置 `audit.review.enforce-double-check`（默认 false），团队扩张时一键升级为硬拒绝。

### 挂账 / 调账状态机（要点）

- 差异生命周期：`PENDING → SUSPENDED（挂账）→ ADJUSTED（调账）→ VERIFIED（recheck 通过）`；批次内全部 VERIFIED 方可关批（CLOSED），否则关批返回 400。
- 调账五类：`SUPPLEMENT`（补记）/ `REVERSE`（红冲，append-only 不删原分录）/ `CORRECT` / `TRANSFER`（挂账转正式）/ `WRITE_OFF`（核销，MVP 默认关闭：`audit.adjust.write-off.enabled=false`，因科目表暂无损益类对手科目）。
- 硬规则：借贷必须平衡、`adjust:{adjustNo}` 幂等、累计调账金额不超差异额、绝不自动调账、绝不改业务单据状态。

### 验证与可观测（负责人追加需求）

- **确定性模拟数据** F1~F10（平账组 + 8 类故障 + 混合组），单号金额固定，自动化测试与 demo 演示共用一份。
- **Demo 控制台** `audit.html`（mock-channel-web:8091，`demo.html` 并列）：触发 → 执行日志 → 差异结果 → 挂账 → 调账 → recheck → 关批全链路可见；已交付离线可交互原型（`audit-console-mockup.html`），实施期由 T070 迁入 `static/` 并接真实 API。
- E2E 断言脚本 `deployment/demo/scenario-audit.sh`（①~⑩），验收矩阵 TC-01~TC-24 见 spec 017 `acceptance.md`。

## 影响

- **正影响**：spec 004 SC-005（悬空已久的验收标准）兑现；差异从「记下来」升级为「账面闭环」；SUSPENSE 余额成为差错资金的可监控水位；结算出口获得资金安全门禁。
- **代价**：`reconciliation-service` 体积增长（audit 包 + 日切调度 `@Scheduled` T-1）；新增 `audit_*` 表族与 SUSPENSE 科目；结算创建增加一次门禁查询。
- **不做**：不改 payment/refund/settlement 业务代码（只读其数据）；不引入 MQ / 分布式事务；不新建服务；不做实时对账（日切 T-1 已满足当前业务）。

## 后续待确认（不阻塞实施）

- ⓐ `WRITE_OFF` 对手科目（建议将来新增 `LOSS_ON_WRITE_OFF`；MVP 先不开放该入口）。
- ⓑ 结算单「本期含未收口挂账 ¥X」标记字段（建议加，待实施时确认落表位置）。
