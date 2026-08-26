---
description: 为改动补测试并运行验证（资金逻辑优先，表驱动测试）
argument-hint: [模块 | 服务 | 改动范围]
---

# 测试

为改动补齐测试并运行验证，遵守 `docs/guides/engineering-standards.md` §4。

## 至少考虑：

- 正常流程
- 参数异常
- 状态异常
- 重复请求
- 并发请求
- Timeout
- Retry
- DB Failure
- MQ Failure
- 第三方失败
- Duplicate Message
- Service Restart

支付相关额外检查：

- Duplicate Payment
- Duplicate Callback
- Unknown Payment Status
- Payment Timeout
- Payment Success + Downstream Failure
- Duplicate Refund
- Refund Failure
- Entitlement Grant Failure

## 要求

1. **框架**：JUnit 5 + Mockito + AssertJ；集成测试用 Testcontainers（MySQL 等真实依赖）。
2. **覆盖**：资金逻辑 MUST 有测试；表驱动测试优先；关键路径（支付成功/失败/超时/重复回调）有集成测试。
3. **红线**（Constitution §7.3/7.4）：不得删测试来通过；不得改测试（断言改松/改对）迎合错误实现。

## 流程

1. 读相关 Spec 与现有测试，理解状态机与幂等语义，识别要覆盖的路径（含失败与重复分支）。
2. 补单元测试（domain 状态机、幂等、金额计算）与必要集成测试。
3. 运行验证：`./mvnw -pl <module> test`（或 `verify`），确认通过。
4. 失败时**修实现**或经确认修测试，说明理由；不静默通过。

## 输出

- 新增/修改的测试文件清单。
- 覆盖了哪些关键路径、哪些已知缺口未覆盖（显式说明）。
- 测试运行结果（通过/失败 + 输出）。
