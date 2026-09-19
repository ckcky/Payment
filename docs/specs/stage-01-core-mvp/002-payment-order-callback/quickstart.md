# Quickstart: 支付成功回写订单与交易状态（002）

> 本文件给出可跑通的验证步骤，证明「支付明确成功 → 订单 PAID + 交易 SUCCEEDED」的回写闭环。
> 权威验证用 `./mvnw verify`（H2，无需 MySQL）；手动 e2e 用你已启动的本地 MySQL。

## 0. 前置条件

- Java 21、Maven Wrapper（`./mvnw`）就绪。
- **本地 MySQL 已启动**（你已确认）：`localhost:3306`，用户/密码 `root/root`，已建 8 个空 schema（`catalog / order / payment / refund / fulfillment / entitlement / reconciliation / settlement`）。
  - 若用 Docker 启动：`docker compose -f deployment/docker-compose.yml up -d`（MySQL 容器名 `payment-mysql`，宿主机 3306）。
- 端口约定：catalog 8082、order 8083、payment 8084、fulfillment 8086、entitlement 8087（见 `deployment/README.md`）。

## 1. 权威验证（无需 MySQL）

回写逻辑由单元测试 / 契约测试 / 场景测试覆盖，全部在 H2 / 内存 fake 上运行：

```sh
cd C:/Users/user/Desktop/GoProj/PaymentArch
./mvnw verify      # 编译 + 全量测试（H2，不依赖 MySQL）
```

关键测试（直接证明 002 回写语义）：

| 验证点 | 测试类 | 路径 |
|---|---|---|
| 订单 PENDING_PAYMENT→PAID + 整单支付 | `OrderStateMachineTest`、`OrderInvariantTest` | `order-service/src/test/.../domain/` |
| 订单侧端到端编排 | `SuccessfulPurchaseScenarioTest` | `order-service/src/test/.../scenario/` |
| 重复回调只触发一次（不二次累加） | `PaymentCallbackContractTest.duplicateCallbackDoesNotPublishTwice` | `payment-service/src/test/.../contract/` |
| 迟到失败不覆盖成功 / 未知不触发新事件 | `PaymentCallbackContractTest.lateFailureCallbackDoesNotOverwriteSuccess`、`...unknownCallbackAfterUnknownStaysUnknownWithoutNewEvent` | `payment-service/src/test/.../contract/` |
| 重复回调计数一次 | `PaymentMetricsTest` | `payment-service/src/test/.../application/` |

预期：`BUILD SUCCESS`，002 相关测试全绿。

## 2. 手动 e2e（本地 MySQL，演示真实 RPC 回写）

### 2.1 构建并启动服务

```sh
# 先把所有模块装进本地仓库（使 spring-boot:run 能解析 common-* 快照依赖）
./mvnw install -DskipTests

# 另开若干终端，分别启动（或一条命令后台启动全部：bash deployment/start-all.sh）
./mvnw -pl catalog-service     spring-boot:run   # 8082
./mvnw -pl order-service       spring-boot:run   # 8083
./mvnw -pl payment-service     spring-boot:run   # 8084
./mvnw -pl fulfillment-service spring-boot:run   # 8086
./mvnw -pl entitlement-service spring-boot:run   # 8087

# 健康检查（均返回 {"status":"UP"} 即就绪）
curl http://localhost:8082/actuator/health
curl http://localhost:8083/actuator/health
curl http://localhost:8084/actuator/health
```

### 2.2 建立可售 SKU（order 建单会 RPC 校验 SKU 可售性）

```sh
# 1) 建商品
curl -s -X POST http://localhost:8082/products \
  -H 'Content-Type: application/json' \
  -d '{"productCode":"P-A","name":"Demo Product","type":"PHYSICAL"}'

# 2) 建 SKU（价格用最小货币单位：100 = ¥1.00）
curl -s -X POST http://localhost:8082/skus \
  -H 'Content-Type: application/json' \
  -d '{"skuCode":"SKU-A","productId":1,"name":"Demo SKU","priceMinor":100,"currencyCode":"CNY","deliveryDefinition":"digital"}'
# 返回 {"id":1,...}（记下 skuId，下面用 1）

# 3) 激活 SKU，使其可售（order 侧只接受 sellable SKU）
curl -s -X POST http://localhost:8082/skus/1/activate
```

### 2.3 创建订单（默认 Mock Channel = SUCCESS，回写同步发生）

```sh
curl -s -X POST http://localhost:8083/orders \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u1","merchantId":"m1","items":[{"skuId":1,"quantity":2}]}'
```

返回示例（注意 `status` 已为 `PAID`，`paymentStatus` 为 `SUCCEEDED`）：

```json
{
  "orderId": 1,
  "transactionId": 1,
  "status": "PAID",
  "totalMinor": 200,
  "currencyCode": "CNY",
  "paymentId": 1,
  "paymentStatus": "SUCCEEDED"
}
```

> 说明：默认 Mock Channel 为 `SUCCESS`，`createPaymentIntent` 在订单创建时同步 `charge` 并立即落 SUCCESS，因此回写**当场**发生——这正是 002 要打通的闭环。

### 2.4 校验回写结果

```sh
# 订单应为 PAID，已支付金额 = 订单总额（200）
curl -s http://localhost:8083/orders/1

# 支付应为 SUCCEEDED
curl -s http://localhost:8084/payments/1
```

断言：

- `Order.status == PAID`，`paidMinor == totalMinor (200)`，且 `paymentId` 已记录。
- `Payment.status == SUCCEEDED`。
- `Transaction` 经内部 RPC 推进为 `SUCCEEDED`（订单侧通过 `GET /orders/{id}` 的响应可间接确认；其内部 Transaction 状态由 order-service 持久化）。

### 2.5 验证「仅成功才回写 / 失败未知不回写」（可选，演示 UNKNOWN 收敛）

支付 `UNKNOWN→SUCCESS` 的收敛同样走 `applyAndNotify`，会触发一次回写。可用 `POST /payments/{id}/resolve` 传入权威 SUCCESS 结果（需先让该支付处于 UNKNOWN；默认 Mock 为 SUCCESS，故需将 Mock 切到 TIMEOUT 或经单元测试覆盖——本步以 Swagger 演示为主，单测证据见 §1）。

```sh
# 若某支付处于 UNKNOWN，传入权威成功结果收敛并触发回写：
curl -s -X POST http://localhost:8084/payments/{id}/resolve \
  -H 'Content-Type: application/json' \
  -d '{"status":"SUCCESS","channelReference":"ref-converge","reason":null}'
```

## 3. 可观测性检查

```sh
# 支付业务指标（payment-service:8084）
curl -s http://localhost:8084/actuator/prometheus | grep -E 'payment_(succeeded|failed|unknown|duplicate_callback)_total'

# 资金审计日志（支付成功 / 状态迁移会输出 FINANCIAL_AUDIT 行）
# 手动启动时直接看终端；一键启动时：
grep FINANCIAL_AUDIT deployment/logs/payment-service.log
```

预期能看到 `payment_succeeded_total` 递增，以及包含 `from→to` 状态迁移的 `FINANCIAL_AUDIT` JSON 行。

## 4. 关闭本 Feature 的收尾（对应 tasks.md Phase 6）

1. 本地 MySQL 跑通 §2 全链路后，把结果记入 `acceptance.md`。
2. 补齐应用层单测（`OrderApplicationServiceTest.onPaymentSucceeded`）与 `RecordingOrderGateway` 断言（见 tasks T022 / T023）。
3. 落实 FR-009 异常留痕（T024）。
4. 运行 `/review`（支付相关 `/payment-review`）与 `./mvnw verify`，更新 `roadmap.md`（T026–T028）。

## 故障排查

- **建单报 SKU 不可售 / NOT_FOUND**：确认 §2.2 三步都执行，且 `POST /skus/1/activate` 已返回 `status` 为可售态。
- **order → payment RPC 连接失败**：确认 payment-service（8084）已启动，且 order 的 `services.payment.url` 默认 `http://localhost:8084`。
- **本地 MySQL 连不上**：确认 `3306` 可达、`root/root` 正确，且 8 个 schema 已存在（无表没关系，服务用 H2/内存或自身 migration 时不依赖预建表）。
- **不想起 MySQL**：直接用 §1 的 `./mvnw verify`，无需任何外部依赖。
