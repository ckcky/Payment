# ADR-0062 业务单号统一采用"两字母前缀 + 雪花算法"

<a id="adr-0062"></a>

- 状态：Accepted
- 日期：2026-09-04
- 关联：ADR-0023（结算）、ADR-0019/0020（对账）、ADR-0048（演示）

### Context（背景）

用户需求：所有系统的单号统一用雪花算法生成，前面加两个字母作为系统标识。

现状（调研结论）：

- 全项目**没有统一单号生成器**。各表主键为 `BIGINT AUTO_INCREMENT`，由 MySQL 生成；
  跨服务传的 `transactionId` 是 `Long → String` 透传，无业务语义、可被猜测顺序（枚举攻击面）。
- 各单据（订单/支付/退款/结算批/对账批/记账流水）只有内部自增 id，对外展示与客服检索缺一个
  全局唯一、带业务语义的单号。

### Decision（决策）

1. **单号格式**：`两字母前缀 + 雪花 ID（十进制）`，总长 20~21 字符，各单号列
   `VARCHAR(32) + UNIQUE KEY`。

   | 前缀 | 系统/单据 | 生成方 |
   |---|---|---|
   | TX | 交易单 | order-service（创建 Transaction 时） |
   | OR | 订单 | order-service |
   | PM | 支付单 | payment-service |
   | RF | 退款单 | refund-service |
   | SB | 结算批 | settlement-service |
   | RB | 对账批 | reconciliation-service |
   | LP | 记账流水 | ledger-service |

2. **实现位置**：common-core 新增 `com.payment.common.core.id` 包——
   `SnowflakeIdWorker`（标准 41+5+5+12 位布局，synchronized 线程安全）、
   `BusinessNoType`（前缀枚举）、`BusinessNos`（进程级单例门面）。

3. **workerId 分配**：优先环境变量 `PAYMENT_WORKER_ID`（0~31），否则
   `server.port % 32` 派生、datacenterId 固定 1。单机多进程端口互异 → workerId 天然错开，
   不引入额外协调组件（最简方案）。

4. **时钟回拨**：回拨 ≤ 5ms 自旋等待；> 5ms 抛异常拒绝生成（宁可失败不可重复）。

5. **侵入边界（最小改动）**：单号是**新增的对外展示字段**（orderNo/paymentNo/refundNo/
   batchNo/postingNo），各表主键与跨服务引用**保持 Long 自增不变**，DDL/实体/DTO 均为
   加列（加字段），存量 API 向后兼容。唯一例外：`payments.transaction_id`（本就是
   VARCHAR(64) UNIQUE）改存 TX 单号——它本来就是跨服务交易标识，语义更准确。

6. **不做的**：fulfillment/entitlement 无对外单号语义（从属于订单），不加单号；
   不改主键生成策略（自增主键 + 唯一单号是支付系统常见分层：内部join快，外部不泄露量级）。

### Consequences（后果）

- 对外暴露的单号不可枚举（雪花非顺序可猜），客服可按前缀一眼识别单据类型。
- 多机部署时 workerId 需保证唯一（环境变量注入或扩容规划），当前单机演示天然满足。
- 新增 41 位时间戳基准 EPOCH=2025-01-01，可用约 69 年。
- 测试：H2 schema 同步加列；`BusinessNosTest` 覆盖并发唯一性/格式/前缀。

### Verification（验证）

- common-core 37 测试（含雪花并发 40k 无重复）。
- 受影响 6 服务（order/payment/refund/settlement/reconciliation/ledger）全量测试通过。
