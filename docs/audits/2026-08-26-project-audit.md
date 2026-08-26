# PaymentArch 项目治理与文档体系审计

> **Status: superseded（已归档）**
>
> 本报告结论已并入 [docs/architecture/overview.md](../architecture/overview.md) 与 [docs/architecture/roadmap.md](../architecture/roadmap.md)，仅作历史留档，不再作为权威事实源。

> 审计日期：2026-08-26
>
> 审计范围：当前仓库文件、Git 状态、根 Maven 工程、服务骨架、文档、`.claude/`、`.specify/` 和 Feature 001。
>
> 说明：本报告只记录当前仓库中能够确认的事实，不把目标目录、文档声明或任务清单当成已经完成的实现。

## 审计后负责人裁决

以下决策已在本次审计后确认，并作为当前有效基线：

- 采用多个独立微服务；单机部署只表示服务运行在同一台服务器上，不改变服务边界。
- 每个服务使用不同端口和独立 Schema；单机阶段允许这些 Schema 位于同一个物理数据库。
- 跨服务统一使用同步 HTTP/RPC；当前不使用跨服务异步事件、Kafka、RabbitMQ 或其他 MQ。
- Feature 文档统一位于 `docs/specs/<feature>/`。
- Payment Channel 当前属于 `payment-service` 内部，只实现 Channel Adapter + Mock Channel。
- Settlement 当前只生成结算批次和模拟结果，不执行真实出款。
- 当前 Payment、Refund、Settlement 只模拟业务资金事实；真实资金模型必须通过 Ledger 建立可追溯账务事实。
- Feature 开发唯一入口使用 Spec Kit；`review`、`payment-review` 和 `test` 只作为辅助检查。

本报告第 5 至第 9 节记录的是初始审计时发现的问题；已被上述裁决解决的项目，以 `docs/00-project/architecture.md`、`docs/00-project/roadmap.md` 和当前 Feature Plan 为准。

## 1. 当前项目状态

### 1.1 总体判断

当前项目处于：**架构和 SDD 文档已建立，Maven 多模块服务骨架已出现，但业务实现尚未开始，且长期架构决策与第一阶段实现形态尚未收口的过渡状态。**

当前不是纯文档项目，也不是可运行的 Commerce & Payment Platform MVP。更准确地说，它是一个已经创建了多服务启动壳、正在从架构设计进入实施准备的仓库。

### 1.2 已确认存在的内容

- 根目录存在 `pom.xml`、`README.md`、`CLAUDE.md` 和 `.gitignore`。
- 根 POM 管理 12 个 Maven 子模块：3 个 `common-*` 模块和 9 个业务服务模块。
- 9 个服务目录均有启动类、`application.yml` 和上下文测试骨架。
- 目前可统计到 9 个主源码 Java 文件、9 个测试 Java 文件、13 个 POM 文件（包含根 POM）。
- `mvn -q -DskipTests validate` 当前通过，说明 Maven 工程结构至少可以完成基础模型校验。
- 当前不存在 `mvnw`、`mvnw.cmd`，因此尚未具备文档所要求的 Maven Wrapper 可复现入口。
- 当前不存在 Docker Compose、Dockerfile、`.github/workflows/` CI 文件或 Roadmap 文件。
- 当前不存在业务 Controller、领域实体、状态机、Repository 实现、Mapper、业务事件处理器或集成测试。
- Git 工作树存在未提交的文档、模块骨架和 POM 变化；当前最后一次提交是项目初始化提交，工作树不是干净状态。

### 1.3 当前模块实际成熟度

| 范围 | 当前事实 | 判断 |
|---|---|---|
| 根构建 | 根 POM 和子 POM 已存在，`validate` 通过 | 构建骨架 |
| 服务启动 | 9 个服务有启动类 | 启动壳，不代表服务能力 |
| 业务代码 | 未发现业务实体、接口、状态机或编排 | 尚未实现 |
| 测试 | 主要是 Spring 上下文加载测试 | 只有骨架测试 |
| 持久化 | 未发现 Mapper、迁移或业务持久化实现 | 尚未实现 |
| 事件 | 未发现业务事件实现或消费处理器 | 尚未实现 |
| 运行环境 | 没有 Compose、Dockerfile、CI | 尚未交付 |
| 资金能力 | 没有 Ledger，也没有真实资金动作 | 不具备真实资金能力 |

## 2. 当前文档体系

### 2.1 当前文件和职责

| 文件/目录 | 当前职责 | 当前问题 |
|---|---|---|
| `README.md` | 项目简介和入口导航 | 仍写“尚未创建业务服务代码”，与实际 9 个服务骨架不符；缺少当前状态、启动命令和路线图 |
| `CLAUDE.md` | AI 工作总纲、硬性红线、文档索引 | 同时承担项目地图、治理规则、工作流入口；没有明确当前架构裁决结果 |
| `.specify/memory/constitution.md` | 最高约束、领域边界、资金正确性、AI 规则和人类决策边界 | 明确写的是 Spring Cloud 微服务，与当前 Feature 001 的模块化单体计划冲突 |
| `docs/adr/0001...` | 记录采用 Spring Cloud 微服务的架构决策 | 状态为 Accepted，但当前第一阶段又明确采用模块化单体，尚未有正式修订或替代 ADR |
| `docs/adr/0002...` | 记录 Java、Spring Boot、Spring Cloud、Maven、MyBatis、Nacos 等技术选择 | 当前 MVP 明确不需要 Spring Cloud，但 ADR 仍是 Accepted；与当前 Plan 不一致 |
| `docs/ai-workflow.md` | 描述 Spec → Plan → Task → Implement → Review 流程 | 与 `.claude/commands/feature.md`、`.specify/workflows/speckit/workflow.yml` 存在流程重复和路径表述差异 |
| `docs/documentation.md` | 文档类型、优先级、路径和维护规则 | 大体清晰，但仍把 Spec 写成 `specs/<feature>/`，实际 Feature 位于 `docs/specs/`；路径没有最终统一 |
| `docs/development-guide.md` | 面向日常开发的入口和建议开发顺序 | 仍以“没有业务代码和构建骨架”为前提，与当前已有服务骨架和 POM 不一致 |
| `docs/project-structure.md` | 目标目录结构和分层约定 | 描述的目标是微服务单仓库，当前 Feature Plan 却选择单体 `platform/`，且 `platform/` 实际不存在 |
| `docs/engineering-standards.md` | Java、测试、资金、一致性、可观测、CI/CD 和安全规范 | 与 Constitution 大量重复；部分要求当前还没有对应工具或实现 |
| `docs/specs/001-core-business-model/` | Feature 001 的 Spec、Plan、研究、数据模型、契约、quickstart、tasks | 设计资料较完整，但目录放置和长期架构基线还未统一 |
| `.claude/commands/` | `feature`、`review`、`test`、`payment-review` 四个项目命令 | `feature` 与 Spec Kit 阶段命令重复；`test` 中仍描述 MQ、Testcontainers 等未必属于当前阶段的内容 |
| `.claude/skills/` | 架构、支付领域、可观测和 Spec Kit 各阶段 Skill | 领域 Skill 与 Constitution/工程规范存在规则重复；职责边界未形成总表 |
| `.specify/` | Spec Kit 模板、脚本、工作流、集成配置和 Feature 指针 | 工具配置真实存在，但 `feature.json` 当前指向 `docs/specs/...`，与早期文档中的 `specs/...` 约定不一致 |

## 3. 当前 AI 协作体系

当前 AI 协作体系由四层组成：

1. **项目硬规则**：`CLAUDE.md` 和 `.specify/memory/constitution.md`。
2. **项目规范**：`docs/engineering-standards.md`、`docs/ai-workflow.md`、领域 Skill。
3. **Spec Kit 工具流程**：`.claude/skills/speckit-*`、`.specify/scripts/` 和 `.specify/workflows/`。
4. **执行命令**：`.claude/commands/feature.md`、`review.md`、`test.md`、`payment-review.md`。

### 3.1 当前协作链路

理论链路是：

```text
需求
→ speckit-specify
→ speckit-clarify（需要时）
→ speckit-plan
→ speckit-analyze（需要时）
→ speckit-tasks
→ speckit-implement
→ review / payment-review
```

但仓库同时存在一个 `.specify/workflows/speckit/workflow.yml`，它又定义：

```text
specify → review-spec → plan → review-plan → tasks → implement
```

因此当前有两套“应该怎么走”的入口：项目命令描述的流程，以及 Spec Kit workflow 配置描述的流程。

### 3.2 当前优点

- 资金正确性、幂等、UNKNOWN、状态机、数据所有权等关键风险已经被反复强调。
- Spec Kit 的核心阶段、模板和脚本已落入仓库，不依赖个人记忆。
- 支付领域、架构和可观测性有专门 Skill，便于在高风险 Feature 中加载专项知识。
- `tasks.md` 已经能够提供按用户故事组织的执行清单。

### 3.3 当前主要问题

- AI 没有一个明确的“先读取哪一份裁决、遇到冲突停在哪里”的单页入口。
- Constitution、ADR、Plan 和 Engineering Standards 都在重复定义架构、依赖、一致性和资金规则。
- `feature` 命令和 Spec Kit Skill 都在描述 Spec/Plan/Task/Implement 流程，容易造成同一阶段被重复解释或路径不一致。
- Skill 中有些内容是规则，有些内容是检查清单，有些内容是操作指导，但没有明确区分。

## 4. 当前 Spec Kit 使用方式

### 4.1 已配置内容

- Spec Kit 版本记录为 `1.0.2.dev0`。
- 当前集成是 `claude`，脚本配置为 `sh`。
- Feature 编号策略为顺序编号。
- 已配置 `specify`、`plan`、`tasks`、`implement` 核心工作流。
- 已有 Spec Kit 模板：Spec、Plan、Tasks、Checklist、Constitution。
- `.specify/feature.json` 当前指向 `docs/specs/001-core-business-model`。

### 4.2 当前 Feature 的实际使用方式

当前 Feature 001 实际使用的是：

```text
docs/specs/001-core-business-model/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── contracts/README.md
├── quickstart.md
├── tasks.md
└── checklists/requirements.md
```

这与 Spec Kit Skill 中默认示例的 `specs/<feature>/` 不一致，但脚本当前通过 `feature.json` 能解析到 `docs/specs/...`。

### 4.3 Spec Kit 已经承担的职责

Spec Kit 适合承担：

- 创建 Feature 目录和模板。
- 生成 Spec、Plan、研究、数据模型、契约和 quickstart 初稿。
- 生成按故事组织的任务清单。
- 提供 Feature 上下文传递。
- 提供清单和阶段门禁。

### 4.4 不应交给 Spec Kit 自动决定的内容

- 是否采用模块化单体还是微服务。
- 是否引入新服务、中间件或数据库边界。
- 支付状态机和资金正确性规则。
- 领域边界和数据所有权。
- 破坏性迁移、安全策略和生产部署策略。
- 当前项目的 Roadmap 和 MVP 取舍。

这些必须由项目负责人通过 Constitution/ADR/批准的 Plan 明确。

## 5. 当前架构文档缺口

### 5.1 缺少总体技术方案

**是，当前缺少一份已经批准且与实现状态一致的总体技术方案。**

已有内容分散在 Constitution、ADR-0001、ADR-0002、`project-structure.md` 和 Feature 001 Plan 中，但它们不能作为一份稳定的系统架构基线，原因是：

- ADR-0001 已接受 Spring Cloud 微服务。
- Feature 001 Plan 明确采用模块化单体。
- 根 POM 已按微服务多模块生成服务骨架。
- 当前 MVP Plan 又规划 `platform/` 单体目录，而该目录尚不存在。

缺少的总体方案至少应该回答：

- 当前第一阶段到底采用哪种运行形态。
- 长期目标和当前实现形态如何共存。
- 根 POM 的模块边界是否就是当前真实边界。
- 哪些服务目录只是占位，哪些必须保留。
- 业务事件归属和未来拆分规则。
- 数据库/持久化的当前边界。
- 本地、Compose、单机和后续拆分的演进路径。

### 5.2 缺少架构决策状态管理

当前 ADR 有 `Accepted`，但没有 `Superseded`、`Proposed` 或“当前有效范围”说明。一个长期项目需要能区分：

- 长期目标架构。
- 当前阶段实现架构。
- 已被新决策替代的历史架构。
- 仍需负责人批准的方案。

### 5.3 缺少可执行的契约总览

Feature 001 有局部契约，但缺少项目级契约目录规则、事件版本策略和公共 DTO 的所有权说明。根 POM 中的 `common-dto` 已存在，但没有实际契约代码或“哪些契约可以进入 common”的批准规则。

## 6. 当前项目规划缺口

### 6.1 缺少项目 Roadmap

**是，当前没有 Roadmap。** 当前只有 Feature 001 的实施阶段，没有项目级的里程碑、退出条件和范围管理。

缺少以下内容：

- 阶段 0：治理和架构收口。
- 阶段 1：最小可运行 MVP。
- 阶段 2：资金账本和真实资金事实模型。
- 阶段 3：真实渠道、退款策略和对账增强。
- 阶段 4：可观测性、交付和 CI/CD 增强。
- 阶段 5：有证据的部分服务拆分。
- 每个阶段的“开始条件、交付物、验收条件、明确不做什么”。
- 哪些内容是当前 Feature，哪些必须另开 Feature。

### 6.2 缺少当前迭代入口

负责人目前无法只看一个页面回答：

- 本周要完成什么。
- 当前 Feature 是否允许开始。
- 需要什么人工决策。
- 哪些任务可以并行。
- 完成后如何证明可运行。

Feature 001 的 `tasks.md` 能回答实现任务，但不能替代项目 Roadmap 和当前迭代板。

## 7. 当前规则重复/冲突分析

### 7.1 主要重复

| 内容 | 重复位置 | 影响 |
|---|---|---|
| 领域边界 | Constitution、payment-domain、engineering-standards、Spec、Plan | 修改一次需要同步多处，容易出现不同版本 |
| 幂等、UNKNOWN、状态机 | Constitution、payment-domain、review、payment-review、Spec、Plan、Tasks | 规则和检查项混在一起，容易误以为重复描述就是新增约束 |
| 测试要求 | Constitution、engineering-standards、test 命令、Plan、Tasks | 规范、操作步骤和任务未分层 |
| 文档流水线 | ai-workflow、feature 命令、Spec Kit workflow、各 speckit Skill | 存在多个入口，路径和门禁表述不完全一致 |
| 架构分层 | Constitution、architecture Skill、project-structure、review | 同一原则有多个维护点 |
| 可观测性 | Constitution、engineering-standards、observability Skill、Plan、Tasks | 指标名称和实施阶段没有一个项目级事实源 |

### 7.2 关键冲突

#### 冲突 1：长期微服务决策 vs 当前模块化单体

- Constitution IV：Spring Cloud 微服务、每服务独立数据库。
- ADR-0001：Spring Cloud 微服务为 Accepted 决策。
- 根 POM：按多个服务模块组织，并引入 Spring Cloud BOM。
- Feature 001 Plan：第一阶段采用模块化单体，不引入 Spring Cloud，不直接部署微服务。
- 实际源码：多个独立服务启动骨架已经存在。

这是当前最严重的冲突，因为它会直接影响目录、依赖、测试、持久化和部署任务。不能靠继续写文档解决，必须由负责人明确“长期目标”和“当前实现基线”的关系，必要时修订 Constitution/ADR。

#### 冲突 2：Feature 路径

- 多份基础文档写 `specs/<feature>/`。
- 当前 Feature 实际位于 `docs/specs/001-core-business-model/`。
- `.specify/feature.json` 也指向 `docs/specs/...`。

工具当前能工作，但新 Feature 作者如果只读文档，很可能创建到错误目录。

#### 冲突 3：README 与实际仓库状态

- README 说当前尚未创建业务服务代码和构建骨架。
- 实际已有根 POM、12 个子模块声明、9 个启动类和 9 个上下文测试。

这会让新接手者误判项目状态。

#### 冲突 4：Plan 与实际源码结构

- Plan 要求建立 `platform/` 单体。
- 实际已存在多个 `*-service/` 目录，但没有 `platform/`。
- Tasks 又包含创建 `platform/` 的任务。

如果直接执行 `speckit-implement`，它很可能同时面对“已有服务骨架”和“创建单体平台”的两套结构。

#### 冲突 5：事件模型表述

- 当前 Plan 已明确不建立全局 `event` 模块，事件归属于产生事件的业务模块。
- Constitution/工程文档仍有跨服务公共事件、Outbox、common DTO 的泛化表述。
- 根 POM 存在 `common-dto`，但当前模块化单体的公开事件契约归属尚未形成项目级规则。

## 8. 当前 Feature 001 在整体项目中的位置

Feature 001 是当前唯一完整的业务 Feature，位置应定义为：

> **项目从治理/骨架阶段进入第一条可运行业务纵向切片之前的“核心 MVP 设计与实施准备 Feature”。**

它不是整个项目的最终架构，也不是完整支付平台；它负责验证：

- Catalog/SKU → Order → Transaction → Payment → PaymentAttempt。
- Payment Channel 抽象和 Mock Channel。
- 回调、幂等和 UNKNOWN 收敛。
- PaymentSucceeded → Fulfillment → Entitlement。
- Refund 的最小闭环。
- Mock/预置渠道账单驱动的基础对账。
- 只生成模拟结算结果、不真实出款的基础结算。
- 基础业务可观测性。

Feature 001 当前同时承担了三类工作：

1. 业务模型确认。
2. 第一阶段架构形态选择。
3. MVP 实施任务拆解。

这导致它的范围偏大。它应该作为“第一阶段 MVP Feature”，但不应该承担替代项目总体架构决策和项目 Roadmap 的职责。

当前状态：

- Spec：已形成，业务模型和边界较完整。
- Plan：已形成，但和 Constitution、ADR、根 POM 存在架构冲突。
- Tasks：已形成，任务较细，但任务路径基于 `platform/`，与实际服务骨架冲突。
- Code：尚未按 Feature 001 实现业务能力。
- 可运行验收：尚未具备。

因此，Feature 001 当前**不应直接进入实现**，至少要先完成架构基线裁决和任务路径重基线。

## 9. 当前最严重的 10 个治理问题

按对后续返工和错误决策的影响排序：

1. **架构基线冲突**：Constitution/ADR/根 POM 指向微服务，Feature 001 指向模块化单体，实际仓库处于两者混合状态。
2. **没有项目级 Roadmap**：Feature 001 看起来像整个项目，负责人无法判断阶段边界和下一步。
3. **没有唯一开发入口**：README、development-guide、feature 命令和 Spec Kit workflow 各自提供不同入口。
4. **文档路径没有收口**：`specs/<feature>/` 与实际 `docs/specs/<feature>/` 并存。
5. **实际状态与 README 不一致**：README 仍把已有 Maven/服务骨架描述为不存在。
6. **Plan 和源码结构不一致**：Plan 规划 `platform/`，仓库已经有 9 个服务目录；任务无法直接照做。
7. **长期架构决策没有版本迁移关系**：ADR-0001 没有被正式标记为历史、过渡或被新决策替代。
8. **Maven Wrapper、CI、Compose 缺失**：文档要求可复现构建和部署，但仓库没有对应入口。
9. **模块骨架先于架构收口生成**：多个服务已经存在，但没有业务代码和边界测试，增加了后续删除/合并成本。
10. **公共模块和事件契约边界未定**：`common-core`、`common-dto`、`common-mybatis` 已进入构建结构，但哪些内容可以共享、哪些必须留在业务模块没有足够的实施约束。

## 10. 推荐的最终项目结构

在负责人确认“第一阶段模块化单体、长期保留按领域拆分能力”后，推荐使用以下结构作为当前阶段基线：

```text
PaymentArch/
├── pom.xml                         # 当前阶段唯一父工程
├── mvnw / mvnw.cmd                 # 可复现构建入口
├── README.md                       # 项目状态、快速开始、当前迭代入口
├── CLAUDE.md                       # AI 入口和规则索引
├── .gitignore
├── .github/workflows/              # CI
│
├── docs/
│   ├── project-audit.md            # 本审计报告
│   ├── architecture.md             # 当前有效总体技术方案
│   ├── roadmap.md                  # 项目里程碑和阶段边界
│   ├── decisions/                  # ADR，或继续使用 docs/adr
│   ├── standards/                  # 工程规范，按主题拆分
│   ├── operations/                 # 本地、Compose、单机运行文档
│   └── specs/                      # Feature 文档唯一目录
│       └── 001-core-business-model/
│
├── platform/                       # 第一阶段唯一可运行应用
│   └── src/
│       ├── main/java/com/payment/platform/
│       │   ├── api/
│       │   ├── application/
│       │   ├── domain/
│       │   │   ├── merchant/
│       │   │   ├── catalog/
│       │   │   ├── order/
│       │   │   ├── transaction/
│       │   │   ├── payment/
│       │   │   ├── refund/
│       │   │   ├── fulfillment/
│       │   │   ├── entitlement/
│       │   │   ├── reconciliation/
│       │   │   └── settlement/
│       │   ├── infra/
│       │   └── config/
│       └── test/
│
├── common/                         # 只有稳定、跨模块且批准共享的内容
│   ├── common-core/
│   ├── common-dto/
│   └── common-mybatis/
│
└── deployment/
    └── docker-compose.yml
```

当前不推荐同时保留一套可独立启动的 `*-service/` 骨架和一套 `platform/` 单体，除非负责人明确它们分别代表“长期目标”和“当前实现”，并在目录和构建规则中清晰隔离。

## 11. 推荐的文档职责划分

### 11.1 权威层级

```text
Constitution
  → 当前有效总体架构方案 / ADR
    → Roadmap
      → Feature Spec
        → Feature Plan
          → Feature Tasks
            → 代码、测试、运行记录
```

### 11.2 单一职责

| 文档 | 只负责什么 | 不负责什么 |
|---|---|---|
| Constitution | 永久或长期工程原则、资金铁律、人类决策边界 | 不写当前 Feature 的文件路径和任务 |
| 总体架构方案 | 当前有效架构基线、模块边界、演进方式、运行形态 | 不替代 Feature 需求 |
| ADR | 一个重要决策及其背景、选项、后果和状态 | 不复制全部工程规范 |
| Roadmap | 阶段、里程碑、进入/退出条件、范围控制 | 不写具体类和任务 |
| README | 新人 5 分钟内了解项目当前状态和如何验证 | 不承载完整规则 |
| Feature Spec | 要解决什么问题、业务边界、验收标准 | 不决定具体文件结构 |
| Feature Plan | 本 Feature 如何落地、影响范围、设计和风险 | 不重新定义项目长期原则 |
| Feature Tasks | 可执行的最小任务及依赖 | 不写新的架构决策 |
| 工程规范 | 代码、测试、构建、日志、发布标准 | 不描述业务领域模型 |
| 领域 Skill | 某类风险的分析方法和专项检查清单 | 不成为高于 Constitution 的新规则 |
| Review 命令 | 按既定规则找问题并报告 | 不自动改变架构决策 |

### 11.3 推荐的路径收口

当前项目应选定一种并在所有文档、脚本、Skill 中统一。基于现状，建议继续使用：

```text
docs/specs/<feature>/
```

原因是 Feature 001 已经实际位于此处，迁移成本低。之后所有 Spec Kit 脚本状态、README、命令和开发指南都必须以这个路径为准。

## 12. 推荐的 AI/Spec Kit/Skill 职责划分

### Constitution

回答：什么绝不能违反？哪些决策必须由负责人确认？

只放长期原则：资金正确性、数据所有权、状态机、依赖方向、AI 红线、人类决策边界。

### CLAUDE.md

回答：AI 接手项目时先读什么、当前项目地图在哪里、常用命令是什么？

只做导航和硬规则摘要，不复制完整领域规则。

### Spec Kit

回答：这个 Feature 要什么、怎么设计、如何拆任务？

负责 Feature 生命周期产物，不负责替项目决定长期架构。

### `.claude/commands/`

回答：我现在要执行哪个项目动作？

建议保留：

- `feature`：只做项目级入口编排，调用 Spec Kit 阶段，不重新定义每个阶段。
- `review`：通用 Review。
- `payment-review`：资金专项 Review。
- `test`：测试执行和结果报告。

### `.claude/skills/`

回答：处理某类问题时，分析和检查的重点是什么？

- `architecture`：架构、边界、依赖、数据所有权和基础设施引入门槛。
- `payment-domain`：支付、退款、UNKNOWN、幂等、状态机和资金正确性。
- `observability`：指标、日志、Trace、审计和业务告警。
- `speckit-*`：Spec Kit 的阶段操作，不额外添加项目架构规则。

### ADR

回答：为什么做出某个重要选择？当前是否仍有效？

ADR-0001/0002 必须补充状态关系：是长期目标、当前阶段暂缓，还是已被新决策替代。未完成这一步前，不应让 AI 同时把它们和模块化单体当作无条件有效规则。

## 13. 推荐的迁移顺序

以下顺序的目标是先消除方向性风险，再恢复 Feature 001，不要求一次性重写全部文档：

1. **冻结实现入口**：暂停执行 Feature 001 的 `speckit-implement`，不继续新增服务、表、依赖或业务代码。
2. **负责人裁决架构基线**：明确“第一阶段模块化单体、长期是否保留微服务目标”，并决定 ADR/Constitution 的正式状态。
3. **建立总体架构方案**：补充当前有效架构、模块边界、当前目录、事件归属、持久化边界和未来拆分条件。
4. **建立项目 Roadmap**：写清治理阶段、MVP 阶段、Ledger 阶段、真实渠道阶段和服务拆分阶段的进入/退出条件。
5. **统一 Feature 路径**：在文档、命令、Skill 和 Spec Kit 状态中统一使用 `docs/specs/<feature>/`，或由负责人明确迁移到另一条路径。
6. **校准仓库骨架**：根据架构裁决，保留 `platform/` 或保留 `*-service/`，不要两套结构同时作为当前实现入口。
7. **校准根 POM 和依赖**：移除当前阶段不需要的 Spring Cloud/Nacos 等依赖，或明确它们只属于后续目标架构；补齐 Maven Wrapper。
8. **修正 README 和开发入口**：让新人看到的状态、启动方式和下一步与真实仓库一致。
9. **重新审阅 Feature 001 Plan/Tasks**：只修路径、模块映射和阶段依赖，不重新发明业务模型；确认任务能够对应实际目录。
10. **完成 Bootstrap 验收**：本地构建、最小启动、基础测试、Compose 和 CI 入口可验证后，才恢复 US1 实现。
11. **恢复 Feature 001**：优先完成 Catalog/Order/Transaction/Payment/Mock Channel/UNKNOWN/履约/权益，再进入退款、对账和模拟结算。
12. **每个里程碑做一次治理回检**：确认代码、Spec、Plan、Tasks 和 Roadmap 没有再次分叉。

## 明确结论

**如果我是这个项目的技术负责人，现在应该暂停开发 Feature 001，先做以下事情，完成后再恢复 Feature 开发：**

1. 先裁决当前阶段到底采用“模块化单体”，并明确它与长期微服务目标的关系；这项裁决需要正式更新 ADR/Constitution 的有效状态。
2. 建立一份当前有效的总体技术方案，解决 `platform/` 与多个 `*-service/` 并存的问题。
3. 建立项目 Roadmap，明确 Feature 001 只是第一阶段 MVP，而不是整个项目。
4. 统一 Feature 路径为实际使用的 `docs/specs/<feature>/`，修正所有工具和文档指针。
5. 依据架构裁决校准根 POM、服务骨架、依赖和构建入口，补齐 Maven Wrapper；当前不继续增加微服务、MQ 或真实资金能力。
6. 重新审阅并校准 Feature 001 的 Plan/Tasks，使每个任务都能对应实际目录和当前架构。
7. 让 README、开发指南、本地启动、Compose 和 CI 的描述与仓库事实一致。

完成以上 7 项，并通过“构建 + 最小启动 + 基础测试 + 文档路径 + 架构门禁”检查后，再恢复 Feature 001 的实现。当前最不应该做的事情，是直接运行完整 `speckit-implement`，因为它会把尚未裁决的两套架构同时推进，造成后续返工。
