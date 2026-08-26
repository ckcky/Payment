# 项目目录结构（Project Structure）

> 对应交付物 B。基于 ADR-0001（微服务）与 ADR-0002（技术栈）。本文是**目标结构**，P0 初始化阶段先建骨架，业务模块随 SDD 阶段逐步填充，不一次性生成空模块。

## 总体结构（Maven 多模块单仓库 Monorepo）

采用**单一 Git 仓库 + Maven 多模块**：一个父 POM 统一管理版本与依赖，每个服务一个 Maven 模块。共享代码抽为 `common-*` 模块。

```
PaymentArch/
├── pom.xml                      # 父 POM：dependencyManagement 统一版本
├── mvnw / mvnw.cmd              # Maven Wrapper（锁定 Maven 版本）
├── .gitignore
├── README.md
├── docs/                        # 工程文档（见 docs/README.md）
│   ├── architecture/             # 总体架构、项目结构、Roadmap
│   ├── adr/                      # 重要架构决策（含索引 README）
│   ├── guides/                   # 工程规范、开发指南、AI 工作流
│   ├── deployment/               # 部署与运行说明
│   ├── audits/                   # 一次性审计报告（带日期）
│   └── specs/                    # Feature 文档
│
├── .specify/                     # Spec Kit：宪法、模板、脚本、工作流
│   ├── memory/constitution.md
│   └── templates/
│
├── gateway/                     # 接入层：Spring Cloud Gateway（本 MVP 延后）
│
├── common/                      # 共享库（被服务依赖，不独立部署）
│   ├── common-core/             # 通用工具、异常、统一返回体、结果码
│   ├── common-dto/              # 跨服务 RPC DTO 定义
│   └── common-mybatis/          # MyBatis 通用配置、拦截器、审计字段
│
├── merchant-service/            # 商户
├── catalog-service/             # 商品 / SKU
├── order-service/               # 订单 / 交易
├── payment-service/             # 支付编排 + 渠道适配
├── refund-service/              # 退款
├── fulfillment-service/         # 履约
├── entitlement-service/         # 权益
├── ledger-service/              # 复式记账（资金核心，本 MVP 延后）
├── reconciliation-service/      # 对账
└── settlement-service/          # 结算
```

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
    │       ├── application.yml               # 配置（含 Nacos 引用）
    │       └── mapper/                       # MyBatis XML
    └── test/
        └── java/                             # 单元 + 集成测试
```

**分层依赖方向（单向）**：`api → application → domain ← infra`。`domain` 不依赖任何层；`infra` 实现 `domain` 声明的仓储接口（依赖倒置）。禁止 `domain` 反向依赖 `infra` 或 `api`。

## 关键约定

1. **包名**：统一 `com.payment.<service>.<layer>`，禁止 `com.payment.common` 之外随意新建顶层包。
2. **渠道适配**：`application/channel`（接口）与 `infra/channel`（实现）分离，落实 Constitution §2.3 的 Payment ≠ Channel。
3. **common 模块**：只放**跨服务共享**的稳定契约（DTO、事件、结果码），不放业务逻辑；业务逻辑各服务自持。
4. **不建空模块**：一个服务只在对应 SDD 阶段启动时才创建，避免一堆空壳目录。
