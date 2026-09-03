<a id="adr-0060"></a>

- **状态**：Accepted（2026-09-04，全链路压测驱动）
- **日期**：2026-09-04
- **关联**：`ADR-0004`（Redis 非数据源 / 014 秒杀预扣）、`ADR-0039`（下单幂等）、`ADR-0053`（三段式库存）、`0018`（性能基线，§分布式端到端验证）

## ADR-0060：Redis 客户端启用 Lettuce 连接池

### Context（背景）

- Lettuce 默认使用**单连接多路复用**：所有命令共享一条 TCP 连接，单条命令天然串行。
- 低负载下无感知；2026-09-04 全链路实跑发现两个热点场景受排队影响：
  - 秒杀 Lua 预扣（catalog）：500 VU 破坏性压测 p95 **528ms**（毫秒级操作被排队放大，见 ADR-0058 §分布式端到端验证）；
  - 下单幂等键读写（order）：20 VU 全链路压测中与 catalog 侧排队叠加。
- Spring Data Redis 对 Lettuce 池化的支持需要 `commons-pool2` 在 classpath（Boot BOM 管理版本）；
  未引入依赖时 `spring.data.redis.lettuce.pool.*` 配置静默不生效。

### Decision（决策）

1. **catalog-service / order-service** 引入 `org.apache.commons:commons-pool2`（版本由 Boot BOM 管理）。
2. 两服务 `application.yml` 启用池化：

   ```yaml
   spring.data.redis.lettuce.pool:
     enabled: true
     max-active: 16
     max-idle: 8
     min-idle: 2
   ```

3. **维持单实例 Redis、不引入读写分离**：池化只解决客户端侧排队；秒杀热点 key 的服务端串行
   仍由 Redis 单线程天然保证，这正是秒杀准入「不超卖」语义所依赖的（ADR-0004）。
4. 不为 Redis 池化引入重试/熔断等额外机制：Redis 不可用时既有 fail-open（缓存）/fail-closed（秒杀）语义不变。

### Consequences（后果）

- ✅ 秒杀 Lua 高并发下由多连接并行分发，消除客户端串行排队（复测数据见 `deployment/performance/README.md`）。
- ✅ 幂等键读写延迟分布收窄，20 VU 全链路 p95 改善。
- ⚠️ 每服务多占 2–16 条 Redis 连接；单机多服务部署需关注 Redis `maxclients`（默认 10000，当前规模无虞）。
- ⚠️ `commons-pool2` 成为必要依赖：移除它而保留 pool 配置会导致池静默不生效（回落单连接），无报错——
  排障时若发现延迟重新劣化，先确认依赖存在。

### 实施核对清单

- [x] catalog-service / order-service POM 声明 `commons-pool2`
- [x] 两服务 `application.yml` 启用 `spring.data.redis.lettuce.pool`
- [x] 500 VU 秒杀复测（2026-09-04，池化后）：deduct p95 **528ms → 278ms**、吞吐 **976 → ~1,536 rps**，
      0 错误（对照轮为配额耗尽破坏性场景、本轮为大配额场景，条件差异见 performance README §4.5）
- [x] 全链路 20 VU 复测不回归（失败率 0.55%，p95 363ms）
