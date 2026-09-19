# Plan: 010-distributed-evolution

**对应 Spec**：`spec.md`
**决策**：见 `docs/adr/0010-distributed-evolution-decisions.md`（ADR-0029~0033，均 Proposed）

## 总体方案

Phase 10 的最简实现**不是拆服务，而是立门禁**。三件产物：

1. **`architecture-tests` 模块**（代码）—— ArchUnit 结构校验，把「服务还能不能被独立拆出去」变成构建期断言。
2. **`docs/operations/runbook.md`** —— 运维可查：端口、依赖、健康检查、关键指标、故障处置、回滚、环境变量。
3. **`docs/operations/split-proposal-template.md`** —— 拆分提案模板，四段必填，缺一不予评审。

## 为什么不直接拆分

- Roadmap Phase 10 前置条件：「至少一个真实业务瓶颈或隔离需求；拆分方案和运维成本获得负责人确认」——当前两者皆无。
- Roadmap 明确排除：「不因『看起来像微服务』而默认引入 Service Mesh、Kubernetes、CQRS 或 Event Sourcing」。
- Constitution §8：服务边界与数据库 Schema 变更属 MUST 由人确认的决策边界。

## 落点

- 新增 `architecture-tests/`（第 14 个 Maven 模块）：
  - `pom.xml`：test 依赖全部服务（**仅用于固定 reactor 顺序**）+ `spring-boot-starter-test` + `archunit-junit5`；`maven.install.skip` / `spring-boot.repackage.skip`。
  - `src/test/java/com/payment/arch/ServiceBoundaryTest.java`：5 条结构规则 + 1 条防空转门禁。
- 根 `pom.xml`：`<modules>` 末尾追加 `architecture-tests`。
- 新增 `docs/operations/runbook.md`、`docs/operations/split-proposal-template.md`。

## 实现要点（踩坑记录）

1. **不能把服务当普通依赖 import**：各服务经 `spring-boot-maven-plugin` 重打包，类在 `BOOT-INF/classes` 下。故用 `new ClassFileImporter().importPaths(...)` 按目录导入各服务的 `target/classes`，而不是 classpath 导入。对各服务的 test 依赖只用于让 reactor 把它们排在本模块之前。
2. **结构规则全是否定式，天然会空转**：「no classes should ...」在导入 0 个类时必然通过。故必须先有防空转门禁（断言每个服务导入类数 > 5），并用一次性探针验证规则真的会报错（探针已确认能列出具体类名与方法位置，随后移除）。
3. **模块必须排在 `<modules>` 末尾**：它读取其它模块的编译产物。

## 关键决策（最简实现，待确认）

- 本期零拆分、零新基础设施（ADR-0029）。
- 独立数据库：只登记触发条件与建议顺序，不动手（ADR-0030）。
- 异步消息：继续不用 MQ，只登记引入判据（ADR-0031）。
- 扩缩容排序：用关键等级（T0~T3）分级表，不改任何部署形态（ADR-0032）。
- 拆分准入：提案四段必填 + 契约向后兼容（ADR-0033）。
