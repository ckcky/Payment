# Acceptance: 分布式演进门禁（010-distributed-evolution）

**Feature**: `010-distributed-evolution` | **Date**: 2026-08-30 | **Spec**: [spec.md](spec.md) | **ADR**: [0010-distributed-evolution-decisions.md](../../adr/0010-distributed-evolution-decisions.md)

> 实现已完成（最简方案），`mvn -o verify -fae` 全量 14 模块 BUILD SUCCESS。功能项全部勾选；**决策验收仍需负责人确认**（Constitution §8）。

## 功能验收

### US1 - 边界不变量自动守护（Priority: P1）

- [x] 服务之间零编译期包依赖：任何 `com.payment.<svc>..` 类依赖另一个服务的类 → 构建失败（FR-001 / ADR-0029）
- [x] `domain..` 不依赖 `org.springframework..` / `org.mybatis..` / `com.baomidou..`（FR-002）
- [x] `domain..` 不依赖自身或其它服务的 `infra..`（依赖方向 infra → domain）（FR-002）
- [x] `api..` / `web..` 不依赖 `infra.persistence..`（FR-003）
- [x] 全系统不出现 MQ（`kafka` / `amqp` / `rabbitmq` / `rocketmq` / `jms`）与 JTA-XA（`jakarta.transaction` / `javax.transaction` / `atomikos`）依赖（FR-004 / ADR-0031）
- [x] 四条不变量在**当前代码**上全部成立（不是先写规则再改代码凑出来的）

### US2 - 门禁不空转（Priority: P1）

- [x] 防空转门禁：每个服务导入的类数必须 > 5，否则测试失败（FR-005）
- [x] 一次性探针验证：注入已知会被违反的规则后，测试确实报错，并列出具体类名与源码位置（如 `FeignLedgerPostingGateway.buildEntries(long)` → `PostingRequest$EntryRequest.<init>`）；探针已移除（SC-002）
- [x] 导入方式为按目录读取各服务 `target/classes`（服务 jar 是重打包的可执行 jar，类在 `BOOT-INF/classes`，无法作为普通依赖 import）

### US3 - 拆分有据可依（Priority: P2）

- [x] `docs/operations/split-proposal-template.md` 四段必填：问题（证据）/ 收益 / 成本 / 回滚方案（FR-006 / ADR-0033）
- [x] 模板强制填写「已排除的更廉价方案」，防止「看起来该拆了」式提案（ADR-0029）
- [x] 模板含契约兼容性检查清单（`common-dto` 向后兼容、破坏性变更走新端点 + 双轨期）（FR-006）
- [x] 独立数据库迁移的触发条件已登记（容量 / 隔离 / 合规归属 / 可用性）（ADR-0030）
- [x] 异步消息引入判据已登记，且明确「MQ 只用于通知与解耦，不得承载资金事实唯一真相」（ADR-0031）

### US4 - 运维可查（Priority: P2）

- [x] `docs/operations/runbook.md` 覆盖全部 10 个服务（FR-007）
- [x] 端口、下游 Feign 依赖、Schema 与 `application.yml` 实际配置一致（8081~8090）（SC-004）
- [x] 含启动顺序（依赖方向自检）、数据库说明、环境变量清单（含 Phase 9 三个密钥）（FR-007）
- [x] 含关键指标表与常见故障处置表（含 001-007 阶段踩过的真实故障）（FR-007）
- [x] 含回滚章节，且明确账本「优先补偿分录而非删数据」（FR-007）
- [x] 含关键等级 T0~T3 分级表（ADR-0032）

## 非功能验收

- [x] 新增模块 `architecture-tests` 无业务代码、不被任何服务依赖、不产出可部署构件（FR-008）
- [x] 模块排在根 `pom.xml` `<modules>` 末尾（FR-008）
- [x] 未引入 Kubernetes / Service Mesh / CQRS / Event Sourcing / MQ（Roadmap Phase 10 禁止默认引入）
- [x] 未改动任何服务的运行时行为与契约
- [x] `mvn -o verify -fae` 全量 14 模块 BUILD SUCCESS，0 失败 0 错误（SC-003）

## 决策验收（Constitution §8）

- [ ] ADR-0029（本期不拆分、改立边界门禁 + 防空转门禁）经负责人确认并置 Accepted
- [ ] ADR-0030（独立数据库迁移触发条件与顺位建议）经负责人确认并置 Accepted
- [ ] ADR-0031（异步消息引入判据与一致性约束）经负责人确认并置 Accepted
- [ ] ADR-0032（关键等级 T0~T3 分级）经负责人确认并置 Accepted
- [ ] ADR-0033（提案模板与运行手册作为准入门禁）经负责人确认并置 Accepted
- [ ] 新增 `architecture-tests` 模块（第 14 个 Maven 模块）经确认

## 已知未闭环

1. **未触发任何真实拆分**：本 Feature 刻意只做门禁。真实拆分需按 `split-proposal-template.md` 单独立项。
2. **运行手册基于当前单实例形态**：一旦部署形态变更，手册须同步更新（模板的兼容性检查里已列为必答项）。
3. **关键等级尚未绑定 RTO/RPO 指标**：ADR-0032 待确认项为 T0 定义独立 RTO/RPO。
