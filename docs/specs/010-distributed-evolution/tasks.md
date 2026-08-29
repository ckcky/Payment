# Tasks: 010-distributed-evolution

**Current Progress（2026-08-30）**：实现完成，`mvn -o verify -fae` 全量 14 模块 BUILD SUCCESS。ADR-0029~0033 写入 `docs/adr/0010-distributed-evolution-decisions.md`（均 Proposed，待负责人确认）。

## Implementation

- [x] T001 新增 `architecture-tests` 模块 `pom.xml`：test 依赖全部服务（仅用于固定 reactor 顺序）+ `spring-boot-starter-test` + `archunit-junit5`；`maven.install.skip` / `spring-boot.repackage.skip`。
- [x] T002 根 `pom.xml` `<modules>` 末尾追加 `architecture-tests`（它按目录读取各服务 `target/classes`，必须最后构建）。
- [x] T003 `ServiceBoundaryTest`：规则 1 —— 服务之间零编译期包依赖（跨服务仅经 `common-dto` + HTTP/Feign）。
- [x] T004 `ServiceBoundaryTest`：规则 2 —— `domain..` 不依赖 Spring / MyBatis / MyBatis-Plus / 自身 `infra..`。
- [x] T005 `ServiceBoundaryTest`：规则 3 —— `api..` / `web..` 不依赖 `infra.persistence..`。
- [x] T006 `ServiceBoundaryTest`：规则 4 —— 全系统不出现 MQ / JTA-XA 依赖。
- [x] T007 `ServiceBoundaryTest`：防空转门禁 —— 每个服务被真正导入的类数 > 5，否则否定式规则会「0 个类全通过」。
- [x] T008 `docs/operations/runbook.md`：10 服务端口 / 依赖 / 启动顺序 / Schema / 环境变量 / 关键指标 / 故障处置 / 回滚。
- [x] T009 `docs/operations/split-proposal-template.md`：问题（证据）/ 收益 / 成本 / 回滚四段必填 + 契约兼容性检查。
- [x] T010 `docs/adr/0010-distributed-evolution-decisions.md`：ADR-0029~0033 合并一文档。

## Verification

- [x] T011 用一次性探针验证规则有效性：注入一条已知会被违反的规则（settlement 依赖 common），确认报错并列出具体类名与方法位置；验证后移除探针。
- [x] T012 `mvn -o verify -fae` 全量 14 模块 BUILD SUCCESS，0 失败 0 错误。
- [x] T013 更新 `docs/architecture/roadmap.md`：Current Status 推进 010，Phase 10 章节补落地情况。
- [ ] T014 负责人确认 ADR-0029~0033 状态为 Accepted（代码已按最简实现，确认后无需改实现）。
