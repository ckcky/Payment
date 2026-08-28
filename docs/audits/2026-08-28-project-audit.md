# PaymentArch 项目审计报告（代码级）

> **审计日期**：2026-08-28  
> **审计范围**：整个仓库代码、配置、依赖、测试、部署与执行进度（区别于 2026-08-26 的「治理/文档体系审计」，本报告聚焦**代码实现质量**）。  
> **审计基线**：工作树（含未提交改动），最后提交 `688381c`（2026-08-27）。  
> **方法**：逐项读取根 POM、Constitution、技术方案、Roadmap，并对 9 个服务 + 3 个公共库的实际源码做安全/质量/测试/性能/依赖多维扫描（315 个 Java 文件），关键 HIGH 结论已二次人工核验。  
> **权威事实源**：本报告结论若与 `docs/architecture/technical-solution.md`、`roadmap.md`、`.specify/memory/constitution.md` 冲突，以这三份为准；本报告仅记录代码现状。

---

## 0. 总体结论（TL;DR）

项目**架构纪律优秀、领域建模扎实、资金正确性基础牢固**，但**距离其自身 Constitution 的「生产级八性」仍有明确差距**。最突出的问题集中在四块：

1. **资金值对象 `Money` 形同虚设（High）** —— 已写好却零引用，全库用裸 `(long amountMinor, String currencyCode)` 元组承载金额，违反「金额必须封装 Money VO」铁律。
2. **安全基线整体缺位（High×4）** —— 无鉴权、无输入校验、回调无验签、`/resolve` 端点可伪造支付成功；且 DB 密码硬编码。
3. **事务内同步 RPC + 全链路无超时/熔断（High）** —— `createPaymentIntent` 在一个 `@Transactional` 内同时做了外部渠道调用与跨服务 RPC，DB 连接被网络调用长期占用，且 Feign 无任何超时/熔断配置。
4. **测试未用真实数据库（High）** —— 宪法要求 Testcontainers(MySQL) 集成测试，实际全部跑在 H2 兼容模式，资金一致性未被真实库验证。

**总评**：一个**高质量的学习型骨架/主链 MVP**，核心领域逻辑（状态机、幂等、UNKNOWN 收敛、复式记账前置）实现得相当认真；但「可部署 / 可观测落地 / 安全 / 真实测试」仍停留在文档承诺或 Phase 9/10 的待办中。建议在进入下一个资金敏感 Feature 前，优先收敛 P0（见第 9 节）。

---

## 1. 项目概述

### 1.1 定位

Java 21 + Spring Boot 3.5.15 + Spring Cloud 2025.0.3 + MyBatis-Plus 3.5.17 的 **Spring Cloud 微服务**学习项目（Commerce & Payment Platform）。目标不是 CRUD Demo，而是**真实地**实践订单、支付、履约、权益、对账、结算的边界与一致性。当前 MVP **不含真实资金**（Payment/Refund/Settlement 仅模拟业务事实，`ledger-service` 复式记账延后至 Phase 8）。

### 1.2 技术栈（已落地 / 已声明）

| 维度    | 现状                                         | 说明                                |
| ----- | ------------------------------------------ | --------------------------------- |
| JDK   | 21 LTS                                     | 父 POM `java.version=21`           |
| 框架    | Spring Boot 3.5.15 + Spring Cloud 2025.0.3 | BOM 统一，无版本漂移                      |
| ORM   | MyBatis-Plus 3.5.17                        | SQL 显式可控；mapper 全用 `#{}`          |
| 服务调用  | OpenFeign + LoadBalancer                   | **实际为硬编码 URL**，未接 Nacos 服务发现      |
| 注册/配置 | Nacos                                      | **声明于文档，代码中未引入依赖**                |
| 可观测   | Micrometer + Tracing                       | 业务指标 + 资金审计日志已落地（见下）              |
| 测试    | JUnit5 + Mockito + AssertJ                 | 集成测试用 **H2**（非声明的 Testcontainers） |
| 质量门禁  | Checkstyle + Spotless                      | **文档要求，CI 未接入、配置缺失**              |

### 1.3 模块清单与成熟度

| 模块                     | Java 文件 | 控制器 | 状态   | 备注                             |
| ---------------------- | ------- | --- | ---- | ------------------------------ |
| common-core            | 19      | —   | 已实现  | 含 Money、错误模型、幂等基元、Trace 拦截     |
| common-dto             | 12      | —   | 已实现  | 跨服务 DTO / RPC 契约               |
| common-mybatis         | 3       | —   | 已实现  | **零测试**；提供 `@Version` 乐观锁、审计填充 |
| merchant-service       | 9       | 1   | 骨架   | 内存仓储，无 DB/Schema               |
| catalog-service        | 21      | 1   | 部分实现 | Product/SKU/价格                 |
| order-service          | 35      | 2   | 已实现  | Order/Transaction 状态机          |
| payment-service        | 38      | 3   | 已实现  | 支付编排 + 渠道适配 + 回调 + UNKNOWN     |
| refund-service         | 27      | 2   | 已实现  | 退款编排                           |
| fulfillment-service    | 14      | 2   | 部分实现 | 履约                             |
| entitlement-service    | 13      | 3   | 部分实现 | 权益授予/撤销                        |
| reconciliation-service | 33      | 1   | 已实现  | 对账批次 + 匹配                      |
| settlement-service     | 31      | 1   | 已实现  | 结算批次 + 净额                      |

> 与 2026-08-26 治理审计「仅有 9 个启动壳、无业务代码」相比，项目已**实质性前进**：当前 315 个 Java 文件、主链 MVP 已跑通、Feature 001 已验收。治理审计中担心的「架构基线冲突」已通过技术解决方案收口为 Spring Cloud 微服务并解决。

---

## 2. 代码质量评估

### 2.1 分层与依赖方向 —— ✅ 良好

- **领域层零框架泄漏**：`domain/**` 包内无任何 Spring/MyBatis/JPA/Lombok 导入（grep 为空），满足「domain 不依赖框架层」。
- **依赖方向单向**：`infra` 仅 import `domain.*`；服务间只经 `common-dto/rpc` 与 `common-core` 交互，无跨服务内部包耦合、无跨服务直连 SQL（符合数据所有权边界）。
- ⚠ **中危（Med）**：`infra/client` 反向依赖 `application` 层 —— `settlement-service/.../infra/client/FeignReconciliationClient.java` 与 `fulfillment-service/.../infra/client/EntitlementFeignClient.java` 直接 `import ...application.ReconciliationClient/...`。端口/适配器混入 infra，应把端口接口下沉到 domain 或独立 `port` 包。

### 2.2 状态机集中化 —— ✅ 合规

每个聚合的状态变更都收敛到**带守卫的领域方法**，无散落 `setStatus`：

- `Payment.transitionTo(...)` + `requireStatus`（Payment.java:65-133）终态吸收冲突；
- `Order.confirm/markPaid/complete/cancel/close`（Order.java:67-142）；
- `Refund`（Refund.java:59-138）、`SettlementBatch`（SettlementBatch.java:65-150）、`Sku.activate/suspend/discontinue`（Sku.java:92-111）均经 `requireStatus` 守护。
- `setStatus(...)` 仅存在于 `*Entity`（持久化类），且仅被 Repository 映射调用 —— MyBatis 必然项，可接受。
- **建议（低优）**：统一为共享状态机基类，减少每个聚合重复守卫样板。

### 2.3 Money 值对象 —— 🔴 High（宪法级违背）

- `common-core/.../money/Money.java` 设计优秀：不可变、`long` 最小货币单位、`Math.addExact` 防溢出、币种校验、`RoundingMode.UNNECESSARY` 禁止静默舍入（Money.java:14-139）。
- **但它从未被使用**：全库源码对 `com.payment.common.money.Money` 的引用为 **0**（仅其自身测试在 `target/` 报告中出现）。金额一律以裸 `(long amountMinor, String currencyCode)` 元组散落：`Payment.java:26-27`、`Order.paidMinor/refundedMinor`、`Transaction.java:19`、`ChannelStatement.java:11`、`ReconciliationMatching.java:34-38` 直接比较 `p.amountMinor() == c.amountMinor()`。
- **后果**：币种与金额可分别被篡改、跨币种加减无编译期保护、Money 的溢出/舍入护栏形同虚设。虽用 `Math.addExact/subtractExact` 规避了溢出与 float/double（正向），但**未封装进 Money VO**，违反 Constitution「Money MUST be a Money value object, not a bare long」。
- **行动**：将全部 `(long, String)` 金额元组替换为 `Money`；优先改 Payment/Order/Refund/Settlement/Reconciliation 的金额字段与算术。

### 2.4 错误处理 —— ✅ 合规

- `BizException`/`SystemException` 分离；`GlobalExceptionHandler`（@RestControllerAdvice）统一兜底，返回含 traceId 的 `ApiError`。
- 无空 `catch {}`、无 `catch(Exception)` 后吞异常（grep 为空）。业务错误显式 `BizException.of(...)` 抛出。

### 2.5 DTO / 实体泄漏 —— ✅ 无泄漏

控制器返回 `*Response` DTO 并由领域对象构造；`api` 包内 `return *Entity` 为空。

### 2.6 代码重复 —— ⚠ 中/低危

全库 **21 个 `*Client.java`**（reconciliation 6、settlement 6、order 4…），各服务自维护调用对端的 Feign 客户端与请求/响应映射样板。建议抽公共 `clients` 模块或基于 `common-dto` 的共享 Feign 接口。

---

## 3. 架构合理性分析

### 3.1 与 Constitution 的契合度

| 宪法要求                             | 现状                               | 评价               |
| -------------------------------- | -------------------------------- | ---------------- |
| 领域边界（Order≠Payment≠Channel 等六条）  | 显式拆分，Channel 内聚于 payment-service | ✅                |
| 数据所有权（每服务独占 Schema）              | Schema 已规划 9 套，@Version 乐观锁      | ✅（运行时表未挂载，见 7.2） |
| 一致性：幂等 + 状态机 + UNKNOWN           | DB 唯一约束兜底 + 集中状态机 + UNKNOWN 路径完整 | ✅                |
| 禁止 2PC/XA，用 Saga+RPC+幂等          | 同步 RPC 编排，无 XA                   | ✅                |
| 资金金额用 long/BigDecimal + Money VO | long minor 正确，但 **Money VO 未用**  | 🔴 见 2.3         |
| 内部强一致 / 外部最终一致分层                 | 本地事务 + 跨服务幂等；无跨服务 MQ             | ✅                |

### 3.2 一致性设计亮点

- **幂等键由 DB 唯一约束兜底（非内存）**：`uk_payments_idempotency_key`、`uk_refunds_idempotency_key`、`uk_attempts_channel_reference` 等；`createPaymentIntent:61-104` 先回查、撞唯一约束后 `catch DuplicateKeyException` 再回查 —— 并发/重启重复请求 race-safe。
- **履约失败不回滚支付成功**：`createPaymentIntent:85-91` 对 `fulfillmentGateway.notifyPaymentSucceeded` 的异常 `catch(RuntimeException ignored)` —— 符合「后置失败不回写前序成功事实」。**但**该 swallow 同时吞掉所有 RuntimeException，且**无重试/告警/补偿触发**（见 4.3、6.3）。

### 3.3 架构风险点

- **同步 RPC 扇出无隔离**：`payment→fulfillment`、`payment→order`（`PaymentResultProcessor`）、`fulfillment→entitlement` 全为同步 Feign，且**全仓无 resilience4j / Feign 超时 / 熔断**（grep 0 命中）。任一下游挂起即拖垮调用方线程与连接。
- **事务内跨服务/外部调用**（见第 4 节，High）。
- **Nacos 已声明未引入**：服务发现与配置中心只在文档里，代码用硬编码 URL（`http://localhost:8084` 等），与「跨服务经注册中心」的架构描述不符。

---

## 4. 潜在安全风险

> 评分：🔴 High / 🟠 Medium / 🟢 Low。多数 HIGH 属于 Constitution「已声明但尚未实现」的安全能力（Roadmap 将其排在 Phase 9 Risk/Security），但作为审计必须如实标注其**当前确实缺位**。

### F1 🔴 全站无鉴权（Constitution §Security.3）

13 个 `pom.xml` 均无 `spring-boot-starter-security`/`oauth2`；代码中无 `SecurityFilterChain`/`@PreAuthorize`/`csrf()`。所有 `@RestController` 与跨服务 `*RpcController` 完全无认证。**任何未授权调用方可直接触发资金相关操作。**

### F2 🔴 可伪造支付成功的未鉴权端点（§Security.2 & .3）

`payment-service/.../api/PaymentController.java:51-55`

```java
@PostMapping("/{id}/resolve")
public PaymentResponse resolveUnknown(@PathVariable Long id, @RequestBody ResolveRequest request) {
    resolutionService.resolve(id, request.toResult());   // 原始 result("SUCCESS"/"FAILURE") 直转 ChannelResult
```

`ResolveRequest` 无任何鉴权/签名/来源校验；`resolve` 直接将支付翻转为 SUCCESS，进而触发下游履约/权益。**这是一个伪造回调原语** —— 即便当前是模拟资金，也应在 Phase 9 前至少加 admin 鉴权 + 权威查询约束。

### F3 🔴 全站无输入校验（§Security.3）

grep `@Valid`/`@Validated`/`@NotNull`/`jakarta.validation` 在 `*.java` 中 **0 命中**。DTO（`CreatePaymentRequest` 等）无约束，`amountMinor`/`currencyCode`/`orderId` 未经校验直达领域。无法阻止负数/零金额或非法币种进入资金路径。

### F4 🟠 密钥硬编码（§Security.1）

8 个 DB 服务 `application.yml` 硬编码 `username: root` / `password: root`（`payment-service/.../application.yml:7` 等同）；`deployment/docker-compose.yml:21` `MYSQL_ROOT_PASSWORD: root`、`:55` `GF_SECURITY_ADMIN_PASSWORD: admin`。无任何 `${ENV}` 占位，违反「配置与代码分离 / 外部化」。

### F5 🔴 渠道回调无签名/来源校验（§Security.2）

唯一渠道实现 `MockChannelAdapter`；`PaymentCallbackService.handleCallback` 直接消费已构造的 `ChannelResult`，**无签名/来源验证步骤**，无真实 HTTP 回调适配器。伪造回调与合法回调无法区分（预计 Phase 9 落地）。

### 安全正向项（保留并记录）

- **金额无浮点**：money 一律 `long` minor units；`float/double` 仅出现在 metrics 计数器，绝不用于金额。✓
- **无 SQL 注入**：所有 mapper 用 `#{}`，全仓 **0 处 `${}`**、0 处字符串拼接 SQL、0 处裸 JDBC。✓
- **无危险构造**：无 `Runtime.exec`/`ProcessBuilder`/`eval`/`ObjectInputStream`/`XStream`/路径穿越 `..`。✓
- **资金审计日志**用 `Long` 金额 + `StructuredAuditLogger.mask` 脱敏键。✓

---

## 5. 性能与并发问题

### 5.1 🔴 事务内同步 RPC / 外部调用（High）

`PaymentApplicationService.createPaymentIntent`（@Transactional，:59）在一个本地事务内依次：

1. DB 插入 payment + attempt（:69-81）
2. **`channel.charge(...)` 外部渠道调用**（:76-77）
3. DB 保存（:80-81）
4. **`fulfillmentGateway.notifyPaymentSucceeded(...)` 跨服务 RPC**（:87）

→ DB 连接被**外部网络调用**长时间占用。Channel 当前为 Mock 故无感，但一旦接真实渠道或下游变慢，连接池将在无超时配置下被耗尽（雪崩）。`PaymentResultProcessor.applyAndNotify`（:37）也未加事务，payment/attempt 两次 save 各自自动提交（可接受，但缺原子性）。

### 5.2 🔴 全链路无超时 / 熔断（High）

grep `feign/readTimeout/connectTimeout/resilience4j/@Retryable` 在 `application.yml` 中 **0 命中**。Feign 使用默认（极长/无）超时，无熔断、无重试退避。同步扇出任一节点挂起 → 调用方线程与连接被占满。

### 5.3 🔴 重试/退避机制缺失（High）

状态机/领域仅有 `PaymentAttempt.retryCount` 字段，**无任何自动重试实现**（无 `@Retryable`、无 backoff 配置、无相关测试）。Constitution 要求「仅对幂等调用自动重试，须有退避与上限」——当前未实现。

### 5.4 🟠 无分页 / 无上限查询（Medium）

全仓 0 处 `Pageable`/`LIMIT`。对账/结算批次遍历大表时存在全表扫描风险（当前数据量小，但属扩展性隐患）。

### 5.5 🟢 正向：并发正确性基础扎实

- 金额算术 `Math.addExact/subtractExact/multiplyExact`（`Money:75/81`、`Order:45/116`、`OrderItem:35`）防溢出。
- 所有聚合含 `@Version` 乐观锁（`BaseEntity:36`），防丢失更新；无 `synchronized`（合理，靠乐观锁）。
- 幂等键 DB 唯一约束 + `DuplicateKeyException` 回查，并发重复请求安全。

---

## 6. 依赖与配置审查

### 6.1 ✅ 版本治理优秀

父 POM `dependencyManagement` 统一 spring-boot 3.5.15 / spring-cloud 2025.0.3 / mybatis-plus 3.5.17 / testcontainers / archunit / springdoc；抽查所有服务 POM **无任何内联 `<version>`**，零版本漂移。JSON 仅 Jackson、无 Lombok、mysql(h2) scope 正确。

### 6.2 🔴 Nacos 缺位（High）

全仓 0 个 `spring-cloud-starter-alibaba-nacos-*` 依赖；OpenFeign 仅作 HTTP 客户端并配**硬编码 URL**。Constitution 要求注册/配置走 Nacos，实际未引入。属「声明未用」面，需补依赖或正式调整架构文档。

### 6.3 🔴 安全依赖缺位（High，已知延后）

任何 POM 均无 `spring-boot-starter-security`（同 F1）。Roadmap 将其排在 Phase 9，但 Constitution 已列为硬性要求 —— 建议要么实现、要么立 ADR 明确延后范围与时间点。

### 6.4 🔴 CI / Lint 不完整（High）

- `.github/workflows/verify.yml` 仅执行 `./mvnw -B verify`，**无 lint 阶段**。
- 全仓**无 `checkstyle.xml` / spotless 配置**，POM 也未挂载任一插件。Constitution 要求 Checkstyle + Spotless 在 CI 强制 —— 当前**未执行**。
- Maven Wrapper（`mvnw`/`mvnw.cmd`/`.mvn`）存在，可复现构建成立 ✅。

### 6.5 🔴 部署不完整（High）

- 无 `Dockerfile`（任一服务均无）。
- `deployment/docker-compose.yml` 仅含 `mysql`+`prometheus`+`grafana`，**无 9 个服务镜像、无 Nacos**（注释自承「镜像待补」）。与「9 服务 + MySQL」目标不符。
- **Schema 未挂载**：无 Flyway/Liquibase；`initdb` 仅建 8 个空库，业务表 DDL（`deployment/schema/*.sql`）标注「参考、不挂载不执行」。MyBatis-Plus 非 JPA，无 `ddl-auto` → **运行期表实际不存在**，仅 H2 测试能跑。

### 6.6 🟠 配置硬编码（Medium）

除 F4 的密码外，下游服务 URL（`reconciliation/refund/settlement` 的 `http://localhost:8084` 等）硬编码，未用服务发现或统一配置源。

### 6.7 ✅ 端口与金额/幂等 Schema

- 端口 `8081`(merchant)…`8089`(settlement) 全部唯一，无冲突 ✅。
- 金额列均为 `*_minor BIGINT`（最小货币单位），**无 FLOAT/DOUBLE** ✅。
- payment/refund/settlement 均有 `idempotency_key VARCHAR(128) NOT NULL` + `UNIQUE KEY`；order/catalog 等有对应业务唯一键 ✅。

---

## 7. 测试覆盖情况

### 7.1 每模块测试/主类比

| 模块                     | 测试类     | 主类       | 比        | 备注                    |
| ---------------------- | ------- | -------- | -------- | --------------------- |
| catalog-service        | 3       | 21       | 0.14     |                       |
| common-core            | 7       | 19       | 0.37     | 含 ArchUnit/ Money 不变式 |
| common-dto             | 1       | 12       | 0.08     |                       |
| common-mybatis         | **0**   | 3        | **0.00** | 🔴 乐观锁基础设施零测试         |
| entitlement-service    | 6       | 13       | 0.46     |                       |
| fulfillment-service    | 5       | 14       | 0.36     |                       |
| merchant-service       | 3       | 9        | 0.33     |                       |
| order-service          | 5       | 35       | 0.14     |                       |
| payment-service        | 11      | 38       | 0.29     | 最全                    |
| reconciliation-service | 6       | 33       | 0.18     |                       |
| refund-service         | 8       | 27       | 0.30     |                       |
| settlement-service     | 5       | 31       | 0.16     |                       |
| **合计**                 | **~60** | **~266** | **0.23** |                       |

- `@SpringBootTest` ×14（其中 9 个为仅加载上下文的薄测试 + 各 persistence 测试）。**0 个 `@DataJpaTest`/`@MybatisTest` 切片**。
- **🔴 Testcontainers：全仓 0 `@Container`**。所有持久化/集成测试用 **H2 MySQL 兼容模式**（`PaymentPersistenceTest:19` 等）。H2 与真实 MySQL 在锁等待、隔离级别、约束/JSON 行为上有差异，**资金一致性未被真实库验证**，违反 Constitution「集成测试用 Testcontainers(MySQL)」。
- ✅ ArchUnit 存在：`common-core/ModuleBoundaryTest`（common 不得反向依赖业务包、核心值对象框架无关）+ `MoneyInvariantTest`（禁 float/double 金额）—— 架构边界有自动化守护。

### 7.2 关键资金路径覆盖

| 关键路径            | 覆盖         | 证据                                                                                                   |
| --------------- | ---------- | ---------------------------------------------------------------------------------------------------- |
| 支付成功/失败/UNKNOWN | ✅          | `PaymentApplicationServiceTest` (T037/T018)                                                          |
| 重复回调幂等          | ✅          | `PaymentCallbackContractTest:33` `duplicateCallbackDoesNotPublishTwice`；`PaymentStateMachineTest:42` |
| 延迟回调 + 终态保护     | ✅（部分）      | `PaymentCallbackContractTest:14`；无显式「乱序」场景，建议补                                                       |
| 超时→UNKNOWN      | ✅          | `ChannelResult:24` 超时映射；`PaymentUnknownResolutionTest`                                               |
| 渠道失败            | ✅          | `PaymentApplicationServiceTest` 失败路径                                                                 |
| 重试 + 退避         | 🔴 **未覆盖** | 仅 `retryCount` 字段；无机制、无测试                                                                            |
| 退款幂等 + 超额/部分退款  | ✅          | `RefundApplicationServiceTest:35`、`RefundAmountInvariantTest`、`RefundScenarioTest:43`                |
| 结算批次幂等          | ⚠ 部分       | `SettlementBatchStateMachineTest`/`SettlementEligibilityTest`；未见显式「重跑批次」幂等测试                         |
| 对账匹配            | ✅          | `ReconciliationMatchingTest` + `ReconciliationSettlementRpcScenarioTest`                             |

**缺口**：① 真实库 Testcontainers 集成测试；② 重试/退避实现与测试；③ 显式「乱序回调」「结算批次重跑」「common-mybatis 乐观锁」测试。

---

## 8. 执行计划与当前进度

### 8.1 Roadmap（来自 `docs/architecture/roadmap.md`，v0.1，2026-08-26）

主链路 Phase 依赖图：`Phase0 Foundation → 001 Core Business Model → 002 Payment Reliability → 003 Refund → 004 Reconciliation → 005 Settlement → 006 Ledger → 007 Risk/Security → 008 Distributed Evolution`；可并行：`009 Observability Baseline`、`010 Delivery/CI-CD Baseline`。

### 8.2 当前实际状态（工作树，2026-08-27 末次提交）

- **Phase 0–2 基本完成**：主链 MVP 已交付，Feature `001-core-business-model` **已通过验收**（`docs/specs/001.../acceptance.md`），端到端 merchant→catalog→order→payment→fulfillment→entitlement 可跑通；退款/对账/结算已骨架化并接入指标。
- **当前 Feature**：`002-payment-order-callback`（支付成功回写订单/交易，spec 已写）＋ 并行 `009 Observability Baseline`（Swagger + Prometheus/Grafana 可视化，埋点已落地、可视化层进行中）。
- **下一个 Feature**：`002 Payment Reliability`（超时/UNKNOWN 收敛/重复乱序回调/有限重试）—— 这与本报告第 4–5 节的 HIGH 问题高度对应，**建议在 002 阶段一并补强重试/超时/熔断**。
- **阻塞**：无。

### 8.3 Git / 工作树状态（审计时点）

- 分支 `master`；最后提交 `688381c fix(order): default catalog Feign URL to port 8082`（2026-08-27）。
- **存在大量未提交改动**（约 50+ 文件 M/D）：涉及 CLAUDE.md、README.md、多个服务 `pom.xml` 与 `application.yml`、`common-dto` 契约、`deployment/schema/01-order-schema.sql`、`docs/*` 与 `docs/audits/2026-08-26-project-audit.md`、`docs/specs/001-*/` 多篇。**结论**：正处于某个 Feature 的中途、未清理提交，建议按 Constitution「完成功能后必须测试和 Review」补充提交与 PR。
- 历史提交显示渐进推进：observability 埋点（T070-T073）、refund intake 锁（H1-H3）、Feign trace 隔离、settlement/reconciliation 实现等，工程节奏健康。

---

## 9. 可落地的改进建议（按优先级）

### P0 —— 必须在下一个资金敏感 Feature 前收敛（High）

1. **启用 `Money` 值对象**：将 Payment/Order/Refund/Settlement/Reconciliation 的 `(long,String)` 金额元组替换为 `Money`，复用已有 `addExact`/`subtractExact`/`RoundingMode.UNNECESSARY` 护栏；保留并扩展 `MoneyInvariantTest`。
2. **补齐重试/退避与超时**：引入 resilience4j retry+backoff（仅对幂等调用），并在所有 Feign 客户端配置 `connectTimeout`/`readTimeout` + 熔断；补充对应测试。优先在 `002 Payment Reliability` 落地。
3. **把外部/跨服务调用移出 `@Transactional`**：`createPaymentIntent` 先本地事务提交支付事实，再（事务外）触发渠道/履约 RPC；避免 DB 连接被网络调用占用。可改为「提交后发内部领域事件/后台任务」。
4. **`/resolve` 端点加鉴权 + 约束**：至少 admin 鉴权 + 仅允许在「权威渠道查询后」收敛，杜绝任意成功伪造；并补回调签名校验骨架（为 Phase 9 做准备）。

### P1 —— 安全与部署基线（High/Med，多为 Constitution 已声明）

1. **引入 Spring Security / OAuth2**（或立 ADR 正式记录延后范围与时间点）；跨服务 RPC 至少 mTLS 或服务间鉴权。
2. **全站 Bean Validation**：控制器/DTO 加 `@Validated` + 金额/币种/订单号约束，挡住非法资金入参。
3. **外部化全部密钥**：`application.yml` 改为 `${DB_USER}`/`${DB_PASSWORD}`/`${DB_HOST}` 等，或接入 Nacos 配置中心（同时补 Nacos 依赖）。
4. **Schema 落地**：引入 Flyway/Liquibase 挂载 `deployment/schema/*.sql`，否则运行期无表、无法真正启动联调。
5. **CI 接入 Checkstyle + Spotless**：新增配置并在 `verify.yml` 增加 lint 阶段（或在 `mvnw verify` 中挂载插件），落实 Constitution 门禁。

### P2 —— 质量与可维护性（Med/Low）

1. **Testcontainers 替换 H2**：对 payment/refund/settlement/reconciliation 的持久化与集成测试，用真实 MySQL 容器验证唯一约束、乐观锁、隔离级别行为。
2. **收敛 `infra→application` 反向依赖**：把 Feign 端口接口下沉到 domain 或独立 `port` 包，恢复单向依赖。
3. **抽公共 Feign 客户端**：21 个 `*Client` 样板收敛为基于 `common-dto` 的共享客户端/映射。
4. **补 `common-mybatis` 测试**（乐观锁、审计字段填充）与分页/上限查询（对账、结算批次）。
5. **补齐 Dockerfile + docker-compose 服务定义**（含 Nacos 或标注部署未就绪）。
6. **建立项目级契约/事件版本策略**与公开 DTO 所有权规则；收敛文档中重复的规则表述（Constitution/Engineering-Standards/Skill 多处重复，建议单一事实源）。
7. **提交当前未提交的 Feature 改动**并走 Review，保持工作树整洁与可回溯。

---

## 10. 风险登记（摘要）

| 风险                     | 严重度    | 当前缓解            | 建议处置            |
| ---------------------- | ------ | --------------- | --------------- |
| Money VO 未用，金额裸元组      | High   | 用 addExact 防溢出  | P0-1            |
| 无重试/退避 + 无超时熔断         | High   | 仅 retryCount 字段 | P0-2（归入 002）    |
| 事务内同步 RPC 占连接          | High   | 下游当前快/为 Mock    | P0-3            |
| 全站无鉴权/校验/验签            | High   | 仅学习环境           | P0-4 / P1-5/6/7 |
| 测试跑 H2 非真实库            | High   | ArchUnit 守护边界   | P2-10           |
| Nacos 声明未用、URL 硬编码     | High   | 单机本地可跑          | P1-7 / P2-13    |
| Schema 未挂载，运行期无表       | High   | H2 测试可跑         | P1-8            |
| CI 无 lint              | High   | 本地约定            | P1-9            |
| 密钥硬编码                  | Medium | 仅本地             | P1-7            |
| infra→application 反向依赖 | Medium | 当前可编译           | P2-11           |
| 无分页/大表遍历               | Medium | 数据量小            | P2-12           |
| common-mybatis 零测试     | Medium | —               | P2-12           |

---

> **审计收尾**：本报告聚焦代码实现现状，已二次核验全部 High 结论。与 2026-08-26 治理审计相比，项目已从「骨架 + 文档冲突」推进到「主链 MVP 跑通、领域建模扎实」；剩余差距集中在**安全基线、真实测试、部署落地、Money VO 启用、重试/超时/熔断**五处，均在 Roadmap 的 Phase 7–10 或 002 Feature 的覆盖范围内，可按 P0→P1→P2 有序收敛。
