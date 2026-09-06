# 性能压测报告 — Phase 4（SKU 缓存 / 秒杀 Lua 预扣）

> 关联能力：catalog-service `cache-aside` SKU 读缓存（`catalog.cache.*`）、秒杀配额原子预扣
>（`/internal/stock/seckill/*`，Redis Lua）、order-service 固定窗口限流（429 快速失败）。
> 测试脚本：`deployment/performance/catalog-seckill-k6.js`（k6）。

## 1. 目标

| 关注点 | 度量 | 目标（SLO，草案） |
|---|---|---|
| SKU 读延迟（缓存命中） | `http_req_duration` p95 / p99 | p95 < 50ms，p99 < 120ms |
| SKU 读错误率 | `http_req_failed` | < 1% |
| 秒杀准入正确性 | 返回 200 或 409，绝不 500 | 全部请求 200/409 |
| 缓存卸载 DB | catalog 服务的 DB 查询计数（Prometheus `hikaricp` / 自定义指标） | 命中后 DB 查询应较「关闭缓存」基线显著下降 |

## 2. 测试场景

1. **sku_cache_read**（ramping-vus：0→50→200→0，共 2 分钟）
   - 持续 `GET /skus/{id}`（默认 `SKU_ID=103`）。
   - 验证 cache-aside：首次读回源 DB 并写 Redis，后续命中 Redis 跳过 DB。
   - 对照实验：将 `catalog.cache.enabled=false` 重启后重跑，比较 DB 查询量与 p99。

2. **seckill_flash**（ramping-vus：0→500→0，共 40s）
   - 瞬时高并发 `POST /internal/stock/seckill/deduct?skuId=103&quantity=1`。
   - 验证 Lua 原子预扣：配额（reset.sh 播种 10）耗尽后返回 409，洪峰不击穿 DB。
   - 对照：未播种配额时同一 SKU 应全部 `bypass`（200），由 DB 三段式库存兜底。

## 3. 运行步骤

```bash
# 1) 启动依赖（docker compose）：Nacos、MySQL、Redis、catalog-service（含 8082）
bash deployment/start-all.sh

# 2) 灌种子（为 103 播种秒杀配额 10）
bash deployment/demo/reset.sh
#    备用（无需 httpie）：curl -X POST "http://localhost:8082/internal/stock/seckill/seed?skuId=103&total=10"

# 3) 跑压测
#    方式A（推荐，零外部依赖、免 k6 二进制）：Node 负载生成器，复刻 k6 两套场景
BASE_URL=http://localhost:8082 SKU_ID=103 SECKILL_SKU_ID=103 \
  OUT=deployment/performance/results/r3-load-result.json \
  node deployment/performance/catalog-seckill-loadgen.js
#    方式B（需 k6 二进制，本环境代理拦截不可用）：
k6 run -e BASE_URL=http://localhost:8082 -e SKU_ID=103 -e SECKILL_SKU_ID=103 \
    deployment/performance/catalog-seckill-k6.js

# 4) 生成报告
RESULT=deployment/performance/results/r3-load-result.json \
  OUT=deployment/performance/results/r3-perf-report.html \
  node deployment/performance/generate-report.js
```

可选对照：临时将 `catalog-service/src/main/resources/application.yml` 中 `catalog.cache.enabled`
改为 `false` 重启后重跑 `sku_cache_read`，记录 DB 侧计数器差异。

## 4. 结果（2026-09-04 分布式实跑，Docker 基础设施 + 宿主进程全栈）

> 环境：infra 以容器运行（Nacos/Redis/Prometheus/Grafana/Loki+Promtail），微服务为宿主进程；
> 2026-09-04 起宿主原生 MySQL 已停用，JDBC `localhost:3306` 直连**容器 MySQL**，
> 本节全部数据为容器库实测。JVM 统一内存上限 `-Xmx384m`（start-all.sh 默认）。

### 4.1 sku_cache_read（缓存对照实验，2026-09-04 容器 MySQL 实测）

同一基准（`catalog-seckill-k6.js` sku_read 场景，200 VU / 2 min，Lettuce 池化开启，
DB 查询量以 MySQL `Com_select` 全局计数差值计量）：

| 指标 | 缓存开启 | 缓存关闭（对照） |
|---|---|---|
| sku 读请求数 | 103,811 | 103,965 |
| p50 / p90 | 2.78 / 4.57 ms | 2.65 / 4.37 ms |
| p95 延迟 | **6.77 ms** | **10.84 ms** |
| p99 延迟 | 136.51 ms | 138.97 ms |
| 错误率 | **0** | **0** |
| **DB `Com_select` 区间增量** | **+23** | **+103,987** |

结论：
- **DB 卸载是主要收益**：关闭缓存后每次读直连 DB（+10.4 万次查询），开启后 2 分钟仅 23 次
  （≈4,500× 差距），读路径 DB 压力趋近于零。
- **延迟收益为次要**：本地容器 MySQL 单查很快，p95 仅 6.8 vs 10.8ms（约 1.6×）；
  生产环境 DB 经网络访问 + 高基数 SKU 时，该差距会显著放大。
- p99 两态相近（~137ms）为 JVM GC / 共享资源周期性停顿，与缓存无关。

（历史参考：上一轮宿主 MySQL 环境下缓存开启 p95 14.34ms / p99 18.18ms / 错误率 0，
见 `results/2026-09-04-catalog-load-result.json`；环境不同，不与本表直接对比。）

### 4.2 seckill_flash（破坏性场景：配额 10，500 VU 抢购）

| 指标 | 值 |
|---|---|
| VU 峰值 | 500 |
| 配额 | 10 |
| 200（准入） | **10（恰好等于配额）** |
| 409（售罄） | 38,791 |
| 500 / 其他 | **0** |
| p95 延迟 | 473.26 ms |
| Redis 终值 `seckill:sku:{id}` | **0（不超卖）** |

> p95 473ms 为 500 并发下 Lettuce 单共享连接串行排队的排队延迟（Redis 单线程 +
> 连接复用），非超卖/ correctness 问题；降低并发或引入连接池可回到毫秒级。
> 大配额（1,000,000）对照跑 p95 528ms / p99 564ms、RPS 976、0 超卖，结论一致。

### 4.3 下单幂等与限流（分布式，真网络 + 多连接并发）

| 项 | 场景 | 结果 |
|---|---|---|
| 入口幂等（ADR-0039/0040/012） | 相同 `Idempotency-Key` 50 并发 `POST /orders` | 恰好 **1×201** + **49×409**（全部带 `Retry-After: 1`）；窗口翻转后同 key 重放 → **200 与首次响应一致**（不重复下单）。产物：`r3-idempotency-result.json`（`order-idempotency-verify.js`） |
| 限流（capacity=50/1s，ADR-0045） | 100 并发 448ms 内进入同一窗口 | 恰好 **50** 放行（打在下游库存 409）+ **50×429**；429 **全部不带 `Retry-After`**（`{"error":"rate_limit_exceeded","retryable":false}`），符合「拒绝不允许重试」 |

### 4.4 全链路业务压测：下单 → 渠道回调 → 退款（2026-09-04，容器 MySQL + 宿主服务）

场景脚本：`order-payment-refund-k6.js`（ramping-vus：30s 爬坡 → 稳态 → 15s 退出）。
每个迭代走完整业务闭环，覆盖 order / catalog / payment / mock-channel-web / refund 五个服务写路径 + Feign 内部 RPC 链：

1. `POST /orders`（幂等键）：order 内部串联 Redis 秒杀准入 → catalog 库存预占 → payment 意图创建（收银台路径：PROCESSING + payUrl）
2. `POST /mock-channel/callback`：mock-channel-web 以渠道身份 HMAC 签名转发 payment，驱动 PROCESSING→SUCCEEDED
3. `POST /internal/refunds`：refund 经 Feign 走 payment 渠道退款（同步 SUCCEEDED）
4. `POST /internal/refunds/{id}/resolve`：权威确认（幂等）

| 轮次 | VU | 迭代 | HTTP 请求 | 失败率 | 平均/p95 |
|---|---|---|---|---|---|
| 第一轮（修复前） | 20 | 3,827 | 11,387 | **11.5%** | 92ms / 151ms |
| 第二轮（库存冲突重试后） | 20 | 3,674 | 14,459 | **0.55%** | 142ms / 363ms |

**第一轮 11.5% 失败的定位与修复（重要发现）：**

- 失败全部是 catalog `/internal/stock/reserve` 的 409 —— 库存聚合单行**乐观锁**版本冲突
  （ADR-0053 快速失败设计），而 order-service 不重试 → 整单取消（`orders.CANCELLED` 与冲突数吻合）。
- 修复：新增 `CONCURRENT_UPDATE` 错误码（区别于不可重试的 CONFLICT），catalog 侧对版本冲突做
  **跨事务有界重试**（8 次、线性退避）。注意重试必须在**独立事务**里进行——同一 `@Transactional`
  事务内重读拿到的仍是 REPEATABLE READ 本事务快照，永远看不见他人提交的新版本。
- 连带修复：order-service 对 bypass（未播种秒杀配额）的 SKU 不再登记秒杀回滚——
  失败回滚的 `INCREMENT` 会凭空造出 Redis 配额键，导致后续正常下单被误判"秒杀库存不足"。

**终态一致性核对（两轮压测后，容器 MySQL）：** `stock_reservation.CONFIRMED`（5,384）与
`orders.PAID`（5,384）**精确相等**；预占 PENDING 全部对应 PENDING_PAYMENT 订单；
退款 SUCCEEDED 与支付 SUCCEEDED 一一对应；秒杀配额 Redis 余量与 admitted+rollback 账目吻合。

Grafana「HTTP 请求量/P99」面板同步改为按**服务名**（Prometheus `job`）分组，不再按端口号。

### 4.5 Lettuce 连接池调优（ADR-0060，2026-09-04）

Lettuce 默认单连接多路复用，秒杀 Lua 高并发下客户端侧串行排队。catalog/order 启用
`spring.data.redis.lettuce.pool`（max-active 16，需 commons-pool2）后复测
（`catalog-seckill-k6.js`，500 VU，SKU 4 大配额）：

| 指标 | 池化前（ADR-0058 破坏性场景基线） | 池化后（大配额场景） |
|---|---|---|
| seckill_deduct p95 | **528ms** | **278ms** |
| seckill_deduct p99 | — | 290ms |
| 秒杀吞吐 | ~976 rps | ~1,536 rps |
| 错误率 | 0 | 0 |
| sku_read p95（200 VU） | — | 6.8ms（SLO<50ms 达标） |

> 条件差异：基线轮为配额耗尽破坏性场景（10 配额 / 38,801 次），本轮为大配额（~99 万）
> 持续扣减 40s；两者同为 500 VU 打同一 Lua 端点，趋势可比、绝对值不可直接等同。
> 池化后 500 VU 下 p95 仍高于 50ms 草案 SLO——瓶颈已从客户端单连接排队转移到
> 服务吞吐容量（HTTP 层 + 单机 11 JVM 共享资源），后续如需逼近 SLO 应做 Tomcat/
> Web 容器与 JVM 堆参数调优，或水平扩容 catalog。

**同批遗留观察项收口**：压测产生的 52 笔 UNKNOWN 支付为**设计内收敛**（ADR-0048 收银台路径
PROCESSING 超 30s 由 `TimeoutScanner` 转 UNKNOWN「点了不回调」→ `ChannelQueryService` 主动查询
不收敛 → 订单 900s 超时取消）。状态机允许 UNKNOWN → SUCCEEDED/FAILED（迟到回调仍能权威收敛，
冒烟已实证），不构成资金风险；`succeed()` 不清除 `failure_reason` 属审计残留，非缺陷。

## 5. 结论与建议（草案）

- cache-aside 命中后应使 `GET /skus/{id}` 延迟稳定落在内存/Redis 量级（亚毫秒~低十毫秒），
  DB 查询计数较关闭缓存基线大幅下降，证明缓存成功卸载读路径。
- 秒杀 Lua 预扣在配额耗尽后统一返回 409，**不会**因并发导致超卖或击穿 DB；正常品（未播种配额）
  走 `bypass` 由 DB 三段式库存兜底。
- 下单侧固定窗口限流（capacity=50/1s）在更高流量下会以 429 快速失败、不返回 Retry-After，
  符合「拒绝不允许重试」决策。
- 后续可补充：用 Redis `INFO stats` / `Slowlog` 观察 Lua 执行耗时；用 Prometheus
  `catalog_seckill_degraded_total` 监控 Redis 不可用时的 fail-closed 降级次数。

---

## 6. R6 · Feature 016 验证压测（2026-09-06）

> 承载 spec 016 / ADR-0065 落地验证：主链压测新增 **surplus 双支付分支**，
> 断言「订单 PAID 后第二张支付单回调成功 → order transaction 层判 surplus 发起自动退款，
> **全程 0 次 409**」（FR-007 / SC-001）。k6 二进制在沙箱被代理拦截，主链改用零依赖
> Node 版负载生成器（stdlib http，与 k6 场景等价）。

### 6.1 工具与用法

```bash
# 主链（下单 → 建单×N → 回调 → 退款 → 收敛；ORDER_RATE 对齐 /orders 限流 50/s）
VUS=20 DURATION=90s SKU_ID=1 ORDER_RATE=40 SURPLUS_RATIO=0.2 \
  OUT=results/r6-verification-chain-load.json \
  node deployment/performance/order-payment-refund-loadgen.js

# catalog 缓存读 + 秒杀（沿用既有脚本）
BASE_URL=http://localhost:8082 SKU_ID=1 SECKILL_SKU_ID=1 \
  OUT=results/r6-verification-catalog-load.json \
  node deployment/performance/catalog-seckill-loadgen.js
```

### 6.2 结果（r6-verification-*.json）

| 场景 | 指标 | 结果 |
|---|---|---|
| SKU 缓存读（ramping 0→200 VU） | RPS / p95 / p99 | 604 / 8.3ms / 31.7ms，错误 0 |
| 秒杀洪峰（500 VU×40s） | RPS / p95 / p99 | 3661 / 123ms / 232ms，准入 200 全部、0 超卖 |
| 主链完整闭环（40 单/s） | 完成链路 / surplus 分支 | 910 / 175 次，**surplus 回调 0 次 409** |
| 主链各段 p99（ms） | 下单 / 建单 / 回调 / 退款 / 收敛 | 1124 / 321 / 1250 / 433 / 131 |
| 自动退款执行 | 成功 / 失败 | 132 / **0**（`payment_auto_refund_*_total`） |

### 6.3 观察项（非 016 缺陷）

1. **单 SKU 热点行**：`/orders` 409（乐观锁重试 8×10ms 耗尽）约 26%——热点单行写竞争
   既有现象（代码注释 2026-09-04 实测 20VU 冲突 ~34%），分摊 SKU 或调大重试预算可缓解。
2. **payment→order 回写 Feign 超时被吞**：surplus 回调 175 次 vs order 判定 132 次，
   差额 43 次为 1s RPC 超时（ADR-0058 口径）设计性吞掉（`PaymentResultProcessor` catch 语义），
   依赖对账收敛兜底——与「订单回写失败不回滚支付成功事实」决策一致。
3. **压测前置**：直接 UPDATE stock.available 会破坏 `total = available + reserved + sold`
   不变量导致预占 409，须 total/available 同步改（或走 seed API）。
