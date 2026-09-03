<a id="adr-0002"></a>
# ADR-0002: 技术栈选型

- **状态**：Accepted（已接受）
- **日期**：2026-08-26
- **决策者**：人类（项目 Owner）

## Context（背景）

确定 Spring Cloud 微服务架构（ADR-0001）后，需敲定语言、JDK、构建、ORM、注册配置中心等底座技术。

## Decision（决策）

| 维度 | 选择 | 理由 |
|---|---|---|
| 语言 | Java | 主流后端语言，生态成熟 |
| JDK | **Java 21 LTS** | 长期支持，Spring Boot 3.x 全面支持 |
| 框架 | **Spring Boot 3.x + Spring Cloud** | 主流企业级框架 |
| 构建 | **Maven** | 市场占比最高、约定优于配置、教程丰富 |
| ORM | **MyBatis / MyBatis-Plus** | SQL 显式可控，适合资金 / 复式记账 / 对账复杂查询 |
| 注册 + 配置中心 | **Nacos**（推荐默认，可替换 Eureka/Consul） | 国内主流，同时提供注册与配置能力 |
| 服务调用 | **Spring Cloud OpenFeign + LoadBalancer** | 声明式服务间调用 |
| API 网关 | **Spring Cloud Gateway** | 响应式网关 |
| 熔断 | Resilience4j 或 Sentinel（**延迟到需要时再引入**） | 弹性容错，非首日必需 |
| 可观测 | **Micrometer + Micrometer Tracing** | 指标与链路追踪 |

## Alternatives（备选方案）

- **Gradle**：更灵活、增量构建快，但学习曲线陡，未选。
- **JPA / Hibernate**：抽象更高，但复杂资金查询可控性弱、懒加载风险，未选。
- **Java 17 LTS**：同样 LTS，21 是更新的主流 LTS，选 21。
- **Eureka / Consul**：可选注册中心；选 Nacos 因同时覆盖配置中心且国内主流。
- **分布式事务 Seata**：可选；但为学习目的先**手写 Saga / Outbox / 幂等**，理解原理后再评估是否引入。

## Consequences（后果）

- 依赖 Spring Boot 3.x 的 Java 17+ 要求（Java 21 满足）。
- MyBatis 需维护 XML mapper，换取 SQL 完全可控。
- Nacos 引入运行时依赖，需本地或容器化部署。
- 熔断组件延迟引入，早期先关注业务正确性与幂等。

## 关联

- ADR-0001：微服务架构。
- `docs/guides/engineering-standards.md`：工程规范（落地的具体约束）。
