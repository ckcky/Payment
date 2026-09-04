# Spec: 015-snowflake-business-no

- 版本: v1
- 日期: 2026-09-04
- 状态: Implemented（分支 feature/snowflake-order-no）
- 输入: 用户需求——所有系统的单号统一用雪花算法生成，前面加两个字母作为系统标识
- 关联: ADR-0062

## 背景

现有各单据只有 BIGINT 自增主键，跨服务 transactionId 为 Long 转字符串透传：无业务语义、
顺序可枚举、客服检索困难。

## 目标

- common-core 提供统一雪花单号组件（SnowflakeIdWorker / BusinessNoType / BusinessNos）。
- 七类单据接入：TX 交易、OR 订单、PM 支付、RF 退款、SB 结算批、RB 对账批、LP 记账流水。
- 单号格式 `前缀(2 字母) + 雪花 ID`，长度 20~21，DB 列 VARCHAR(32) + UNIQUE。
- 对外 API 响应携带单号字段；存量字段与行为向后兼容。

## 非目标

- 不改各表主键生成策略（保持自增）。
- 不做 fulfillment/entitlement 单号（从属单据，无对外单号语义）。
- 不做多机房 datacenterId 规划（单机演示，workerId=port%32）。

## 需求

- R1 单号全局唯一、进程内线程安全、批量并发无重复。
- R2 时钟回拨 ≤5ms 自旋等待；>5ms 拒绝生成（防重复）。
- R3 workerId 可用环境变量 PAYMENT_WORKER_ID 覆盖，默认 port%32。
- R4 单号持久化唯一约束兜底；仓储重建（rehydrate）还原持久化值。

## 验收

- 并发生成 40k ID 无重复；单号格式/前缀校验单测通过。
- 受影响 6 服务 + common-core 全量测试绿。
