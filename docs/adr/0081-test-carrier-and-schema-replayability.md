<a id="adr-0081"></a>

# ADR-0081：测试载体升级——真库 Testcontainers（仅测试作用域）+ schema 双路径可重放门禁 + RPC 边允许清单（Feature 033）

> 承载 [spec 033-test-infrastructure](../specs/stage-05-channel-and-finance-deepening/033-test-infrastructure/spec.md) 的三条决策。
> 状态：**🟡 Proposed（2026-09-21 提出，待负责人裁决）**——涉及 Constitution
> §Engineering.3「不引入 Testcontainers」的**现行约束变更**（design-review §13 **H16**），**未 Accepted 前不得实现**。

---

## Context

走查与代码核对（spec 033 §1 的 23 条【现状】证据）确认三件事：

1. **正确性基础设施有一道结构性盲区**：L2 集成测试全部跑在 H2（MySQL 兼容模式）+ 各服务自带的
   `src/test/resources/schema.sql` 副本 + 19 个 `InMemory*Repository` 桩上。
   桩能证「撞键之后的处理逻辑对」，**证不了「真的会撞键且只有一个赢家」**——后者恰是资金系统最要紧的断言。
   全仓只有 1 个真库测试（`LedgerPostingConcurrencyTest`，030/T11 引入），且**手搓、无 Docker 即整体 skip**、模式未复用。
2. **真实升级路径零覆盖**：CI 建表只重放全量 `NN-*.sql` + `027`，**显式跳过 `015/016/018/019/030` 增量脚本**
   （`.github/workflows/e2e.yml:76-89` 注释即此意）⇒ 「存量库上执行增量迁移」从未被执行，
   `016-refund-channel-attempt.sql:7` 的 MariaDB 方言因此长期潜伏。
   同时全量文件普遍用 `CREATE TABLE IF NOT EXISTS`（**27 处**）⇒ 对存量表**不补列**，静默「表在、列不在」。
   正确先例其实已有（018/019/030 的 `information_schema` 守卫 + `PREPARE`），但**未成条文、无 lint**。
3. **架构门禁看不见运行时环**：`ServiceBoundaryTest` 的 10 条规则都是字节码静态依赖断言，
   拦不住 Feign/Nacos 构成的 `order ↔ payment` 环（backlog #4 / design-review M10）。

另有一条外部约束：031（余额投影并发累加）与 032（账单导入幂等）的 acceptance 已把「真库并发验证」
**显式登记为已知缺口**——本 ADR 是该缺口唯一的关闭路径。

---

## Decision

### 决策 1：Testcontainers 放宽至**仅测试作用域**，并抽成共享基座

- 新增 `deployment/test-infra`（test scope，放 `deployment/` 下以守 AGENTS.md「非领域组件收口」纪律），
  提供容器生命周期（跨类复用、类内独占 database）、`SchemaBootstrap`（DDL **只能**来自 `deployment/schema/`）、
  Docker 可用性契约、`FaultHooks`、`MetricsAssert` 五件事；基座内 MUST NOT 出现业务词汇。
- **只切三类场景**到真库（L2b）：幂等唯一键竞争、余额投影并发累加、批次/业务号并发唯一。
  **不做**全量切换：与方言无关的用例留在 H2 是正确选择，无理由切换即过度设计。
- **既有 H2 用例与 `InMemory*Repository` 零删除**（并存；真库断言约束、H2 断言逻辑，重叠面收敛）。
- 生产依赖树零变化：补一条 L5 规则「`src/main` MUST NOT 依赖 `org.testcontainers`」把口子封死。

### 决策 2：schema **双路径重放**成为 CI 门禁，守卫模式升格为规范条文

- 路径 A（空库全量）与路径 B（存量基线 + 增量脚本）各产生一份 `information_schema` 快照，**diff 必须为空**；
  路径 A 连跑两遍结果一致（幂等自检）。
- `engineering-standards` 新增「迁移脚本可重放」小节，六条可判定条文（守卫模式强制、禁 MariaDB 方言、
  自带 `USE`、文件头三件事、禁 `DROP COLUMN`、编号与 Feature 一一对应）；
  配构建期静态 lint 三条（L-1 方言、L-2 `initdb` 与 schema 库集合一致、L-3 `ALTER` 必带守卫）。
- 顺带收口 code-debt #1（🔴 High）：`initdb/01-create-databases.sql` 补 `ledger` 库；
  已退役的 `refund` 库**保留并标注遗留**，删除另立 chore（避免误伤存量库）。

### 决策 3：运行时 RPC 环改用「**边允许清单**」治理，而非环检测

静态抽取各 `infra/client/*FeignClient` 的 `(调用方服务, 目标服务, 端点)` 三元组 → 生成 `rpc-edges.txt`（进 git）。
门禁断言「集合只能等于基线」，**新增边必须改基线 ⇒ 强制在 review 里讨论这条边该不该有**。
副产品：该文件是 `technical-solution.md` 调用图的事实来源，可顺带压 D-4 类文档漂移。
（备选 R-B「显式接受环并立 ADR」被否决：允许清单的实现成本同为一次，却同时保留了「防止继续恶化」的能力。）

### 决策 4：033 只拥有**载体与门禁**，用例本体归业务 Feature

033 的 PR MUST NOT 含业务断言；业务 Feature 的 acceptance MUST NOT 以「由 033 覆盖」替代自身必测项。
（这条是范围条款而非技术条款，但它决定 033 能否收敛——故写进 ADR。）

---

## 备选方案

- **A. 全面把 L2 换成 Testcontainers**：CI 时长翻倍、runner 成本上升，且多数用例与方言无关——为覆盖率牺牲反馈速度，否决。
- **B. 维持现状**（并发靠 demo 手跑）：031/032 正在新增真库约束与并发路径，缺口只会扩大；
  且与 Constitution §I「真实 > 全面」直接冲突，否决。
- **C. 采纳**：定向三类 + 双路径门禁 + 边允许清单。

---

## Consequences

**正面**
- 「唯一约束挡住了」第一次成为**被实测过**的事实，而不是注释里的声明；
- 迁移脚本第一次有「存量库」这条路径的自动化验证，`016` 类方言缺陷在 PR 即红；
- 运行时 RPC 边第一次可见、可评审、可对齐文档；
- 031 §10 / 032 §8 的已知缺口有明确关闭路径与验收口径。

**代价 / 风险**
- CI 增加两个 job（`real-db` ≤8min、`schema-replay` ≤6min）与维护面；
- 无 Docker 环境的开发者体验变化：本地 skip（可见 + WARN），CI skip **即红**——
  「禁假绿」是刻意的取舍，代价是偶发环境性红灯；
- 基线文件（`deployment/schema/baseline/*.sql`）需随 Feature 递增，过期不更新会让路径 B 检测力下降（已登记为风险 R4）；
- 双份用例（H2 + 真库）长期并存，靠「断言对象不同」的纪律维持，而非工具强制。

---

## 待人类裁决（详见 spec 033 §15）

| # | 决策项 | 边界类型 | 推荐 |
|---|---|---|---|
| H-033-1 | 是否放宽「不引入 Testcontainers」（= design-review §13 **H16**） | 测试载体变更 | 放宽（仅测试作用域 + L5 封口） |
| H-033-2 | `deployment/schema/baseline/*.sql` 门禁基线是否入库 | 数据资产新增 | 入库 |
| H-033-3 | 已退役 `refund` 库与 `06-refund-schema.sql` 的处置 | 数据库结构变更 | 保留 + 标注遗留 |
| H-033-4 | 运行时 RPC 环取「边允许清单」还是「显式接受立 ADR」 | 门禁口径 | 边允许清单 |
| H-033-5 | CI 真库测试「skip 即红」是否作为强制门禁 | 交付节奏 | 强制 |
