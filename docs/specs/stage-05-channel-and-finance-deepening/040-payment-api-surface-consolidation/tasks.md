# Tasks: 040-payment-api-surface-consolidation

**Feature**：040　**Status**：Draft（与 spec.md 一致）　**日期**：2026-09-26

> 主执行清单。业务需求见 [spec.md](spec.md)，实现路径见 [plan.md](plan.md)。
> 每个任务完成后打勾 `- [x]` 并跑一次 `./mvnw -B -pl payment-service -am test`。

**分支**：`git checkout -b feature/040-payment-api-surface-consolidation master`
**TDD**：每个 Task 先写失败测试（红）→ 实现（绿）→ 重构，禁止先实现后补测试。

---

## T1 — 单号解析器 `PaymentRefResolver`（FR-001~FR-005a, INV-1, INV-2）

**文件**：

- 新增 `payment-service/src/main/java/com/payment/payment/application/PaymentRefResolver.java`
- 新增 `payment-service/src/test/java/com/payment/payment/application/PaymentRefResolverTest.java`

**要做**：

1. `resolve(paymentNo, transactionId)`：
   - 两者皆空/空白/缺省 → `BizException.of(ErrorCodes.INVALID_ARGUMENT, ...)`（FR-002）
   - 仅一个非空 → 按该单号查，未命中 → `NOT_FOUND`（FR-003）
   - 两个非空 → 分别查；**任一未命中 → `NOT_FOUND`**；都命中且 `id` 不等 → `CONFLICT`（FR-004/INV-2）；都命中且 `id` 相等 → 返回
2. `resolveSingle(ref)`：前缀 `PM` → `paymentNo`，`TX` → `transactionId`，**无法识别 → 回退 `paymentNo`**（FR-005）。**禁止任何按数值主键查询的分支**（INV-1）
3. 依赖注入 `PaymentRepository`（已有 `findByPaymentNo` / `findByTransactionId`，**无需新增仓储方法**）

**验收**：

- [ ] `PaymentRefResolverTest` 覆盖 E1~E7（spec §9）全部分支，全绿
- [ ] `grep -n "isDigit\|parseLong" payment-service/src/main/java/com/payment/payment/application/PaymentRefResolver.java` → **空**
- [ ] 追溯：FR-001、FR-002、FR-003、FR-004、FR-005a、INV-1、INV-2

---

## T2 — 新增 `GET /payments` 查询参数式端点（FR-001~FR-004）

**文件**：

- 改 `payment-service/src/main/java/com/payment/payment/api/PaymentController.java`
- 改 `payment-service/src/main/java/com/payment/payment/application/PaymentApplicationService.java`
- 新增 `payment-service/src/test/java/com/payment/payment/api/PaymentQueryEndpointTest.java`

**要做**：

1. `PaymentController` 新增：

   ```java
   @GetMapping
   public PaymentResponse getPayment(@RequestParam(required = false) String paymentNo,
                                     @RequestParam(required = false) String transactionId)
   ```

   委托 `PaymentRefResolver.resolve(paymentNo, transactionId)`
2. 异常 → HTTP 状态码**已由既有全局处理器覆盖，无需新增映射**：
   `GlobalExceptionHandler.mapStatus`（`common/common-core/.../error/GlobalExceptionHandler.java:75-88`）
   已实现 `NOT_FOUND`→404、`CONFLICT`→409、`default`（含 `INVALID_ARGUMENT`）→400。
   **只需抛对应的 `BizException.of(ErrorCodes.X, ...)`，禁止改动该 Handler**
3. **保留** `GET /payments/{ref}`（T3 改语义），不得删除

**验收**：

- [ ] `GET /payments`（无参）→ 400；`?paymentNo=不存在` → 404；`?transactionId=存在` → 200（SC-001/SC-002）
- [ ] 双参数同实体 → 200；不同实体 → **409**（SC-003）
- [ ] 追溯：FR-001、FR-002、FR-003、FR-004

---

## T3 — 旧入口与 resolve 的 ref 语义修正（FR-005, FR-006, INV-1, INV-7）

**文件**：

- 改 `payment-service/src/main/java/com/payment/payment/application/PaymentApplicationService.java`（`getPaymentByRef`，`:333-337`）
- 改 `payment-service/src/main/java/com/payment/payment/api/PaymentController.java`（`GET /{ref}`、`POST /{ref}/resolve`）
- 改 `payment-service/src/main/java/com/payment/payment/application/PaymentUnknownResolutionService.java`（若其 `resolve(String ref, ...)` 内部也做数字判定）

**要做**：

1. **删除** `getPaymentByRef` 中「`ref.chars().allMatch(Character::isDigit)` → `getPayment(Long.parseLong(ref))`」分支
2. 改为委托 `PaymentRefResolver.resolveSingle(ref)`
3. `POST /payments/{ref}/resolve` 的 `ref` 解析同样走 `resolveSingle`（FR-006）
4. 检查 `PaymentUnknownResolutionService` 是否也有类似数字判定，**有则一并改为走解析器**

**验收**：

- [ ] `grep -n "allMatch(Character::isDigit)" payment-service/src/main/java/com/payment/payment/` → **空**（INV-1）
- [ ] 传纯数字（如 `123`）查单 → **不返回 id=123 的单据**（SC-004）
- [ ] 传 `PM...` / `TX...` 行为不变（INV-7）
- [ ] 追溯：FR-005、FR-006、INV-1、INV-7

---

## T4 — 响应扩展 `resolvable` / `suggestedAction`（FR-007, FR-008, INV-3）

**文件**：

- 改 `payment-service/src/main/java/com/payment/payment/api/dto/PaymentResponse.java`
- 改 `payment-service/src/main/java/com/payment/payment/api/dto/RefundResponse.java`（路径以实际为准）
- 改 `payment-service/src/main/java/com/payment/payment/api/RefundController.java`（`GET /{refundNo}`）
- 新增/改 `payment-service/src/test/java/com/payment/payment/api/PaymentResponseResolvableTest.java`

**要做**：

1. `PaymentResponse` record **末尾**新增 `boolean resolvable`、`String suggestedAction`
2. `PaymentResponse.from(Payment)` 填充：`resolvable` = 状态是否属「允许人工收敛」集合；`resolvable==true` 时 `suggestedAction="RESOLVE"`，否则 `null`
3. ⚠️ **判定集合 MUST 以代码现状为准**：读 `PaymentUnknownResolutionService` / `PaymentStatus` / `RefundStateMachine` 确认，**禁止凭 spec 猜测状态名**
4. `RefundResponse` 同 FR-008
5. **纯派生**：不得触发任何写操作、记账、审计（INV-3）

**验收**：

- [ ] 连续 2 次 `GET /payments?paymentNo=...` 响应 JSON **完全一致**（含 `status`、`resolvable`）（INV-3）
- [ ] UNKNOWN 单 → `resolvable=true, suggestedAction="RESOLVE"`；终态单 → `resolvable=false, suggestedAction=null`（SC-005）
- [ ] 编译后 `grep -rn "new PaymentResponse(" payment-service/src` 所有调用点已同步（R2）
- [ ] 追溯：FR-007、FR-008、INV-3

---

## T5 — 通用退款端点（FR-010, FR-011, FR-012a, INV-4）

**文件**：

- 新增 `payment-service/src/main/java/com/payment/payment/api/dto/CreateRefundHttpRequest.java`（若需跨服务共享则下沉 `common/common-dto`，见 R5）
- 改 `payment-service/src/main/java/com/payment/payment/api/RefundController.java`
- 新增 `payment-service/src/test/java/com/payment/payment/api/CreateRefundEndpointTest.java`

**要做**：

1. `RefundController` 新增：

   ```java
   @PostMapping
   public RefundResponse createRefund(@Valid @RequestBody CreateRefundHttpRequest request)
   ```

   委托既有 `RefundApplicationService.createRefund(...)`，**不新增应用服务、不改领域逻辑**
2. 校验 `idempotencyKey` 非空非空白，否则 400 `INVALID_ARGUMENT`（FR-011/INV-4）
3. `transactionNo` / `transactionRefundNo` 可选（FR-012a）。**不得改变 `CreateRefundCommand` 内部「`transactionRefundNo` 优先作幂等键」的逻辑**
4. 返回 201

**验收**：

- [ ] 正常创建 → 201，返回 `refundNo`（SC-006）
- [ ] 缺 `idempotencyKey` → 400（INV-4）
- [ ] 同 `idempotencyKey` 重复提交 → 返回**同一** `refundNo`，退款单总数 = 1（E11/SC-006）
- [ ] `RefundApplicationServiceTest` 保持全绿（领域逻辑零改动）
- [ ] 追溯：FR-010、FR-011、FR-012a、INV-4

---

## T6 — 删除死端点（FR-014, FR-015, FR-016, INV-5）

> ⚠️ **必须在 T5 之后**（鉴权样例需要替代端点）。

**文件**：

- **删除** `payment-service/src/main/java/com/payment/payment/api/RefundRpcController.java`
- 改 `payment-service/src/test/java/com/payment/payment/web/InternalServiceAuthTest.java`
- 改 `CHANGELOG.md`（破坏性变更登记）
- **保留不动**：`payment-service/src/main/java/com/payment/payment/application/PaymentRefundService.java`

**要做**：

1. 删除 `RefundRpcController`（含 `query-amount`、`refund-attempt` 两个端点）
2. `InternalServiceAuthTest.java:207-211` 的 `queryAmount()` 辅助方法改为指向**仍在册的内部端点**（建议 `GET /internal/payments/refunds/{refundNo}`）；**6 处调用的断言（期望状态码）一律不动**（R7）
3. **禁止删除 `PaymentRefundService` 类**——它被 `LocalPaymentRefundGateway` 真实依赖（INV-5/R1）
4. `PaymentAmountQueryRequest` / `PaymentAmountQueryResponse`（`common-dto`）**保留**，仍被应用层使用

**验收**：

- [ ] `grep -rn "query-amount\|refund-attempt" payment-service/src/main` → **空**（SC-007）
- [ ] `grep -rn "class PaymentRefundService" payment-service/src/main` → **非空**（INV-5）
- [ ] `PaymentRefundServiceTest` 全部保持绿（INV-5）
- [ ] `InternalServiceAuthTest` 全绿，且断言未被改动（`git diff` 中只有路径变化）
- [ ] 追溯：FR-014、FR-015、FR-016、INV-5

---

## T7 — 运维端点开关（FR-013, FR-013a, INV-6）

**文件**：

- 改 `payment-service/src/main/java/com/payment/payment/api/UnknownQueueController.java`
- 新增 `payment-service/src/test/java/com/payment/payment/api/UnknownQueueEndpointToggleTest.java`

**要做**：

1. 类上加 `@ConditionalOnProperty(name = "payment.ops.unknown-queue.enabled", havingValue = "true", matchIfMissing = true)`（**`matchIfMissing` 必须为 `true`**，INV-6）
2. javadoc 补充：① 运维观测端点；② 仅返回 UNKNOWN 聚合与有界明细；③ 项目无 gateway、内部鉴权既定 `return true`，**生产 MUST 置于内网/网关之后或通过本开关关闭**
3. **不加** `application.yml` 默认配置（靠 `matchIfMissing=true` 即可保持默认开启）

**验收**：

- [ ] 不配置开关时访问 `GET /internal/payments/unknown` → 200（INV-6/SC-008）
- [ ] 置 `payment.ops.unknown-queue.enabled=false` → 404（SC-008）
- [ ] `grep -n "matchIfMissing" UnknownQueueController.java` 命中且值为 `true`
- [ ] 追溯：FR-013、FR-013a、INV-6

---

## T8 — 文档与 javadoc（FR-017）

**文件**：

- 改 `payment-service/src/main/java/com/payment/payment/api/ReconciliationFactsController.java`（javadoc）
- 改 `payment-service/src/main/java/com/payment/payment/api/RefundController.java`（`confirmed-facts` javadoc）
- 改 `docs/operations/runbook.md`（unknown 开关说明）
- 改 `CHANGELOG.md`（端点变化登记）

**要做**：

1. 两处 `confirmed-facts` 补 javadoc：用途（供 reconciliation 拉取平台侧已确认资金事实、与渠道对账单比对找长短款）、支付/退款是两条独立资金流**非冗余**、可选 `?period=YYYY-MM-DD`
2. runbook 补 unknown 端点开关说明
3. CHANGELOG 登记：新增 2 端点、删除 2 端点、旧查单语义修正

**验收**：

- [ ] 两处 `confirmed-facts` 的 javadoc 含「reconciliation」与「非冗余/独立资金流」说明
- [ ] runbook 含 `payment.ops.unknown-queue.enabled` 说明
- [ ] 追溯：FR-017

---

## T9 — 全量回归 + 全链路（SC-009, SC-010）

**要做**：

1. `./mvnw -B -pl common/common-core,common/common-dto,payment-service,deployment/architecture-tests -am test`
2. **基线**：payment-service **583**（允许新增，不得减少）；architecture-tests 唯一允许的红是 `.workbuddy/**` 环境噪声（已裁定不处置，禁止改测试/删目录）
3. 全链路：`Docker Running` → `deployment/start-all.sh` → `deployment/demo/reset.sh` → 跑：
   - `deployment/demo/scenario-refund.sh`
   - `deployment/demo/scenario-payment-unknown.sh`
   - `deployment/demo/scenario-routing.sh`
   三者 **EXIT=0**（INV-7/SC-010）
4. 人工验证：双单号交叉校验、通用退款创建 + 同键重放

**验收**：

- [ ] 全量单测 payment-service ≥ 583，零失败
- [ ] 3 个 scenario 脚本 EXIT=0
- [ ] `acceptance.md` 所有 SC 实测结论已回填，**无「待填」残留**
- [ ] 追溯：SC-009、SC-010、INV-7

---

## T10 — 收口

- [ ] `git add -A` + `git commit`（message 标注 FR 覆盖）
- [ ] `git merge --no-ff` 合入 master 并 `git push origin master`（**本机无 `gh` CLI**）
- [ ] 若涉及 L0（`docs/architecture/systems/payment-service.md` 端点表）漂移 → **按 AGENTS.md 报 DOCUMENTATION_DRIFT，禁止自行改写 L0**

---

## FR 覆盖追溯表

| FR | 覆盖任务 |
|---|---|
| FR-001 | T1, T2 |
| FR-002 | T1, T2 |
| FR-003 | T1, T2 |
| FR-004 | T1, T2 |
| FR-005 | T1, T3 |
| FR-005a | T1, T3 |
| FR-006 | T3 |
| FR-007 | T4 |
| FR-008 | T4 |
| FR-009 | —（保留不动，无需改动） |
| FR-010 | T5 |
| FR-011 | T5 |
| FR-012 | —（保留不动） |
| FR-012a | T5 |
| FR-013 | T7 |
| FR-013a | T7 |
| FR-014 | T6 |
| FR-015 | T6 |
| FR-016 | T6 |
| FR-017 | T8 |

| INV | 覆盖任务 |
|---|---|
| INV-1 | T1, T3 |
| INV-2 | T1 |
| INV-3 | T4 |
| INV-4 | T5 |
| INV-5 | T6 |
| INV-6 | T7 |
| INV-7 | T3, T9 |
