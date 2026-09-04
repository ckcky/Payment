# Plan: 015-snowflake-business-no

实现顺序：common-core 组件 → order（含 transactionNo 跨服务语义）→ payment → refund →
settlement → reconciliation → ledger。每服务统一五件套：领域字段、实体、仓储映射、
DDL 加列（MySQL + H2）、DTO。详见 ADR-0062 Decision。
