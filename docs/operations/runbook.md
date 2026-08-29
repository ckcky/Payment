# 运行手册（Runbook）

**适用版本**：PaymentArch 0.1.0-SNAPSHOT（Roadmap Phase 0~10）
**最后更新**：2026-08-30
**配套文档**：`docs/architecture/roadmap.md`、`docs/adr/`、`docs/specs/`

> 本手册是 Phase 10 验收标准（ADR-0033）要求的运行手册。它只描述**当前实际形态**：单台机器、单一 MySQL 实例、10 个 JVM 进程、跨服务同步 HTTP/Feign。任何拆分或部署形态变更必须先走 `split-proposal-template.md` 评审，并在通过后回来更新本手册。

---

## 1. 服务清单与端口

| 等级 | 服务 | 端口 | Schema | 下游 Feign 依赖 | 健康端点 |
| --- | --- | --- | --- | --- | --- |
| T3 | `merchant-service` | 8081 | 无（内嵌存储，重启清空） | — | `/actuator/health` |
| T3 | `catalog-service` | 8082 | `catalog` | — | `/actuator/health` |
| T1 | `order-service` | 8083 | `order` | catalog, payment | `/actuator/health` |
| T1 | `payment-service` | 8084 | `payment` | fulfillment, ledger, order | `/actuator/health` |
| T1 | `refund-service` | 8085 | `refund` | entitlement, fulfillment, ledger, payment | `/actuator/health` |
| T2 | `fulfillment-service` | 8086 | `fulfillment` | entitlement | `/actuator/health` |
| T2 | `entitlement-service` | 8087 | `entitlement` | — | `/actuator/health` |
| T1 | `reconciliation-service` | 8088 | `reconciliation` | payment, refund | `/actuator/health` |
| T1 | `settlement-service` | 8089 | `settlement` | ledger, merchant, reconciliation | `/actuator/health` |
| T0 | `ledger-service` | 8090 | `ledger` | — | `/actuator/health` |

- 全部服务暴露 `/actuator/health`、`/actuator/info`、`/actuator/metrics`、`/actuator/prometheus` 与 Swagger UI。
- 关键等级（T0~T3）定义见 `docs/adr/0010-distributed-evolution-decisions.md` ADR-0032。

## 2. 启动顺序

依赖方向决定启动顺序（先起被依赖方，可减少启动期熔断）：

```text
MySQL 8 (localhost:3306)
   ↓
ledger-service (8090)  ← 资金事实底座，最先起
   ↓
entitlement-service (8087) → fulfillment-service (8086)
   ↓
payment-service (8084) → order-service (8083)
   ↓
refund-service (8085) → reconciliation-service (8088) → settlement-service (8089)
   ↓
merchant-service (8081)、catalog-service (8082)（无下游依赖，任意时机）
```

启动后自检：`GET http://localhost:<port>/actuator/health` 逐个确认 `UP`。

## 3. 数据库

- 单实例 MySQL 8（`localhost:3306`，root/root），数据库按服务使用独立 **Schema**：`catalog` / `order` / `payment` / `refund` / `fulfillment` / `entitlement` / `reconciliation` / `settlement` / `ledger`。`merchant-service` 无库，使用内嵌存储（**重启即清空**）。
- 建表脚本：`deployment/schema/`。
- **跨服务零写路径**：任何服务只读写自己的 Schema，跨服务只经 HTTP。该约束由 `architecture-tests` 在构建期强制（ADR-0029）。

## 4. 环境变量（密钥）

Phase 9 引入的密钥一律经环境变量注入，**禁止硬编码、禁止入库、禁止明文日志**（ADR-0026）：

| 环境变量 | 用途 | 未配置时的行为 |
| --- | --- | --- |
| `PAYMENT_ADMIN_TOKEN` | `/payments/{id}/resolve` 人工收敛端点的 `X-Admin-Token` | 端点返回 `503`（拒绝而非放行） |
| `PAYMENT_INTERNAL_TOKEN` | `/internal/**` 内部端点的 `X-Service-Token` | 开关默认关闭；一旦开启而未配置 → `503` |
| `PAYMENT_CHANNEL_SECRET` | 渠道回调 HMAC-SHA256 验签密钥 | 回调端点返回 `503`（验签不可关闭） |
| `PLATFORM_INTERNAL_TOKEN` | **全平台共享**的内部服务令牌；出站侧由 Feign 拦截器附加到 `/internal/**` 调用，入站侧由 payment 校验 | 出站不加头（对端开启鉴权后会 403）；payment 入站在 `PAYMENT_INTERNAL_TOKEN` 也缺失时 → `503` |

**令牌优先级（ADR-0034）**：payment 入站取「首个非空」——`PAYMENT_INTERNAL_TOKEN` → `PLATFORM_INTERNAL_TOKEN`。**只注入 `PLATFORM_INTERNAL_TOKEN` 即可两端同源**；`PAYMENT_INTERNAL_TOKEN` 仅作为本服务覆盖值保留。

**开启内部端点鉴权的正确顺序**（顺序错了会全站 403）：

1. 全平台（payment + 5 个调用方）注入同一把 `PLATFORM_INTERNAL_TOKEN`；
2. 先开 `platform.security.outbound-token-enabled=true` 并滚动重启**调用方**，确认出站已带令牌；
3. 再开 `payment.security.internal-auth-enabled=true` 重启 payment；
4. 观察 `payment.internal_auth_rejected` 的 `reason` 维度应归零。

> 注意：拦截器的目标判定是**路径含 `/internal/` 段**，因此把内部令牌发给外部/渠道 URL 的情况不会发生；但反过来，若将来新增一个"路径不含 `/internal/` 但需鉴权"的端点，拦截器不会覆盖（见 ADR-0034 待确认项 2）。

相关配置：`payment-service/src/main/resources/application.yml` 的 `payment.resolve.*`、`payment.security.*`、`payment.risk.*`；各服务的 `platform.security.*`。

## 5. 关键指标

| 指标 | 含义 | 关注点 |
| --- | --- | --- |
| `payment.succeeded` / `payment.failed` / `payment.unknown` | 支付终态分布 | `unknown` 持续升高说明渠道不稳定，需人工收敛 |
| `payment.duplicate` / `payment.duplicate_callback` | 幂等命中 | 突增可能是上游重试风暴 |
| `payment.callback_signature_rejected` | 渠道回调验签被拒（带 `reason` 维度） | **出现即需排查**：可能是密钥不一致或伪造回调 |
| `payment.risk_triggered` | 最小风控命中（带 `rule` 维度） | 默认配置下不启用；启用后只记录不拦截 |
| `payment.internal_auth_rejected` | 内部端点鉴权被拒（带 `reason` 维度） | `unconfigured`=配置故障；`missing_token`=某调用方出站拦截器没开；`token_mismatch`=令牌不同源或已轮换 |
| `ledger.posting_failed` | 记账失败 | 出现后资金事实与账本不一致，需补记账 |
| `FINANCIAL_AUDIT` 日志 | 资金动作审计（独立 logger） | 支付/退款/结算/记账各一条，含 traceId |

## 6. 常见故障与处置

| 现象 | 可能原因 | 处置 |
| --- | --- | --- |
| `POST /payments` 500 `DuplicateKeyException` | `MockChannelAdapter` 重启后渠道引用从 1 重新计数，撞 `payment_attempts.uk_attempts_channel_reference` | 已用运行级 UUID 前缀修复；若仍出现，清空历史 `payment_attempts` 后重启 |
| 支付长时间 `UNKNOWN` | 渠道超时后主动查询未收敛 | 用 `POST /payments/{id}/resolve` 带 `X-Admin-Token` 人工裁定（仅接受 SUCCESS/FAILURE） |
| 渠道回调全部 `403` | 验签密钥不一致 / 时间戳超窗 / 未配 `PAYMENT_CHANNEL_SECRET` | 核对 `X-Channel-Timestamp`（毫秒）与 5min 窗口；核对密钥；看 `payment.callback_signature_rejected` 的 `reason` |
| 回调验签通过但 Controller 报 body 为空 | 过滤器消费了原始 body 却未换包装器 | 不应发生（`CachedBodyHttpServletRequest` 已处理）；若出现检查 `WebConfig` 过滤器注册 |
| 内部端点 `403`（`reason=missing_token`） | 对端开启了 `internal-auth-enabled`，但调用方未开 `platform.security.outbound-token-enabled` 或未注入 `PLATFORM_INTERNAL_TOKEN` | 按「开启内部端点鉴权的正确顺序」§4 复核；出站拦截器只对含 `/internal/` 段的目标生效 |
| 内部端点 `403`（`reason=token_mismatch`） | 两端令牌不同源，或轮换过程中新旧值混用 | 统一 `PLATFORM_INTERNAL_TOKEN`；轮换需全平台同时切换（ADR-0036 不支持平滑轮换） |
| 内部端点 `503`（`reason=unconfigured`） | 开关开了但令牌为空 | 注入 `PLATFORM_INTERNAL_TOKEN`（或 `PAYMENT_INTERNAL_TOKEN`）；`unconfigured` 意味着整条内部调用链已断，应优先告警 |
| 对账差异全为 `PLATFORM_ONLY` | 渠道账单是静态 fixture（`sample.csv`），真实渠道引用带 runId 前缀 | 设计内表现，非故障；如需复位用 `.workbuddy/tools/cleanup.py` |
| `orders` 表缺 `payment_id` 导致 order 500 | 用户库是旧 schema 建的 | `ALTER TABLE orders ADD COLUMN payment_id BIGINT NULL` |
| `GlobalExceptionHandler` 把异常吞成 `INTERNAL_ERROR` | 未捕获异常统一转 `{"code":"INTERNAL_ERROR"}` | 定位需看服务 err 日志或临时加堆栈输出（定位后还原） |

## 7. 回滚

当前形态（单实例 + 独立 Schema）下的回滚粒度按「Schema + 服务」成对处理：

1. **停服**：先停上游（order/payment/refund），再停下下游（ledger/entitlement 最后停）。
2. **回滚 Schema**：`deployment/schema/` 中的变更脚本需配套 down 脚本；无 down 脚本时从备份恢复对应 Schema。
3. **回滚代码**：替换 jar 后按第 2 节顺序重启。
4. **账本特别处理**：`ledger-service` 是资金事实的最终来源，**回滚前必须先确认没有产生新 Posting**；若已产生，优先用补偿分录而不是删数据（账本分录只追加）。

## 8. 容量与拆分触发条件

当前**不拆**任何服务。达到下列任一条件才启动拆分评估（ADR-0030）：

- **容量**：单实例 CPU 持续 > 70%，或连接数长期 > 最大连接数 70%，且垂直扩容已到性价比拐点。
- **隔离**：某服务的慢查询 / 锁等待 / 跑批（结算、对账扫描）实质性影响其它服务 P99。
- **合规/归属**：某服务需要独立备份策略、retention 或访问控制边界（首推 `ledger`）。
- **可用性**：某服务需要独立主从切换或跨可用区部署，共享实例无法满足 RTO/RPO。

触发后按 `split-proposal-template.md` 填写提案，四段（问题/收益/成本/回滚）缺一不予评审。
