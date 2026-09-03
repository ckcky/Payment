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
> JDBC 的 `localhost:3306` 实际解析到**宿主原生 MySQL 8.0.46**（WorkBuddy local-mysql，先于
> Docker 占用 127.0.0.1:3306，容器 MySQL 被架空）——与 Phase 4 基线同源，数据可比。
> JVM 统一内存上限 `-Xmx384m`（start-all.sh 默认）。

### 4.1 sku_cache_read（缓存开启）

| 指标 | 缓存开启（r3 实跑） | 缓存关闭（基线） |
|---|---|---|
| VU 峰值 | 200 | 200 |
| 总请求数 | 34,183 | 未复测（Phase 4 参考：33,627 次读仅 5 次取 DB 连接，卸载率 99.98%） |
| p95 延迟 | **14.34 ms** | _待补_ |
| p99 延迟 | **18.18 ms** | _待补_ |
| 错误率 | **0** | _待补_ |
| catalog DB 查询（区间计数） | 未复测 | — |

（同日另一次配额 1,000,000 的对照跑：p95 8.99ms / p99 14.68ms，见
`results/2026-09-04-catalog-load-result.json`。）

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

## 5. 结论与建议（草案）

- cache-aside 命中后应使 `GET /skus/{id}` 延迟稳定落在内存/Redis 量级（亚毫秒~低十毫秒），
  DB 查询计数较关闭缓存基线大幅下降，证明缓存成功卸载读路径。
- 秒杀 Lua 预扣在配额耗尽后统一返回 409，**不会**因并发导致超卖或击穿 DB；正常品（未播种配额）
  走 `bypass` 由 DB 三段式库存兜底。
- 下单侧固定窗口限流（capacity=50/1s）在更高流量下会以 429 快速失败、不返回 Retry-After，
  符合「拒绝不允许重试」决策。
- 后续可补充：用 Redis `INFO stats` / `Slowlog` 观察 Lua 执行耗时；用 Prometheus
  `catalog_seckill_degraded_total` 监控 Redis 不可用时的 fail-closed 降级次数。
