# Acceptance: 015-snowflake-business-no

- BusinessNosTest：40k 并发唯一、格式校验、前缀枚举、批量无重复 —— 全绿。
- 6 服务 clean test 全绿（H2 schema 同步加列）。
- 下单响应示例：orderId=1, orderNo=OR..., transactionId=..., transactionNo=TX...,
  paymentId=1, payUrl 收银台正常。
