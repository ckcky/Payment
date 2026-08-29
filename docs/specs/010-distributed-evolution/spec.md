# Spec: 010-distributed-evolution（分布式演进门禁）

**版本**：0.1
**日期**：2026-08-30
**状态**：Proposed（代码按最简实现已落地，ADR-0029~0033 待负责人确认）

## 1. 背景与目标

Roadmap Phase 1~9 已交付完整业务闭环（001 核心模型 / 003 支付可靠性 / 004 账本 / 005 退款 / 006 对账 / 007 结算 / 009 风险安全底座）。当前形态：10 个服务 + 3 个共享模块，**服务已物理分模块、数据按 Schema 隔离、跨服务只走同步 HTTP/Feign**，但全部 Schema 跑在同一 MySQL 实例、全部服务跑在同一台机器。

Phase 10 的目标**不是**把系统推到 Kubernetes / Service Mesh / MQ 上——Roadmap 明确写着「不因『看起来像微服务』而默认引入 Service Mesh、Kubernetes、CQRS 或 Event Sourcing」，前置条件是「至少一个真实业务瓶颈或隔离需求；拆分方案和运维成本获得负责人确认」。

因此本 Feature 的目标是：**把「按证据演进」变成可执行的门禁**。真要拆分时，必须带着填好的提案走评审；而「服务还能不能被拆出去」这件事，由构建期测试持续保证，而不是靠人记得。

## 2. 范围

**包含（MVP）**
- 服务边界测试：用 ArchUnit 在构建期强制「服务可独立演进」的结构性前提（ADR-0029）。
- 独立数据库迁移的**触发条件**与迁移顺序建议（ADR-0030）。
- 跨服务异步消息的**引入判据**与不可越过的一致性约束（ADR-0031）。
- 服务关键等级（criticality tier）分级，作为扩缩容与隔离投入的排序依据（ADR-0032）。
- 拆分提案模板 + 运行手册（ADR-0033）。

**不包含**
- 任何实际的服务拆分、数据库实例拆分、容器化、Service Mesh、Kubernetes、CQRS、Event Sourcing。
- 引入 MQ / JTA-XA（构建期已有规则禁止）。
- 压测与容量规划执行（只定义触发阈值与提案要求）。

## 3. 关键用户故事

- **US1 边界不变量自动守护**：一旦有人写出跨服务的编译期依赖、让 domain 依赖 Spring、让 Controller 直连仓储、或引入 MQ，构建 MUST 立刻失败。
- **US2 门禁不空转**：若结构规则因导入失败而「0 个类全通过」，构建 MUST 失败（防空转门禁）。
- **US3 拆分有据可依**：任何一次拆分提案 MUST 含问题（证据）、收益、成本、回滚方案四段，缺一不予评审。
- **US4 运维可查**：每个服务的端口、依赖、健康检查、关键指标、常见故障处置与回滚步骤 MUST 集中可查。

## 4. 功能需求（FR）

- FR-001 `architecture-tests` 模块 MUST 校验：服务之间零编译期包依赖（跨服务仅经 `common-dto` + HTTP/Feign）。
- FR-002 MUST 校验：各服务 `domain..` 不依赖 `org.springframework..` / `org.mybatis..` / `com.baomidou..` / 自身 `infra..`。
- FR-003 MUST 校验：各服务 `api..` / `web..` 不依赖 `infra.persistence..`。
- FR-004 MUST 校验：全系统不出现 MQ、JTA/XA 相关依赖。
- FR-005 MUST 校验：每个服务被真正导入的类数 > 5，防止规则空转。
- FR-006 提供 `docs/operations/split-proposal-template.md`：问题（证据）/ 收益 / 成本 / 回滚方案四段必填 + 契约兼容性检查项。
- FR-007 提供 `docs/operations/runbook.md`：10 服务的端口、上下游依赖、健康检查、关键指标、故障处置、回滚步骤、环境变量清单。
- FR-008 新增模块 MUST 位于根 `pom.xml` `<modules>` 末尾（它按目录读取各服务 `target/classes`），且不产出可部署构件。

## 5. 验收标准（SC）

- SC-001 `ServiceBoundaryTest` 全部通过，且防空转门禁能证明导入了真实类（实测每个服务 > 5 个类）。
- SC-002 注入一次真实违规时，对应规则 MUST 报错并列出具体类名与位置（已用临时探针验证后移除）。
- SC-003 `mvn -o verify -fae` 全量 14 模块 BUILD SUCCESS。
- SC-004 运行手册覆盖全部 10 个服务，且与 `application.yml` 中的端口、Feign 超时配置一致。
