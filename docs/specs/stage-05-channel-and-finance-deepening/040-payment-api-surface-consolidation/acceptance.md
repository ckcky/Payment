# Acceptance: 040-payment-api-surface-consolidation

**Feature**：040　**Status**：Draft（与 spec.md 一致）　**日期**：2026-09-26

> 本文回答「如何判定完成」，**只引用** [spec.md](spec.md) 的 FR/INV/SC，不新增业务规则。

---

## 1. INV 门禁（每条不变量如何被验证）

| INV | 门禁方式 | 通过判据 |
|---|---|---|
| **INV-1** 查单不接受数值主键 | ① grep；② 单测 | `grep -rn "allMatch(Character::isDigit)\|Long.parseLong(ref)" payment-service/src/main/java/com/payment/payment/` → **空**；单测「传纯数字 `123` 查单不返回 id=123 的单据」绿 |
| **INV-2** 双单号交叉校验必须同一实体 | 单测（E5） | 构造两笔不同支付 A/B，`?paymentNo=A.paymentNo&transactionId=B.transactionId` → **409 CONFLICT** |
| **INV-3** GET 零副作用 | 单测 | 连续 2 次 `GET /payments?paymentNo=...` 响应 JSON **逐字节一致**（含 `status`/`resolvable`）；且无 `FINANCIAL_AUDIT` 写入、无渠道调用 |
| **INV-4** 资金入口必带幂等键 | 单测（E8/E11） | ① body 缺 `idempotencyKey` → 400；② 同键重复提交 → 同一 `refundNo`，退款单总数 = 1 |
| **INV-5** 删除端点不得删依赖类 | grep + 测试 | `grep -rn "class PaymentRefundService" payment-service/src/main` → **非空**；`PaymentRefundServiceTest` 全绿；`LocalPaymentRefundGateway` 引用仍在 |
| **INV-6** 运维端点默认可用 | 单测（双档） | 不配置 `payment.ops.unknown-queue.enabled` → `GET /internal/payments/unknown` 返回 **200**；置 `false` → **404** |
| **INV-7** 既有调用方零破坏 | 全链路脚本 | `scenario-refund.sh` / `scenario-payment-unknown.sh` / `scenario-routing.sh` **EXIT=0** |

---

## 2. SC 验收表

| SC | 验收标准 | 验证命令 / 断言 | 结论 |
|---|---|---|---|
| **SC-001** | `?transactionId=` 可查单；数值 id 分支已删 | `PaymentRefResolverTest#resolveByTransactionId` 绿 + INV-1 grep 空 | 待实测 |
| **SC-002** | 无参 → 400；单参命中 → 200；未命中 → 404 | `PaymentQueryEndpointTest` 3 个用例 | 待实测 |
| **SC-003** | 双参同实体 → 200；不同实体 → 409 | `PaymentQueryEndpointTest#dualRefConflict` | 待实测 |
| **SC-004** | 旧入口传业务号行为不变；传纯数字不按 id 命中 | `PaymentRefResolverTest#singleRefNumericNotId` | 待实测 |
| **SC-005** | 响应含 `resolvable`/`suggestedAction`；连续 GET 一致且无审计 | `PaymentResponseResolvableTest` | 待实测 |
| **SC-006** | 通用退款可创建；同键重放同单；缺幂等键 → 400 | `CreateRefundEndpointTest` 3 个用例 | 待实测 |
| **SC-007** | 两死端点访问 → 404；`PaymentRefundService` 仍存在且测试全绿 | `grep -rn "query-amount\|refund-attempt" payment-service/src/main` 空 + `PaymentRefundServiceTest` 绿 | 待实测 |
| **SC-008** | unknown 端点：默认 200 / 置 false 404 | `UnknownQueueEndpointToggleTest` 双档用例 | 待实测 |
| **SC-009** | 全量单测零回归 | `./mvnw -B -pl common/common-core,common/common-dto,payment-service,deployment/architecture-tests -am test` | 待实测 |
| **SC-010** | 全链路 3 个 scenario EXIT=0 | 起栈后跑脚本 | 待实测 |

### 2.1 回归基线（**已实测**，master @ `23dae45`）

| 模块 | 基线测试数 | 说明 |
|---|---|---|
| `common-core` | 70 | — |
| `common-dto` | 15 | 纯 DTO |
| `payment-service` | **583** | SC-009 要求 ≥ 583 |
| `architecture-tests` | 23 跑 / 1 红 | ⚠️ 唯一的红是 `AccountingVocabularyBoundaryTest` 报 `.workbuddy/**` —— **已知环境噪声，负责人已裁定「不处置」**。禁止改测试、禁止删 `.workbuddy/`（项目数据目录）。**不计入本 Feature 失败** |

---

## 3. 演示验收（可复现步骤）

### 3.1 双单号查单

```bash
# 起栈后（payment-service :8083）
PAYMENT_URL=http://127.0.0.1:8083

# 1) 无参数 → 400
curl -s --noproxy '*' -o /dev/null -w "%{http_code}\n" "$PAYMENT_URL/payments"
# 期望 400

# 2) 交易单号查单 → 200（先下单拿到 PM/TX 号）
curl -s --noproxy '*' "$PAYMENT_URL/payments?paymentNo=$PAYMENT_NO" | python -c "import sys,json;d=json.load(sys.stdin);print(d['paymentNo'],d['transactionId'],d['status'],d['resolvable'])"

# 3) 双单号交叉校验（同单 → 200）
curl -s --noproxy '*' -o /dev/null -w "%{http_code}\n" "$PAYMENT_URL/payments?paymentNo=$PAYMENT_NO&transactionId=$TX_NO"
# 期望 200

# 4) 双单号指向不同单 → 409
curl -s --noproxy '*' -o /dev/null -w "%{http_code}\n" "$PAYMENT_URL/payments?paymentNo=$PAYMENT_NO&transactionId=$OTHER_TX_NO"
# 期望 409
```

### 3.2 通用退款创建 + 幂等重放

```bash
# 1) 创建
curl -s --noproxy '*' -X POST "$PAYMENT_URL/internal/payments/refunds" \
  -H 'Content-Type: application/json' -H "X-Service-Token: $TOKEN" \
  -d "{\"orderNo\":\"$ORDER_NO\",\"paymentNo\":\"$PAYMENT_NO\",\"userId\":\"u1\",
       \"amountMinor\":100,\"currencyCode\":\"CNY\",\"reason\":\"user-request\",
       \"idempotencyKey\":\"demo-refund-001\"}" | python -c "import sys,json;print(json.load(sys.stdin)['refundNo'])"

# 2) 同 idempotencyKey 重放 → 返回同一 refundNo
# 3) 缺 idempotencyKey → 400
```

### 3.3 运维端点开关

```bash
# 默认（不配置）→ 200
curl -s --noproxy '*' -o /dev/null -w "%{http_code}\n" "$PAYMENT_URL/internal/payments/unknown"
# 期望 200

# 置 payment.ops.unknown-queue.enabled=false 重启 → 404
```

### 3.4 死端点已下线

```bash
curl -s --noproxy '*' -o /dev/null -w "%{http_code}\n" -X POST "$PAYMENT_URL/internal/payments/refund-attempt" -d '{}'
curl -s --noproxy '*' -o /dev/null -w "%{http_code}\n" -X POST "$PAYMENT_URL/internal/payments/query-amount" -d '{}'
# 两处均期望 404
```

### 3.5 全链路脚本

```bash
# 1 Docker Desktop Running
deployment/start-all.sh
deployment/demo/reset.sh

deployment/demo/scenario-refund.sh            # 期望 EXIT=0
deployment/demo/scenario-payment-unknown.sh   # 期望 EXIT=0
deployment/demo/scenario-routing.sh           # 期望 EXIT=0
```

---

## 4. 已知限制（显式接受）

| # | 限制 | 说明 |
|---|---|---|
| **L1** | `GET /payments/{ref}` 旧入口**保留未删** | 决策 D1：有 6 个真实调用点，删除属破坏性变更。是否已彻底废弃删除见 spec §15 Q1（人类决策） |
| **L2** | 通用退款端点**无额外鉴权管控** | 项目内部鉴权既定 `return true`（全局裁剪，Non-goal NG4）。是否需 `X-Admin-Token` 见 spec §15 Q2 |
| **L3** | 暴露面治理未做 | 无 gateway / 无限流。FR-013 只提供「可关闭」开关，不等于已治理 |
| **L4** | `resolvable` 判定集合以代码现状为准 | spec 未写死具体状态名，执行方 MUST 读 `PaymentUnknownResolutionService` / `RefundStateMachine` 确认，避免猜错 |
| **L5** | 通用退款 DTO 落点待定 | 有跨服务调用方则下沉 `common-dto`，否则落 `payment-service/api/dto`（plan §7 R5） |
| **L6** | L0 端点表同步需负责人确认 | `docs/architecture/systems/payment-service.md` 属 L0，按 AGENTS.md 报 DOCUMENTATION_DRIFT，**执行方不得自行改写** |

---

## 5. 完成判据（全部满足才算完）

- [ ] INV-1 ~ INV-7 门禁全部通过
- [ ] SC-001 ~ SC-010 实测结论已回填本文 §2（**无「待实测」残留**）
- [ ] `./mvnw -B -pl common/common-core,common/common-dto,payment-service,deployment/architecture-tests -am test` 全绿（payment-service ≥ 583；唯一允许的 `.workbuddy/**` 噪声除外）
- [ ] 3 个 scenario 脚本 EXIT=0
- [ ] `tasks.md` 全部打勾
- [ ] CHANGELOG 已登记端点变化（新增 2 / 删除 2 / 语义修正 1）
- [ ] 已 `--no-ff` 合入 master 并 push
