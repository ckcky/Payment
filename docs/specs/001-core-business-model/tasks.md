---

description: "Commerce & Payment Platform MVP 可执行任务清单"
---

# Tasks: Commerce & Payment Platform MVP

**Input**: `docs/specs/001-core-business-model/` 下的 plan.md、spec.md、research.md、data-model.md、contracts/ 和 quickstart.md

**前置条件**: 开始前必须完成 Plan 和业务模型确认。本清单只描述实现任务，不改变已确认的领域边界、基数或状态机。

**组织方式**: 任务按 Setup、Foundational 和用户故事组织。US1 是最小可演示闭环；US2、US3 在 US1 的稳定业务事实之上增量交付。所有服务独立部署，跨服务使用同步 HTTP/RPC。

## 架构基线（已确认：微服务）

> 所有任务按 Spring Cloud 微服务执行。单机部署只表示多个独立服务运行在同一台服务器上；服务使用不同端口和独立 Schema。跨服务通过同步 HTTP/RPC，不使用跨服务异步事件或 MQ。

| 业务领域 | 微服务模块 | 包根 `com.payment.<service>` |
|---|---|---|
| Merchant | `merchant-service` | `com.payment.merchant` |
| Catalog | `catalog-service` | `com.payment.catalog` |
| Order + Transaction | `order-service`（Order 1:1 Transaction，ADR-0001） | `com.payment.order` |
| Payment + Channel | `payment-service`（Payment/Attempt/Channel 内部） | `com.payment.payment` |
| Refund | `refund-service` | `com.payment.refund` |
| Fulfillment | `fulfillment-service` | `com.payment.fulfillment` |
| Entitlement | `entitlement-service` | `com.payment.entitlement` |
| Reconciliation | `reconciliation-service` | `com.payment.reconciliation` |
| Settlement | `settlement-service` | `com.payment.settlement` |
| 稳定公共契约 | `common/common-core` | `com.payment.common.core` |
| 跨服务 DTO/事件契约 | `common/common-dto` | `com.payment.common.dto` |
| MyBatis 通用配置/审计 | `common/common-mybatis` | `com.payment.common.mybatis` |
| `PlatformApplication`（单体启动） | 各服务独立 `@SpringBootApplication` | — |
| （不建） | `gateway`、`ledger-service` | 本 MVP 延后 |

**关键约束**：①跨模块进程内调用 → 跨服务同步 HTTP/RPC；②单个物理数据库 → 按服务独立 Schema，禁止跨 Schema 直连；③各服务使用 `common-core` 中稳定的错误/基础契约；④服务边界测试 → RPC 契约、数据所有权和服务独立启动测试；⑤本阶段不引入 MQ 或跨服务异步事件。

## Phase 1: Setup（项目初始化）

**目标**: 建立多个可独立启动的微服务和最小本地运行入口。

- [X] T001 创建 Maven 父工程和 `pom.xml`，统一 Java 21、Spring Boot、MyBatis/MyBatis-Plus、Micrometer、JUnit 5、Mockito、AssertJ 版本。
- [X] T002 创建各 `*-service/pom.xml` 和对应 `src/main/java/com/payment/<service>/*Application.java`，验证每个服务可以独立启动。
- [X] T003 [P] 创建各 `*-service/src/main/resources/application.yml` 和本地配置样例，为每个服务分配不同端口和独立 Schema。
- [X] T004 [P] 创建各 `*-service/src/test/java/` 下的上下文测试，验证服务应用上下文可以独立加载。
- [X] T005 [P] 创建 `docs/deployment/docker-compose.yml`，提供多个独立服务、不同端口和最小数据库依赖的单机启动入口。
- [X] T006 [P] 更新 `README.md`，记录本地启动、Docker Compose 启动和当前 MVP 不包含真实资金记账的边界，文件路径：`README.md`。
- [X] T007 配置 `.gitignore`、`mvnw`、`mvnw.cmd` 和 `pom.xml`，确保 `mvnw verify` 能成为统一验证入口，文件路径：`.gitignore`、`mvnw`、`mvnw.cmd`、`pom.xml`。

**检查点**: `mvnw verify` 能执行，多个服务能本地独立启动，Compose 文件可被解析；不创建微服务集群、Kubernetes 或 MQ 配置。

## Phase 2: Foundational（基础能力）

**目标**: 建立所有用户故事依赖的服务边界、公共业务值对象、RPC 契约、错误处理和持久化约定。

- [X] T008 确认 `merchant-service`、`catalog-service`、`order-service`、`payment-service`、`refund-service`、`fulfillment-service`、`entitlement-service`、`reconciliation-service`、`settlement-service` 的领域目录和服务边界，并确保服务无未批准的直接依赖。
- [X] T009 [P] 在 `common/common-core/src/main/java/com/payment/common/core/money/Money.java` 封装金额和币种并拒绝浮点金额计算。
- [X] T010 [P] 在 `common/common-core/` 定义稳定的跨服务 RPC 基础契约元数据（请求身份、关联 ID、版本），不创建全局业务事件模块。
- [X] T011 [P] 在各服务的 `application/` 目录定义本服务公开 RPC 用例边界和调用适配器，不创建跨服务异步事件发布器。
- [X] T012 [P] 在 `common/common-core/src/main/java/com/payment/common/core/idempotency/IdempotencyRegistry.java` 提供最小幂等能力：按业务作用域和幂等键记录首次结果，并对重复请求返回原结果或当前状态；不扩展为通用幂等框架。
- [X] T013 [P] 在各服务 `src/main/java/com/payment/<service>/api/error/` 提供全局异常处理、业务错误和系统错误模型，统一返回可解释错误。
- [X] T014 [P] 在 `common/common-mybatis/README.md` 记录持久化约定：每个服务使用独立 Schema、服务只能访问自己的 Schema、关键事实需要可追溯；不创建 Repository Framework。
- [X] T015 [P] 在各服务的 `src/main/java/com/payment/<service>/infra/observability/` 建立关联 ID、结构化日志和业务指标基座。
- [X] T016 [P] 在各服务的 `src/test/java/` 建立服务边界测试，验证服务不得跨 Schema 或访问其他服务内部数据。
- [X] T017 [P] 在 `common/common-core/src/test/java/` 验证金额、币种和禁止浮点金额规则。
- [X] T018 在各资金服务的 `src/test/java/` 验证最小幂等能力：同一业务作用域和幂等键重复请求返回同一结果。
- [X] T019 在 `common/common-dto/src/test/java/` 验证稳定 RPC 契约元数据，不创建跨服务事件契约。

**检查点**: 基础测试通过；各服务可以独立声明领域规则、数据访问和本地事实；跨服务只通过公开同步 RPC，没有跨服务异步事件或全局 `event` 业务模块。

## Phase 3: User Story 1 - 完成商品购买生命周期（Priority: P1，MVP）

**目标**: 通过多个独立服务和同步 RPC 走通 Product/SKU → Order → Transaction → Payment → PaymentAttempt → Channel → PaymentSucceeded → Fulfillment → Entitlement，并覆盖 UNKNOWN 和重复回调。

**独立验收**: 使用一个 Merchant、一个可售 SKU 和 Mock Channel，完成成功支付、UNKNOWN 收敛、重复请求和 RPC 触发的履约/权益发放；Payment、Fulfillment、Entitlement 状态独立且没有重复业务动作。

### US1 测试任务（先写测试）

- [X] T020 [P] [US1] 在 `order-service/src/test/java/com/payment/order/scenario/SuccessfulPurchaseScenarioTest.java` 编写成功购买端到端 RPC 场景，先验证失败，再实现业务。
- [X] T021 [P] [US1] 在 `catalog-service/src/test/java/com/payment/catalog/domain/CatalogInvariantTest.java` 编写 Product/SKU 可售性和价格快照约束测试。
- [X] T022 [P] [US1] 在 `order-service/src/test/java/com/payment/order/domain/OrderStateMachineTest.java` 编写订单状态转换、取消和订单金额约束测试。
- [X] T023 [P] [US1] 在 `payment-service/src/test/java/com/payment/payment/domain/PaymentStateMachineTest.java` 编写 Payment、PaymentAttempt 状态转换和终态保护测试。
- [X] T024 [P] [US1] 在 `payment-service/src/test/java/com/payment/payment/contract/PaymentChannelContractTest.java` 编写 Channel Adapter 与 Mock Channel 契约测试。
- [X] T025 [P] [US1] 在 `payment-service/src/test/java/com/payment/payment/contract/PaymentCallbackContractTest.java` 编写成功、失败、重复、延迟和不完整回调契约测试。
- [X] T026 [P] [US1] 在 `payment-service/src/test/java/com/payment/payment/integration/PaymentUnknownResolutionTest.java` 编写超时进入 UNKNOWN、查询/回调收敛和只触发一次履约 RPC 的集成测试。
- [X] T027 [P] [US1] 在 `fulfillment-service/src/test/java/com/payment/fulfillment/integration/FulfillmentEntitlementRpcFlowTest.java` 编写 Payment 成功后 RPC 履约、权益授予和失败恢复测试。

### US1 领域与应用实现

- [X] T028 [P] [US1] 在 `merchant-service/src/main/java/com/payment/merchant/domain/` 实现 Merchant 最小实体、有效状态和结算资格规则。
- [X] T029 [P] [US1] 在 `catalog-service/src/main/java/com/payment/catalog/domain/` 实现 Product、SKU、价格引用和交付定义，限制只有可售 SKU 才能下单。
- [X] T030 [P] [US1] 在 `order-service/src/main/java/com/payment/order/domain/` 实现 Order、OrderItem、PriceSnapshot 和订单状态机，固定 Order 1:1 Transaction。
- [X] T031 [P] [US1] 在 `order-service/src/main/java/com/payment/order/domain/` 实现订单金额、已支付金额、已退款金额和订单快照不变量。
- [X] T032 [US1] 在 `order-service/src/main/java/com/payment/order/domain/transaction/` 实现 Transaction 领域模型和状态机，固定 Transaction 1:1 Payment；依赖 T030。
- [X] T033 [P] [US1] 在 `payment-service/src/main/java/com/payment/payment/domain/` 实现 Payment、PaymentResult 和支付状态机；Payment 只保存平台支付意图与平台状态。
- [X] T034 [P] [US1] 在 `payment-service/src/main/java/com/payment/payment/domain/` 实现 PaymentAttempt，记录每次渠道交互、渠道引用和 UNKNOWN 信息。
- [X] T035 [US1] 在 `payment-service/src/main/java/com/payment/payment/application/channel/PaymentChannel.java` 定义渠道抽象，在 `payment-service/src/main/java/com/payment/payment/infra/channel/MockChannelAdapter.java` 实现 Mock Channel；依赖 T033、T034。
- [X] T036 [US1] 在 `order-service/src/main/java/com/payment/order/application/OrderApplicationService.java` 实现订单创建、SKU RPC 校验和价格快照；依赖 T029-T031。
- [X] T037 [US1] 在 `payment-service/src/main/java/com/payment/payment/application/PaymentApplicationService.java` 实现支付意图创建、PaymentAttempt 创建和幂等受理；依赖 T032-T035。
- [X] T038 [US1] 在 `payment-service/src/main/java/com/payment/payment/application/PaymentCallbackService.java` 实现回调去重、延迟保护、成功/失败更新和 UNKNOWN 处理；依赖 T034、T035、T037。
- [X] T039 [US1] 在 `payment-service/src/main/java/com/payment/payment/application/PaymentUnknownResolutionService.java` 实现查询/权威回调收敛 UNKNOWN，并保证只触发一次履约 RPC；依赖 T038。

### US1 履约、权益和接口

- [X] T040 [P] [US1] 在 `fulfillment-service/src/main/java/com/payment/fulfillment/domain/` 实现 Fulfillment、FulfillmentItem 和履约状态机。
- [X] T041 [P] [US1] 在 `entitlement-service/src/main/java/com/payment/entitlement/domain/` 实现 Entitlement、Grant、Consumption 和权益状态机。
- [X] T042 [US1] 在 `fulfillment-service/src/main/java/com/payment/fulfillment/api/PaymentSuccessRpcController.java` 接收 payment-service 的成功支付 RPC 并创建幂等履约任务；依赖 T033、T040。
- [X] T043 [US1] 在 `entitlement-service/src/main/java/com/payment/entitlement/api/FulfillmentCompletedRpcController.java` 接收履约完成 RPC 并授予权益；依赖 T040、T041、T042。
- [X] T044 [US1] 在 `order-service/src/main/java/com/payment/order/api/`、`payment-service/src/main/java/com/payment/payment/api/` 实现订单创建、支付意图、渠道回调、支付查询和 UNKNOWN 收敛接口；依赖 T036-T039。
- [X] T045a [US1] 在 `catalog-service/src/main/java/com/payment/catalog/infra/persistence/` 为 Catalog 接入模块自有持久化实现和历史追踪；依赖 T029。
- [X] T045b [US1] 在 `order-service/src/main/java/com/payment/order/infra/persistence/` 为 Order 和 Transaction 接入模块自有持久化实现和历史追踪；依赖 T030-T032。
- [X] T045c [US1] 在 `payment-service/src/main/java/com/payment/payment/infra/persistence/` 为 Payment 和 PaymentAttempt 接入模块自有持久化实现、渠道引用和回调历史追踪；依赖 T033-T039。
- [X] T045d [US1] 在 `fulfillment-service/src/main/java/com/payment/fulfillment/infra/persistence/` 为 Fulfillment 接入模块自有持久化实现和履约历史追踪；依赖 T040、T042。
- [X] T045e [US1] 在 `entitlement-service/src/main/java/com/payment/entitlement/infra/persistence/` 为 Entitlement 接入模块自有持久化实现和授予历史追踪；依赖 T041、T043。
- [X] T046 [US1] 在 `payment-service/src/main/java/com/payment/payment/infra/channel/` 实现 Mock Channel 的成功、失败、超时和不完整响应场景；依赖 T035。
- [X] T047 [US1] 在 `order-service/src/test/java/com/payment/order/scenario/SuccessfulPurchaseScenarioTest.java` 完成 T020 的实现验证，并运行 `mvnw test` 验证 US1 独立 RPC 闭环；依赖 T044-T046。

**检查点**: US1 可独立演示；支付成功只发布一次；UNKNOWN 在权威查询/回调后收敛；履约和权益失败不回写 Payment 成功事实。

## Phase 4: User Story 2 - 支持退款生命周期（Priority: P2）

**目标**: 通过同步 RPC 完成部分/全部退款、退款幂等、渠道退款模拟、UNKNOWN 和履约/权益后处理。
**依赖**: US1 完成，尤其是 Payment、PaymentAttempt、Fulfillment、Entitlement 的边界稳定。

### US2 测试任务（先写测试）

- [X] T048 [P] [US2] 在 `refund-service/src/test/java/com/payment/refund/domain/RefundStateMachineTest.java` 编写退款状态转换、部分退款和关闭规则测试。
- [X] T049 [P] [US2] 在 `refund-service/src/test/java/com/payment/refund/domain/RefundAmountInvariantTest.java` 编写可退款金额、累计退款和币种一致性测试。
- [X] T050 [P] [US2] 在 `refund-service/src/test/java/com/payment/refund/integration/RefundScenarioTest.java` 编写成功退款、重复退款、退款 UNKNOWN 和退款后处理 RPC 集成测试。

### US2 实现任务

- [X] T051 [P] [US2] 在 `refund-service/src/main/java/com/payment/refund/domain/` 实现 Refund、RefundItem、RefundDecision 和退款状态机。
- [X] T052 [US2] 在 `refund-service/src/main/java/com/payment/refund/application/RefundApplicationService.java` 实现退款资格判断、部分/全部退款和退款幂等；依赖 T051。
- [X] T053 [US2] 在 `payment-service/src/main/java/com/payment/payment/application/PaymentRefundService.java` 实现 Payment 内部退款尝试和 Mock Channel 退款结果；依赖 T035、T052。
- [X] T054 [US2] 在 `refund-service/src/main/java/com/payment/refund/application/RefundRpcCallbackService.java` 实现退款回调 RPC 去重、UNKNOWN 收敛和只确认一次退款成功；依赖 T053。
- [X] T055 [US2] 在 `refund-service/src/main/java/com/payment/refund/application/RefundPostProcessingRpcClient.java` 通过 RPC 请求退款成功后的履约/权益后处理，禁止直接修改其他服务内部状态；依赖 T054。
- [X] T056 [US2] 在 `refund-service/src/main/java/com/payment/refund/api/` 实现退款申请、退款查询和退款回调接口；依赖 T052-T054。
- [X] T057 [US2] 在 `refund-service/src/main/java/com/payment/refund/infra/persistence/` 实现 Refund 服务自有持久化和幂等追踪；依赖 T051-T055。
- [X] T058 [US2] 在 `refund-service/src/test/java/com/payment/refund/integration/RefundScenarioTest.java` 完成 T050 的实现验证，并运行 `mvnw test` 验证 US2 不破坏 US1；依赖 T056、T057。

**检查点**: 部分/全部退款可追踪；重复退款不产生第二次资金动作；未知退款不被当作失败或成功；退款成功后的权益处理保持独立。

## Phase 5: User Story 3 - 建立基础对账与结算（Priority: P3）

**目标**: 使用 Mock/预置渠道账单比对平台 Payment/Refund 事实，记录差异，并仅对合格事实生成最小结算批次和模拟结果，不执行真实出款。

**依赖**: US1 和 US2 完成；当前只模拟资金业务事实，不实现真实 Ledger 记账、真实资金划转或真实结算出款。

### US3 测试任务（先写测试）

- [X] T059 [P] [US3] 在 `reconciliation-service/src/test/java/com/payment/reconciliation/domain/ReconciliationMatchingTest.java` 编写基于 Mock/预置渠道账单的一致、金额差异、状态差异、平台独有和渠道独有记录测试。
- [X] T060 [P] [US3] 在 `settlement-service/src/test/java/com/payment/settlement/domain/SettlementEligibilityTest.java` 编写未确认事实、重大差异和重复商户周期批次约束测试。
- [X] T061 [P] [US3] 在 `reconciliation-service/src/test/java/com/payment/reconciliation/integration/ReconciliationSettlementRpcScenarioTest.java` 编写基础对账、差异处理、结算批次和结算 UNKNOWN RPC 集成测试。

### US3 实现任务

- [X] T062 [P] [US3] 在 `reconciliation-service/src/main/java/com/payment/reconciliation/domain/` 实现 ReconciliationBatch、Match、Difference 和对账状态机。
- [X] T063 [P] [US3] 在 `settlement-service/src/main/java/com/payment/settlement/domain/` 实现 SettlementBatch、SettlementItem、Adjustment 和最小结算状态机。
- [X] T064 [US3] 在 `reconciliation-service/src/main/java/com/payment/reconciliation/application/ReconciliationApplicationService.java` 使用 Mock/预置渠道账单和 Payment/Refund 查询 RPC 实现事实比对，禁止修改原始 Payment/Refund；依赖 T062。
- [X] T065 [US3] 在 `settlement-service/src/main/java/com/payment/settlement/application/SettlementApplicationService.java` 实现商户周期结算资格、净额计算、批次幂等和 UNKNOWN；仅生成结算批次和模拟结果，不执行真实出款；依赖 T063、T064。
- [X] T066 [US3] 在 `reconciliation-service/src/main/java/com/payment/reconciliation/api/` 实现基础对账执行、差异查询和差异处理 RPC；依赖 T064。
- [X] T067 [US3] 在 `settlement-service/src/main/java/com/payment/settlement/api/` 实现结算批次创建、查询和模拟结果收敛 RPC；明确不提供真实出款接口；依赖 T065。
- [X] T068 [US3] 在 `reconciliation-service/src/main/java/com/payment/reconciliation/infra/persistence/` 和 `settlement-service/src/main/java/com/payment/settlement/infra/persistence/` 实现服务自有持久化，并在 `reconciliation-service/src/main/resources/fixtures/channel-statements/` 提供 Mock/预置渠道账单；依赖 T062-T067。
- [X] T069 [US3] 在 `reconciliation-service/src/test/java/com/payment/reconciliation/integration/ReconciliationSettlementRpcScenarioTest.java` 完成 T061 的实现验证，并运行 `mvnw test` 验证 US3 不破坏 US1/US2；依赖 T066-T068。

**检查点**: 对账与结算独立；差异可追踪；结算只使用已确认事实；重复批次和 UNKNOWN 结果不会重复结算。

## Phase 6: Polish & Cross-Cutting Concerns

**目标**: 完成可观测性、部署验证、文档回归和完整 MVP 验收。

- [X] T070 [P] 在各服务 `src/main/java/com/payment/<service>/infra/observability/BusinessMetrics.java` 实现支付成功/失败/UNKNOWN、UNKNOWN 持续时间、重复回调、退款成功/失败、履约失败、权益发放失败、对账差异和结算失败指标。
- [X] T071 [P] 在各服务 `src/main/java/com/payment/<service>/infra/observability/StructuredAuditLogger.java` 实现结构化业务日志和模拟资金动作审计字段，确保敏感信息脱敏。
- [X] T072 [P] 在各资金服务 `src/test/java/` 验证所有必需业务指标在对应状态变化时递增或记录持续时间。
- [X] T073 [P] 在各服务 `src/test/java/` 验证关联 ID 能贯穿订单、支付、回调、履约、权益、退款、对账和结算 RPC 流程。
- [X] T074 在 `docs/deployment/docker-compose.yml` 和 `docs/deployment/README.md` 完成本地、Compose、单机启动、健康检查和基本回滚验证说明。
- [X] T075 [P] 在 `.github/workflows/verify.yml` 配置 `mvnw verify`、测试、格式检查和构建产物验证；不引入微服务部署流水线。
- [X] T076 [P] 更新 `docs/specs/001-core-business-model/quickstart.md`，补充实际启动命令、验证命令和预期结果。
- [X] T077 运行 `mvnw verify`、Compose 验证和 quickstart 全链路 RPC 回归，记录 `docs/specs/001-core-business-model/` 下的 MVP 验收结果；确认 Settlement 只生成模拟结算结果，不执行真实出款。
- [ ] T078 运行 `/review` 和支付相关 `/payment-review`，检查 `docs/specs/001-core-business-model/plan.md` 与实现结果，确认没有绕过模块边界、状态机、幂等或 Ledger 约束。

**检查点**: MVP 通过完整购买、UNKNOWN、退款、对账、结算、指标、日志、Compose 和 CI 验证。

## Dependencies & Execution Order

### Phase Dependencies

- Phase 1 无依赖，必须首先完成。
- Phase 2 依赖 Phase 1，并阻塞所有用户故事。
- Phase 3（US1）依赖 Phase 2，是 MVP 最小范围。
- Phase 4（US2）依赖 US1 的 Payment、Fulfillment 和 Entitlement 边界。
- Phase 5（US3）依赖 US1/US2 的已确认 Payment/Refund 事实。
- Phase 6 依赖需要交付的用户故事，完整 MVP 依赖 US1-US3。

### User Story Dependencies

- **US1**：Phase 2 完成后开始；无其他用户故事依赖。
- **US2**：依赖 US1 完成；退款引用已确认的 Order/Payment。
- **US3**：依赖 US1/US2 完成；对账消费已确认 Payment/Refund 事实，并使用 Mock/预置渠道账单。

### Parallel Opportunities

- Phase 1 的 T003-T006 可以并行。
- Phase 2 的 T009-T015、T016-T019 可按文件并行。
- US1 测试 T020-T027 可并行编写；实现完成前必须先验证测试失败。
- US1 的 Catalog、Order、PaymentAttempt、Fulfillment、Entitlement 领域模型可在不同文件中并行，但共享应用编排任务必须按依赖顺序执行。
- US2 的 T048-T050 可并行；Refund 领域模型与测试准备可并行。
- US3 的 T059-T061 可并行；Reconciliation 和 Settlement 领域模型可并行。
- Phase 6 的 T070-T076 可按文件并行，T077-T078 必须最后执行。

## Implementation Strategy

### MVP First

1. 完成 Phase 1 Setup。
2. 完成 Phase 2 Foundational。
3. 完成 Phase 3 US1。
4. 停止并执行 `mvnw verify`、quickstart 和 `/review`。
5. 只有 US1 的成功支付、UNKNOWN 收敛、履约 RPC 和权益发放稳定后，才进入 US2。

### Incremental Delivery

1. US1：商品购买和支付后履约，形成可演示 MVP。
2. US2：退款及退款后处理，不破坏 US1。
3. US3：基础对账与结算，不引入真实 Ledger。
4. Polish：业务指标、审计日志、Compose 和 CI/CD。

### Done Definition

每项任务完成时必须：

- 代码、测试或文档位于任务指定路径。
- 相关测试通过，且没有通过修改测试来掩盖实现问题。
- 状态机、幂等、UNKNOWN、数据所有权和 RPC 边界符合 Plan。
- 不新增未批准的服务、MQ、数据库边界、真实资金记账路径或真实结算出款路径。

## Notes

- `[P]` 仅表示文件和依赖允许并行，不代表可以跳过前置业务约束。
- 任务中的英文模块名、类名、事件名和命令名是稳定标识，不是额外技术范围。
- 本清单不创建 Ledger，也不执行真实结算出款；真实资金模型必须在后续 Feature 中通过 Ledger 建立可追溯账务事实。
