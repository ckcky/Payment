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
# 1) 启动依赖（docker compose）：MySQL、Redis、catalog-service（含 8082）
bash deployment/start-all.sh

# 2) 灌种子（为 103 播种秒杀配额 10）
bash demo/reset.sh

# 3) 跑压测
k6 run -e BASE_URL=http://localhost:8082 -e SKU_ID=103 -e SECKILL_SKU_ID=103 \
    deployment/performance/catalog-seckill-k6.js
```

可选对照：临时将 `catalog-service/src/main/resources/application.yml` 中 `catalog.cache.enabled`
改为 `false` 重启后重跑 `sku_cache_read`，记录 DB 侧计数器差异。

## 4. 结果（示例 / 待实跑填写）

> 以下为**模板**，非实测值。实跑后用 `k6` 输出与 Prometheus 指标替换本表。

### 4.1 sku_cache_read

| 指标 | 缓存开启 | 缓存关闭（基线） |
|---|---|---|
| VU 峰值 | 200 | 200 |
| 总请求数 | _待填_ | _待填_ |
| p95 延迟 | _待填_ ms | _待填_ ms |
| p99 延迟 | _待填_ ms | _待填_ ms |
| 错误率 | _待填_ | _待填_ |
| catalog DB 查询（区间计数） | _待填_ | _待填_ |

### 4.2 seckill_flash

| 指标 | 值 |
|---|---|
| VU 峰值 | 500 |
| 配额 | 10 |
| 200（准入） | _待填_ |
| 409（售罄） | _待填_ |
| 500 / 其他 | _待填_ |
| p95 延迟 | _待填_ ms |

## 5. 结论与建议（草案）

- cache-aside 命中后应使 `GET /skus/{id}` 延迟稳定落在内存/Redis 量级（亚毫秒~低十毫秒），
  DB 查询计数较关闭缓存基线大幅下降，证明缓存成功卸载读路径。
- 秒杀 Lua 预扣在配额耗尽后统一返回 409，**不会**因并发导致超卖或击穿 DB；正常品（未播种配额）
  走 `bypass` 由 DB 三段式库存兜底。
- 下单侧固定窗口限流（capacity=50/1s）在更高流量下会以 429 快速失败、不返回 Retry-After，
  符合「拒绝不允许重试」决策。
- 后续可补充：用 Redis `INFO stats` / `Slowlog` 观察 Lua 执行耗时；用 Prometheus
  `catalog_seckill_degraded_total` 监控 Redis 不可用时的 fail-closed 降级次数。
