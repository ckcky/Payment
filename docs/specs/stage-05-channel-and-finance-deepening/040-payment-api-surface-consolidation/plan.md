# Plan: 040-payment-api-surface-consolidation

**Feature**：040　**Status**：Draft（与 spec.md 一致）　**日期**：2026-09-26

> 本文回答「怎么落地」。业务需求见 [spec.md](spec.md)，**不在本文重新定义**。

---

## 1. 技术上下文

| 项 | 值 |
|---|---|
| 涉及服务 | `payment-service`（唯一改码服务）；`reconciliation-service` / `order-service` **只读不改** |
| 涉及包 | `com.payment.payment.api`（控制器 + DTO）、`com.payment.payment.application`（单号解析）、`com.payment.payment.domain`（不改） |
| 端口 | payment-service `:8083`（宿主/容器模式共用 8081–8091） |
| Schema | **本 Feature 不改任何 DDL** |
| 公共库 | `common-dto`（可能新增通用退款请求/响应 DTO，视 T5 决策） |
| 构建 | `./mvnw -B -pl payment-service -am test` |

---

## 2. 现状检查（落地前 MUST 复核）

| # | 约束 | 核实方式 |
|---|---|---|
| C1 | `PaymentRepository.findByTransactionId` **已存在**且持久层已实现 ⇒ **无需新增仓储方法** | `PaymentRepository.java:16`、`MybatisPaymentRepository.java:43-45` |
| C2 | 单号前缀固定：`PAYMENT("PM")`、`TRANSACTION("TX")` ⇒ 可按前缀自动识别单号类型 | `BusinessNoType.java:19,13` |
| C3 | `RefundApplicationService.createRefund` 已实现完整链路（幂等 → 串行化 → 额度校验 → 渠道 → 三态收敛）⇒ **不重写** | `RefundApplicationService.java:60-124` |
| C4 | `PaymentRefundService` 被 `LocalPaymentRefundGateway` 依赖 ⇒ **不可删** | `LocalPaymentRefundGateway.java:19-27` |
| C5 | `GET /payments/{ref}` 的 6 个调用点**全部传业务单号** ⇒ 删除数值 id 分支零破坏 | `demo.html:805`、`scenario-refund.sh:32,42`、`scenario-payment-unknown.sh:31,33`、`scenario-routing.sh:76`、`traffic-gen.sh:173` |
| C6 | `unknown` 端点被 runbook + 5 条告警规则依赖 ⇒ **不可删、不可默认关** | `runbook.md:352,354`、`payment-alerts.yml:36,64,163` |
| C7 | 038 后包路径基准 = `com.payment.payment.**`（旧 `com.payment.refund.**` 已不存在） | 038 acceptance SC-001 |

---

## 3. 数据模型与契约变更

### 3.1 新增：单号解析器（应用层）

```java
// com.payment.payment.application.PaymentRefResolver
public final class PaymentRefResolver {
    /** 按 (paymentNo, transactionId) 两个可选业务单号定位唯一 Payment。
     *  FR-002 两者皆空 → INVALID_ARGUMENT；FR-003 单值 → 直查；
     *  FR-004 双值 → 交叉校验，非同一实体 → CONFLICT。 */
    public Payment resolve(String paymentNo, String transactionId);

    /** FR-005/FR-006：单 ref 入口。按前缀识别（PM→paymentNo，TX→transactionId），
     *  无法识别则回退 paymentNo。绝不按数值主键查询（INV-1）。 */
    public Payment resolveSingle(String ref);
}
```

### 3.2 响应 DTO 扩展（只读字段）

```java
// PaymentResponse：新增 2 个派生字段（不入库）
public record PaymentResponse(Long id, String paymentNo, String transactionId, String orderNo,
                              String userId, long amountMinor, String currencyCode, String status,
                              String failureReason,
                              boolean resolvable,        // FR-007
                              String suggestedAction)    // FR-007，可为 null
```

```java
// RefundResponse：同样新增 resolvable / suggestedAction（FR-008）
```

> ⚠️ `PaymentResponse` 现有 10 个字段，**新增字段放末尾**以保持构造兼容；`from(Payment)` 工厂需同步改。

### 3.3 新增：通用退款请求 DTO

```java
// 建议落在 payment-service api/dto（若上游需共享则下沉 common-dto —— 见 §7 R5）
public record CreateRefundHttpRequest(String orderNo, String paymentNo, String userId,
                                      long amountMinor, String currencyCode, String reason,
                                      String idempotencyKey,
                                      String transactionNo,          // 可选
                                      String transactionRefundNo)    // 可选
```

### 3.4 删除

| 删除对象 | 保留对象 |
|---|---|
| `RefundRpcController`（整类，含 2 个端点） | `PaymentRefundService`（类 + `queryAmount` / `refund` 方法） |
| `POST /internal/payments/query-amount`（HTTP） | `PaymentRefundGateway` 端口 + `LocalPaymentRefundGateway` 实现 |
| `POST /internal/payments/refund-attempt`（HTTP） | `PaymentAmountQueryRequest` / `Response`（`common-dto`，仍被应用层使用） |

### 3.5 条件注册

```java
// UnknownQueueController
@ConditionalOnProperty(name = "payment.ops.unknown-queue.enabled",
                       havingValue = "true", matchIfMissing = true)
```

---

## 4. 影响范围

### 4.1 端点变化总表

| 变化 | 端点 | 说明 |
|---|---|---|
| ➕ 新增 | `GET /payments?paymentNo=&transactionId=` | FR-001 |
| ➕ 新增 | `POST /internal/payments/refunds` | FR-010 |
| 🔧 改语义 | `GET /payments/{ref}` | FR-005（去数值 id 分支） |
| 🔧 改语义 | `POST /payments/{ref}/resolve` | FR-006（ref 走解析器） |
| 🔧 扩展响应 | `GET /payments*`、`GET /internal/payments/refunds/{refundNo}` | FR-007/FR-008 |
| 🔧 加开关 | `GET /internal/payments/unknown` | FR-013（默认开启） |
| ❌ 删除 | `POST /internal/payments/query-amount` | FR-015 |
| ❌ 删除 | `POST /internal/payments/refund-attempt` | FR-014 |
| ✅ 不动 | `POST /internal/payments/refund-command` | FR-012 |
| ✅ 不动 | 两处 `confirmed-facts` | FR-017（仅补 javadoc） |
| ✅ 不动 | `POST /internal/payments/refunds/{refundNo}/resolve`、`channel-callback` | FR-009 |

**净变化**：payment/api 端点 12 → **12**（+2 −2）。但**零调用端点 2 → 0**，且每个端点语义明确。

### 4.2 需同步的非代码文件

| 文件 | 同步内容 |
|---|---|
| `docs/architecture/systems/payment-service.md` | L0 端点表（**按 AGENTS.md 报 DOCUMENTATION_DRIFT 后由负责人确认再改，禁止自行改写 L0**） |
| `docs/operations/runbook.md` | unknown 端点新增开关说明（FR-013a） |
| `deployment/mock-channel-web/.../demo.html` | 无需改（传的是 `paymentNo`，FR-005 兼容） |
| `deployment/demo/scenario-*.sh` | 无需改（传的是业务单号） |
| `payment-service/src/test/.../InternalServiceAuthTest.java` | 鉴权样例从 `query-amount` 换成仍在册端点（FR-016） |
| `CHANGELOG.md` | 登记删除的 2 个端点（破坏性变更） |

---

## 5. 依赖顺序

```
T1 单号解析器 PaymentRefResolver（纯新增，可独立测）
 └→ T2 GET /payments 查询参数式端点（FR-001~004）
     └→ T3 GET /payments/{ref} + resolve 语义修正（FR-005/FR-006）
         └→ T4 响应扩展 resolvable / suggestedAction（FR-007/FR-008）
T5 通用退款端点（FR-010~012，与 T1~T4 无依赖，可并行）
T6 删除死端点（FR-014~016）— ⚠️ 必须在 T5 之后（FR-016 依赖 T5 提供替代鉴权样例端点）
T7 运维端点开关（FR-013）
T8 文档与 javadoc（FR-017）
T9 全量回归 + 全链路（SC-009/SC-010）
```

> **T6 必须在 T5 之后**：`InternalServiceAuthTest` 需要一个仍在册的内部端点做鉴权样例；T5 新增的 `POST /internal/payments/refunds` 或既有 `GET /internal/payments/refunds/{refundNo}` 均可作为替代。若先删后补，测试会先红。

---

## 6. 一致性 / 幂等 / 失败恢复

| 关注点 | 方案 |
|---|---|
| **一致性** | 本 Feature 不改写入链路。`GET` 零副作用（INV-3）；通用退款复用既有 `createRefund`，其一致性（串行化 `lockForIntake`、累计额度校验）完全沿用 |
| **幂等** | 通用退款幂等键 = `idempotencyKey`（传入 `transactionRefundNo` 时以后者优先，**沿用 `CreateRefundCommand` 既有语义，禁止修改**）。缺失 → 400（INV-4） |
| **失败恢复** | 通用退款失败路径完全复用 `createRefund` 既有 reject / 三态收敛（`RefundResultProcessor`），不新增补偿逻辑 |
| **兼容** | 旧 `GET /payments/{ref}` 保留且行为兼容（C5），无需破坏性迁移 |

---

## 7. 风险与缓解

| ID | 风险 | 级别 | 缓解 |
|---|---|---|---|
| **R1** | 误删 `PaymentRefundService` 类 ⇒ 通用退款链路断裂（FR-010 无法实现） | 🔴 | T6 明确只删 `RefundRpcController`，grep 复核 `PaymentRefundService` 仍被 `LocalPaymentRefundGateway` 引用；`PaymentRefundServiceTest` 保持全绿（INV-5） |
| **R2** | `PaymentResponse` 是 record，新增字段会破坏既有构造器调用点 | 🟠 | 新增字段放末尾；编译后全量 grep 所有 `new PaymentResponse(` 调用点同步改；优先走 `PaymentResponse.from()` 工厂 |
| **R3** | `resolvable` 判定状态集合猜错（支付/退款允许 resolve 的状态） | 🟠 | **执行方 MUST 读 `PaymentUnknownResolutionService` 与 `RefundStateMachine` 代码确认**，不得凭 spec 猜测；必要时提问 |
| **R4** | unknown 端点开关 `matchIfMissing` 写错成 `false` ⇒ 默认关闭，破坏 runbook/告警 | 🟠 | INV-6 硬性要求 `matchIfMissing = true`；SC-008 用单测锁定「不配置 → 200」 |
| **R5** | 通用退款 DTO 落点争议（payment-service 本地 vs 下沉 `common-dto`） | 🟡 | 若仅 payment-service 内部使用 → 落 `api/dto`；若上游（order/客服）需 Feign 调用 → 下沉 `common-dto`。**执行方按是否有跨服务调用方判断，有疑问则提问** |
| **R6** | 全链路 demo 脚本依赖旧行为 | 🟡 | SC-010 明确跑 3 个 scenario 脚本；C5 已确认传业务单号，理论零影响 |
| **R7** | `InternalServiceAuthTest` 改造时误删/改断言（违反红线） | 🟠 | FR-016 明确：只换端点路径，**断言（期望状态码）一律不动** |

---

## 8. 验证方式

| 层 | 验证 |
|---|---|
| 单测 | 新增 `PaymentRefResolverTest`（SC-001~SC-004）、`CreateRefundHttpEndpointTest`（SC-006）、`UnknownQueueEndpointToggleTest`（SC-008）；既有全量零回归（SC-009，基线 583） |
| 静态 | grep 复核：无 `isDigit` 数值 id 分支（INV-1）、`PaymentRefundService` 仍存在（INV-5）、`query-amount`/`refund-attempt` 端点无残留（SC-007） |
| 全链路 | 起栈 → `reset.sh` → 跑 `scenario-refund.sh` / `scenario-payment-unknown.sh` / `scenario-routing.sh` 全部 EXIT=0（SC-010） |
| 人工 | `GET /payments?paymentNo=...&transactionId=...` 双单号交叉校验；`POST /internal/payments/refunds` 创建 + 同键重放 |

---

## 9. 回滚方案

| 场景 | 回滚 |
|---|---|
| T1~T4（查单改造） | 纯新增 + 语义修正，回滚 = 还原 `getPaymentByRef` 的 `isDigit` 分支 + 删除 `GET /payments` 端点。**注意**：回滚会恢复 ADR-0063 违规，仅作紧急回退 |
| T5（通用退款端点） | 删除 `POST /internal/payments/refunds` 端点。已创建的退款单数据不受影响（领域逻辑未改） |
| T6（删死端点） | 恢复 `RefundRpcController`。**无数据影响**（端点零调用） |
| T7（unknown 开关） | 默认开启 ⇒ 零行为变化，回滚风险最低 |
| 整体 | 单一 `--no-ff` merge commit，可整体 revert |

> 本 Feature **不改 DDL、不改领域模型**，回滚无数据迁移成本。
