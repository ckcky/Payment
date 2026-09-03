# 项目目录结构（Project Structure）

> 对应交付物 B。基于 ADR-0001（微服务）与 ADR-0002（技术栈）。本文同时描述**目标结构**与**已落地的实际结构**：P0 骨架已建，10 个业务服务与公共库随 SDD 阶段逐步填充并投产。文档治理约定见第 4 节。

## 总体结构（Maven 多模块单仓库 Monorepo）

采用**单一 Git 仓库 + Maven 多模块**：一个父 POM 统一管理版本与依赖，每个服务一个 Maven 模块。共享代码抽为 `common-*` 模块。

```
PaymentArch/
├── pom.xml                      # 父 POM：dependencyManagement 统一版本
├── mvnw / mvnw.cmd              # Maven Wrapper（锁定 Maven 版本）
├── .gitignore
├── README.md                    # 仓库总览与快速开始
├── CHANGELOG.md                 # 版本化变更记录（2026-09-03 起）
├── docs/                        # 工程文档（见 docs/README.md）
│   ├── architecture/            # 总体架构、项目结构、Roadmap、systems/（10 篇系统设计）
│   │   └── systems/             # 每服务一篇系统设计（含 ledger-service.md）
│   ├── adr/                     # 重要架构决策（含索引 README，ADR-0001~0058）
│   ├── guides/                  # 工程规范、开发指南、AI 工作流
│   ├── deployment/              # 部署与运行说明（含 schema/、performance/）
│   ├── specs/                   # Feature 文档
│   └── archive/                 # 已归档的历史文档（带日期，不再作为权威事实源）
│       └── audits/              # 历史审计报告（2026-08-26/28/30，Status: archived/superseded）
│
├── .specify/                     # Spec Kit：宪法、模板、脚本、工作流
│   ├── memory/constitution.md   # 最高宪法（v2.3.0）
│   └── templates/
│
├── gateway/                     # 接入层：Spring Cloud Gateway（本 MVP 延后）
│
├── common/                      # 共享库（被服务依赖，不独立部署）
│   ├── common-core/             # 通用工具、异常、统一返回体、结果码、可观测
│   ├── common-dto/              # 跨服务 RPC DTO 定义（含 PostingRequest/Response）
│   └── common-mybatis/          # MyBatis 通用配置、拦截器、审计字段
│
├── merchant-service/            # 商户
├── catalog-service/             # 商品 / SKU（含 SkuCache + Redis）
├── order-service/               # 订单 / 交易（强幂等键 + 超时库存释放 ZSet 时间轮）
├── payment-service/             # 支付编排 + 渠道适配（端口 8084）
├── refund-service/              # 退款（冲正记账接入账本）
├── fulfillment-service/         # 履约
├── entitlement-service/         # 权益
├── ledger-service/              # 复式记账（资金核心，端口 8090，已实现、已投产）
├── reconciliation-service/      # 对账（状态机已接线，period 全程参与）
└── settlement-service/          # 结算（净额记账接入账本）
```

> **版本库治理说明**：
> - `.workbuddy/`（含 `memory/` 工作日志）已按治理决议**整体退出版本库**（磁盘保留，git 不跟踪），避免 AI 工作记忆污染工程变更历史。
> - `catalog-service` 与 `order-service` 下的 `src/.../infra/redis/` 为未跟踪目录（R4 待负责人裁决：是否纳入版本库），当前刻意不提交。
> - 历史审计报告统一归档至 `docs/archive/audits/`，正文标注 `Status: archived / superseded`，不再作为权威事实源。

## 单服务内部结构（以 payment-service 为例）

每个业务服务统一采用**分层 + 按领域分包**，包根为 `com.payment.<service>`：

```
payment-service/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/payment/payment/
    │   │   ├── PaymentApplication.java      # 启动类（@SpringBootApplication）
    │   │   ├── api/                          # 对外接口层：Controller + DTO + 入参校验
    │   │   ├── application/                  # 应用服务层：用例编排、事务边界、RPC 客户端
    │   │   │   └── channel/                  # 渠道接口（Payment ≠ Channel 边界）
    │   │   ├── domain/                       # 领域模型：实体、值对象、领域服务、状态机
    │   │   ├── infra/                        # 基础设施：Repository 实现、MyBatis mapper、RPC 客户端
    │   │   │   └── channel/                  # 渠道具体实现（Mock/支付宝/微信等适配器）
    │   │   └── config/                       # 装配配置
    │   └── resources/
    │       ├── application.yml               # 配置（含 Nacos 引用，[目标] 暂未启用）
    │       └── mapper/                       # MyBatis XML
    └── test/
        └── java/                             # 单元 + 集成测试（H2 MySQL 兼容模式）
```

**分层依赖方向（单向）**：`api → application → domain ← infra`。`domain` 不依赖任何层；`infra` 实现 `domain` 声明的仓储接口（依赖倒置）。禁止 `domain` 反向依赖 `infra` 或 `api`。

## 关键约定

1. **包名**：统一 `com.payment.<service>.<layer>`，禁止 `com.payment.common` 之外随意新建顶层包。
2. **渠道适配**：`application/channel`（接口）与 `infra/channel`（实现）分离，落实 Constitution §2.3 的 Payment ≠ Channel。
3. **common 模块**：只放**跨服务共享**的稳定契约（DTO、事件、结果码），不放业务逻辑；业务逻辑各服务自持。
4. **不建空模块**：一个服务只在对应 SDD 阶段启动时才创建，避免一堆空壳目录。
5. **文档分层（Diátaxis）**：架构/ADR/指南/运维/Spec 分目录；一次性审计报告归档至 `docs/archive/audits/`，权威决策以 `docs/adr/README.md` 跳转表与 `docs/architecture/technical-solution.md` §9 ADR 追溯索引为准。
6. **账本唯一事实源**：所有资金变动经 `ledger-service`（端口 8090），业务服务不得自记资金（Constitution §II / ADR-0008）。
