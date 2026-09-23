---
description: 为改动补测试并运行验证（资金逻辑优先，表驱动测试）
argument-hint: [模块 | 服务 | 改动范围]
---

# 测试

为改动补齐测试并运行验证，遵守 `docs/guides/engineering-standards.md` §4 与 Constitution §Engineering Standards 3。

## 至少考虑

- 正常流程
- 参数异常
- 状态异常
- 重复请求
- 并发请求
- Timeout
- Retry
- DB Failure
- 异步通道 Failure（Redis 事务消息通道，ADR-0074）
- 第三方 / 渠道失败
- Duplicate Message
- Service Restart

支付相关额外检查：

- Duplicate Payment（含 surplus 重复支付 → 自动退款路径）
- Duplicate Callback
- Unknown Payment Status
- Payment Timeout
- Payment Success + Downstream Failure
- Duplicate Refund
- Refund Failure
- Entitlement Grant Failure

## 要求

1. **框架**：JUnit 5 + Mockito + AssertJ。
2. **测试载体（分层，勿一刀切）**：
   - **单元 / 领域测试**：纯单测，无容器。
   - **集成测试默认载体 = H2**（项目既定口径）。
   - **Testcontainers 仅用于需要真库语义的用例**（唯一键竞争、并发时序、方言特性）；判据见 engineering-standards §4 与 [ADR-0081](../../docs/adr/0081-test-carrier-and-schema-replayability.md)。**不得**把所有 Integration Test 都默认改成 Testcontainers。
   - 无 Docker 时真库用例 skip 并显式汇总；CI `real-db` job 无 Docker 即红。
3. **覆盖**：资金逻辑 MUST 有测试；表驱动测试优先；关键路径（支付成功/失败/超时/重复回调）有集成测试。
4. **红线**（Constitution §Engineering Standards 3、§AI Development 3/4）：不得删测试来解决失败；不得改测试（断言改松/改对）迎合错误实现。
5. **架构不变量**：涉包的改动 MUST 通过 `deployment/architecture-tests` 的 ArchUnit 规则。

## 流程

1. 读相关 Spec（四件套）与现有测试，理解状态机与幂等语义，识别要覆盖的路径（含失败与重复分支）。
2. 补单元测试（domain 状态机、幂等、金额计算）与必要集成测试，**按第 2 条选择正确载体**。
3. 运行验证：`./mvnw -pl <module> test`（或 `verify`），确认通过；整体门禁为 `./mvnw -B verify`。
4. 失败时**修实现**或经确认修测试，说明理由；不静默通过。

## 输出

- 新增/修改的测试文件清单（标注使用的载体：单测 / H2 / Testcontainers）。
- 覆盖了哪些关键路径、哪些已知缺口未覆盖（显式说明）。
- 测试运行结果（通过/失败 + 关键输出）。
