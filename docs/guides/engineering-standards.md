# 工程规范（Engineering Standards）

> 对应交付物 D。把 Constitution 的原则落到 Java / Spring Cloud 的具体约束。本文是**规范**，用于编码、测试、Review、CI 的统一判据。

## 1. 代码质量（Code Quality）

- **风格**：Checkstyle + Spotless（统一格式化）；命名遵循 Java 惯例，包名 `com.payment.<service>.<layer>`。
- **分层**：`api → application → domain ← infra`，依赖单向；`domain` 不依赖任何框架层。
- **DTO / Entity 分离**：对外 API 用 DTO，持久层用实体，二者不混用；跨服务只传 DTO / 事件。
- **错误处理**：统一错误码 + 异常分层（`BizException` 业务 vs `SystemException` 系统）；全局异常处理器兜底；错误信息对调用方明确。
- **对象**：值对象（Money、幂等键、状态）不可变；实体有清晰的生命周期。

## 2. 资金正确性（Money Invariants，最高优先级）

- 金额用 **`long`（最小单位分）或 `BigDecimal`（明确 scale）** 表示；**禁止 `float` / `double`**（Constitution §2.2）。
- 封装 `Money` 值对象（金额 + 币种），禁止裸 `long` 满天飞。
- 任何资金变动必须经 `ledger-service` 复式记账，借贷平衡；**禁止**直接改余额字段（Constitution §2.2）。

## 3. 一致性（Consistency，Constitution §4）

- **幂等**：支付/退款/结算入口必须有幂等键，数据库唯一约束兜底；重复请求不产生重复资金动作。
- **状态机**：Order/Payment/Refund/Fulfillment/Entitlement/Settlement 用显式单向状态机，集中在 `domain` 的状态转换函数，禁止散落直接 set 状态。
- **分布式一致性**：跨服务用 **Saga + 同步 RPC + 幂等重试**；当前不引入 MQ 或跨服务异步事件；**禁止** 2PC/XA 分布式事务。
- **未知支付状态**：结果不确定时进 UNKNOWN 状态，靠查询接口 / 对账 / 人工收敛，**禁止**猜成败直接落账。
- **事务边界**：`@Transactional` 只放在 `application` 应用服务层，且只覆盖**单服务本地事务**。

## 4. 测试（Testing）

- **框架**：JUnit 5 + Mockito + AssertJ；集成测试用 Testcontainers（MySQL 等真实依赖）。
- **覆盖**：资金逻辑 MUST 有测试；表驱动测试优先；关键路径（支付成功/失败/超时/重复回调）有集成测试。支付重点覆盖：重复请求、重复回调、支付超时、支付状态未知、渠道失败、重试、重复消息、服务重启、最终一致性。
- **红线**：不得删测试来通过；不得改测试迎合错误实现（Constitution §7.3/7.4）。
- **跨服务调用**：通过公开 HTTP/RPC 用例；调用方和被调用方都必须处理超时、重试和幂等。服务内部事件不作为跨服务通信。
- **契约**：跨服务 RPC 接口变更后，用契约测试或明确联调验证，避免静默破坏。

## 5. 文档与 ADR

- 重要/不可逆决策 MUST 立 ADR（`docs/adr/NNNN-*.md`），否则视为未决策。
- 每个特性有 Spec（`docs/specs/<feature>/spec.md`）；代码与 Spec 不一致时，先判断是需求变更还是实现缺陷，再同步修订。
- 关键业务逻辑（状态机、幂等、账本）在代码内写清「为什么」的注释。

## 6. CI / CD

- **Maven Wrapper（mvnw）** 锁定 Maven 版本，保证构建可复现。
- **CI 流水线**：`mvnw verify`（compile + test）→ lint（checkstyle/spotless）→ 打包。
- **发布**：产物确定性；配置与代码分离（配置走 Nacos / 环境变量）。
- **Git**：Conventional Commits；功能分支 + PR；合并前 Review。

## 7. 可观测（Observability，Constitution §6）

- **Metrics**：Micrometer 暴露请求量、延迟、错误率、关键业务计数（支付成功/失败/超时/退款）。
- **Logs**：结构化日志（logback，含 traceId、orderId、paymentId 关联字段）；资金动作有审计日志；敏感字段脱敏。
- **Traces**：Micrometer Tracing 在跨服务调用传播 traceId/spanId。
- **告警/SLO**：对「支付状态未知堆积」「对账差异」「退款失败」「重试耗尽」配业务告警；核心接口定义 SLO。

## 8. 安全（Security）

- 密钥、签名、金额等**禁止硬编码**，走环境变量 / 配置中心 / 密钥管理。
- 渠道回调 MUST 验证签名与来源，防止伪造回调。
- 对外 API 有鉴权（Spring Security / OAuth2）与输入校验（Bean Validation）。
- 敏感信息（卡号、密钥）进日志前脱敏。

## 9. 依赖管理（Dependency Management）

- 父 POM + `dependencyManagement` 统一版本（Spring Boot BOM + Spring Cloud BOM 对齐）。
- **最小化依赖**：每个新依赖 MUST 有理由（ADR 或 commit 说明）；禁止「顺手引入」。
- 版本冲突以父 POM 的 BOM 为准，不散落各自声明。

## 10. 向后兼容（Backward Compatibility）

- 已发布的对外 API / 跨服务接口变更 MUST 向后兼容或提供迁移路径（Constitution §5.9）。
- 破坏性变更 MUST 经人类确认（Constitution §8「API Breaking Change」）。
