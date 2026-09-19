<a id="adr-0061"></a>

- **状态**：Accepted（2026-09-04，演示环境 /demo 面板排障驱动）
- **日期**：2026-09-04
- **关联**：`ADR-0018`（可观测性补全）、`ADR-0048 修订`（mock-channel-web / 同源代理）、`ADR-0049`（Mock 渠道场景）、`ADR-0059`（Nacos）、`ADR-0060`（Lettuce 连接池）

## ADR-0061：可观测性补全与演示脚本漂移修复

### Context（背景）

2026-09-04 演示排障中发现一组叠加问题，导致 `/demo` SKU 列表 500、Grafana「结算 / 对账」「HTTP P99」面板长期无数据：

1. **Grafana HTTP P99 面板无数据（配置缺失）**：各服务未开启 HTTP 请求直方图，Prometheus 中
   不存在 `http_server_requests_seconds_bucket` 系列，`histogram_quantile` 无从计算。
2. **计数器命名撞 OpenMetrics 保留后缀（命名缺陷）**：以 `.created` 结尾的业务计数器
   （`payment.created` / `refund.created` / `order.created` / `settlement.created`）经
   Prometheus 客户端转换后 `_created` 段被剥离（OpenMetrics 中 `_created` 是计数器创建
   时间戳的保留后缀），实际暴露为 `payment_total` / `refund_total` / `order_total` /
   `settlement_total`。看板查询 `*_created_total` 永远无数据，且 `_total` 裸名语义歧义。
3. **对账批次关闭落库失败（类型缺陷）**：`reconciliation_batches.closed_at` 为 DATETIME 列，
   应用层以 `Instant.toString()` 的 ISO 字符串（含 `T`/`Z`）直写，MySQL 8 报
   `Incorrect datetime value`，关闭批次 500。
4. **收银台路径的支付尝试无法权威收敛（状态机缺口）**：cashier 路径（ADR-0048 修订版）下
   支付尝试停留 `PENDING`（未取得渠道引用），TimeoutScanner 将支付置 UNKNOWN 后，
   `resolve` 人工收敛触发 `PaymentAttempt.fail()`，状态机抛 `illegal fail from PENDING`。
5. **演示脚本漂移（ADR-0048 修订后遗留）**：ledger 断言路径 `/balance`（实际
   `/internal/ledger/balance`）；退款场景假设同步 charge 返回 `CREATED`（现网同步收敛为
   `SUCCEEDED`）、超额退款断言 HTTP 409（现网为 200 + `REJECTED` 记录）；对账场景 period
   仅到日粒度（同日重跑复用已关闭批次，门禁反例失效）；`restart-payment.sh` 以 `.pids`
   首个陈旧 PID 终止进程（Windows 下 Git Bash `kill` 跨会话无效），`-D` 场景属性未传入
   fork 的 JVM（须走 `spring-boot.run.jvmArguments`）。

### Decision（决策）

1. **开启 HTTP 请求直方图**：11 个服务（9 微服务 + ledger + mock-channel-web）
   `application.yml` 统一增加
   `management.metrics.distribution.percentiles-histogram.http.server.requests: true`。
2. **业务计数器改名，避开保留后缀**（代码、测试、看板、文档同步）：
   | 旧名（受损暴露） | 新名（实际暴露） |
   | --- | --- |
   | `payment.created` → `payment_total` | `payment.initiated` → `payment_initiated_total` |
   | `refund.created` → `refund_total` | `refund.initiated` → `refund_initiated_total` |
   | `order.created` → `order_total` | `order.initiated` → `order_initiated_total` |
   | `settlement.created` → `settlement_total` | `settlement.batch_initiated` → `settlement_batch_initiated_total` |
   命名纪律：**计数器名不得以 `created` 段结尾**（OpenMetrics `_created` 保留）。
3. **对账关闭时间在持久化边界转换**：领域/API 保持 ISO 展示；仓储落库时将 ISO 字符串转为
   MySQL DATETIME 兼容格式（UTC，`yyyy-MM-dd HH:mm:ss`），非 ISO 值原样透传交由数据库校验。
4. **支付尝试状态机放宽权威收敛来源态**：`PaymentAttempt.succeed()/fail()` 允许从
   `PENDING` 收敛终态（收银台在途尝试可直接吸收权威结果——人工裁定 / 迟到回调）；
   `markUnknown()` 既有行为不变。乐观锁与终态吸收语义不变。
5. **演示脚本对齐现网契约**：ledger 路径改 `/internal/ledger/*`；退款创建状态容忍
   `CREATED|SUCCEEDED`、超额断言改 200 + `REJECTED`；对账 period 增至秒级
   （`demo-YYYYMMDDHHMMSS`）；`restart-payment.sh` 改为按端口（8084）定位 Windows PID 并
   `taskkill` 兜底、等待端口释放、经 `spring-boot.run.jvmArguments` 注入场景属性；
   `run-all.sh` 统一兜底导出演示环境变量（cashier / admin token / channel secret）；
   对账场景补「创建结算批」步骤（对账 CLOSED → 同周期结算，验证 ADR-0023 闸门并产生
   结算指标）；`lib.sh` 兼容沙箱 `MSYS_NO_PATHCONV`（Windows curl `-o /dev/null` 失效）。

### Consequences（后果）

- Grafana 全部业务面板（支付/退款/履约权益/结算对账/秒杀/HTTP P99）数据链路打通。
- `*_total` 裸名不再产生；旧名（`payment_total` 等）在历史数据中残留，无消费方，不迁移。
- HTTP 直方图为每服务增加约 60+ 桶序列，内存/抓取开销可忽略（单机演示规模）。
- `PENDING → 终态` 放宽仅限**权威结果**路径（resolve / 回调 / 查询收敛），不影响内联
  charge 主链；PaymentAttempt 相关测试保持通过。
- 演示脚本与现网契约重新对齐；后续接口契约变化时需同步 `deployment/demo/*`
  （脚本断言失败即非零退出的纪律不变）。
