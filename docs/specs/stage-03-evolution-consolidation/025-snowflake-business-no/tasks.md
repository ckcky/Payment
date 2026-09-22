# Tasks: 025-snowflake-business-no

> **历史文档提示（2026-09-22 文档治理）**：本文为历史记录，保留当时的设计划分；其中的 `refund-service` **已并入 payment-service**（ADR-0064，退款域现位于 payment-service 内）。**当前系统事实**见 [docs/architecture/systems/](../../../architecture/systems/) 与 [technical-solution.md](../../../architecture/technical-solution.md)。

- [x] T1 common-core: SnowflakeIdWorker / BusinessNoType / BusinessNos + 单测
- [x] T2 order-service: orderNo(OR) + transactionNo(TX)；payments.transaction_id 改存 TX 单号
- [x] T3 payment-service: paymentNo(PM)（领域/实体/仓储/DTO/DDL/H2）
- [x] T4 refund-service: refundNo(RF)
- [x] T5 settlement-service: batchNo(SB)
- [x] T6 reconciliation-service: batchNo(RB)
- [x] T7 ledger-service: postingNo(LP)（含 common-dto PostingResponse 加字段）
- [x] T8 ADR-0062 + 全量测试
