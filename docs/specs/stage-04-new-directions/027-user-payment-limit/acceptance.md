# Acceptance: 027-user-payment-limit（用户支付限额）

> **当前状态：✅ Implemented（2026-09-16 已验收）**——批次 A~J 全部完成，实现于 `feature/027-user-payment-limit` worktree。
> 全量回归 `./mvnw -o clean verify -fae` **BUILD SUCCESS**（16 reactor 条目，与实现前一致），
> `architecture-tests` 8/8（含 spec 028 的 2 条边界规则），payment-service 206 测 0 失败。
> **live 验证（2026-09-16，容器模式全栈）**：`scenario-limit.sh` `EXIT=0`，58 条断言全 PASS（见 §7）。
> 验收标准出处：[spec.md §6](spec.md#6-验收标准sc)；对应任务见 [tasks.md](tasks.md)。

## 0. 门禁命令

```bash
# 全量（含 ArchUnit 边界门禁）
./mvnw -B verify

# 演示脚本（live 冒烟，需服务与 Redis 已启动）
bash deployment/demo/scenario-limit.sh
```

**前置条件**：`docker-compose` 的 `redis:7` 已启动（D13）；`payment.limit.enabled=true`（默认）。

## 1. 门禁类

- [x] **SC-001** `mvn -o clean verify -fae` 全绿，reactor 条目数**不变**；`architecture-tests` 通过（限额子域在 `com.payment.payment.limit` 下，不被其他服务直接访问）
  - 实测：`BUILD SUCCESS`，16 reactor 条目（父 + 3 common + 9 领域 + mock-channel-web + e2e-tests + architecture-tests），`ServiceBoundaryTest` 8/8
- [x] **SC-002** 既有支付 / 退款 / 可靠性 / 集成测试**零改动**通过（证明 FR-024 / INV-2）
  - 实测：既有测试文件未修改；payment-service 全量 206 测 0 失败（含既有 `PaymentPersistenceTest` / `ChannelCallbackSecurityTest` / 退款与可靠性用例）
- [x] **SC-012** 新增第三方依赖**仅** `spring-boot-starter-data-redis` 一项；无 MQ / 规则引擎 / 调度框架（D13）
  - 实测：`payment-service/pom.xml` 仅 +1 依赖；补偿扫描复用既有 `@Scheduled`（Spring 内建），惰性回收**无调度器**

## 2. 正确性与不变量

- [x] **SC-003（US1 · INV-3）** 日限额 ¥150、已付 ¥99 → 第二笔 ¥99 返回 `409 LIMIT_EXCEEDED`；`payments` / `payment_attempts` **无新增行**（是「未创建」而非「创建了再拒」）
  - 单测：`LimitReserveServiceTest` 超限用例 + `LimitPersistenceIT`（断言建单事务回滚后 payments 无行）
- [x] **SC-004（US2 · 并发）** 日限额 ¥250，并发 3 笔 ¥99 → 恰好 2 笔成功、1 笔 409；`used+pending` 恒为 19800，**不得出现 29700**
  - 实测：`LimitConcurrencyTest` 7 测（真实线程 + `CountDownLatch`）全绿——靠单条原子 `UPDATE ... WHERE used+pending+? <= limit`
- [x] **SC-005（US3 · INV-4）** 成功后重发回调 N 次 → 该 `paymentNo` 的 `CONFIRM` **恰好 1 条**
  - 实测：`LimitSettlementServiceTest` + `LimitPersistenceIT`（幂等键 `UK(biz_no, op_type, period)` 撞键即跳过）
- [x] **SC-006（补偿）** 人为构造「payment=SUCCEEDED 但无 CONFIRM」→ 补偿扫描补 1 条 → **再跑一次不产生第二条**
  - 实测：`LimitSettlementServiceTest` 补偿用例（二次扫描幂等）
- [x] **SC-007（INV-5）** 支付停留 `UNKNOWN` → 补偿扫描**不结算**，`pending` 保持占用
  - 实测：`LimitSettlementServiceTest`（`UNKNOWN` 不产生 CONFIRM / RELEASE）
- [x] **SC-008（INV-7）** 人为触发两次 `RELEASE` → `pending_minor` **不为负数**
  - 实测：`GREATEST(0, pending_minor - ?)`；`LimitPersistenceIT` 双次释放用例
- [x] **SC-009（INV-6）** 渠道回调携带与请求不同的 `amountMinor` → 额度按请求额结算，`pending` 无残差
  - 实现：预占与确认一律 `payment.getAmountMinor()`（`LimitGate` / `LimitSettlementHook`），不读渠道回传额
- [x] **SC-010（FR-025）** 无配置用户行为与今天一致；`traffic-gen.sh` 连续运行不被 409 打断
  - 实测：`demo/seed.sh` 未播种限额；`LimitReserveServiceTest` 无配置 → 直接放行（`isLimited()` 恒 false）

## 3. TTL 与软超限（D11 / D12 / D13）

- [x] **SC-014（惰性回收）** `FakeLimitExpiryIndex` 置过期 → 触发回收 → `pending` 归零、`EXPIRED` **恰好 1 条**、`payments.status` **未被改动**（FR-035）
  - 实测：`LimitReserveServiceTest` 回收用例（含「同周期恰好 1 条 EXPIRED」断言）
- [x] **SC-015（软超限）** 「TTL 已释放后支付才成功」→ `used` 按实付额**如实**累加（哪怕 `used > limit`）、`pending` 不为负、产出 `payment_limit_overrun` 与 `limit.overrun` 审计；随后新支付被 **409** 拒绝
  - 实测：`LimitSettlementServiceTest` 软超限用例（不 clamp、不拒绝确认）
- [x] **SC-016（Redis 降级 · INV-9）** 未配置 / 不可用 Redis：服务正常启动、建单不报错不拦截、在途**保持占用**、`payment_limit_redis_unavailable` 有值；H2 全量测试在无 Redis 下通过
  - 实测：`NoopLimitExpiryIndex` 经 `ObjectProvider` 降级；H2 测试**零 Redis 依赖**全绿；`NoopLimitExpiryIndex.alive()` 返回 `null` → 保守占用

## 4. 演示（FR-029~034 / FR-041）

- [x] **SC-011** `bash deployment/demo/scenario-limit.sh` 退出码 0（七步断言链 L1~L7，见 tasks T126）
  - **live 实测（2026-09-16，容器模式全栈 + Redis）**：`EXIT=0`，**58 条断言全 PASS、0 FAIL**。
    覆盖：L1 不限额放行 / L1b 无配置期间不累计占用（FR-012 正面证据）/ L2 额度内预占（used 不变、pending +9900）/
    L3 超限 409 + `LIMIT_EXCEEDED` + `payments` 零行（INV-3）/ L4 月周期短板整笔拒绝且日周期预占零残留（FR-010）/
    L5 确认后 used +9900、pending 归零 / L6 失败后 pending 释放、used 不变 / L7 重放后 used 不变且 `CONFIRM` 流水恰 1 条。
  - 脚本实跑期修正 8 处缺陷（详见 §7），其中 **6 处为脚本自身缺陷**、**1 处为运行配置缺失**、**1 处为镜像滞后**。
- [x] `demo.html` 右栏额度水位卡显示日 / 月 / 年三条进度条，`used` 实心 + `pending` 半透明；在途出现「在途」分段
  - 权威口径：`GET /internal/limits/users/{userId}` 的 `periods[]`（DB），不读 Redis、不推算
- [x] 点「一键演示超限」→ 第 3 笔撞线，卡片底部红色提示说明**哪个周期**、差多少、支付单未创建
- [x] 「点了支付不回调」→ pending 分段在 TTL 到期后**回落**，流水出现 `EXPIRED`
- [x] `/demo/trace?orderId=ORxxx` 限额分组可见 `user_limit_usage` 与 `limit_operations`
- [x] `portal.html` T1 chips 出现「限额」

## 5. 文档防漂移（SC-013）

- [x] `payment-service.md` 新增 §9 限额章节（数据模型 / 建单挂点 / 错误码 / 指标 / 配置）
- [x] `docs/operations/runbook.md` 新增 §4.2 补充 `payment.limit.*`，并标注 `reserve-ttl` 与 `order.timeout.ttl-seconds` **需人工保持一致**
- [x] `docs/adr/README.md` 已索引 ADR-0071（索引表 + 编号速查表两处）+ ADR-0048 互链例外登记；ADR-0071 状态 🟡 Proposed → 🟢 Accepted
- [x] 其他同步：`CHANGELOG.md` / `roadmap.md` / `deployment/demo/README.md` / ADR-0071 内 D9·D10 定稿 + D2·D4 UK 修正 + D11 与 D13 冲突留痕

## 6. 验收记录

| 日期 | 范围 | 结果 | 备注 |
|---|---|---|---|
| 2026-09-16 | 批次 A（spec + ADR-0071） | ✅ 完成 | D1~D13 已拍板 |
| 2026-09-16 | 批次 B~J（实现 + 测试 + 演示 + 文档） | ✅ 完成 | `clean verify -fae` BUILD SUCCESS / 16 reactor 条目；payment-service 206 测 0 失败；arch-tests 8/8 |
| 2026-09-16 | **live 验证**（容器模式 + Redis） | ✅ 通过 | `scenario-limit.sh` EXIT=0，58 断言全 PASS；实跑期修正 compose Redis env 漏配 + order 镜像滞后 + 8 处脚本缺陷（§7） |

**实现期修正（务必知悉）**：

1. **`limit_operations` 唯一键由 `(biz_no, op_type)` 改为 `(biz_no, op_type, period)`**——原键会使
   MONTH / YEAR 档在 DAY 首次 `RESERVE` 后**静默失效**（一笔支付跨三周期需各留一条流水）。
   此缺陷由多周期配置的单测暴露（34/34 通过即修正后结果；修正前 29/34）。
2. `user_limit_usage` 需补 `created_by` / `updated_by`（`BaseEntity` 要求）；`limit_operations` 的实体
   **不**继承 `BaseEntity`，故其 DDL 无该两列——两边 schema（MySQL DDL + H2 `schema.sql`）已对齐。
3. H2 集成测试用 `@Transactional` 回滚 + 每测唯一 `bizNo`（`UUID`）——H2 `schema.sql` 在测试上下文间
   共享，固定 `bizNo` 会跨测试撞键产生假失败。
4. `LimitGate` **不带** `@Transactional`（它在 `PaymentPersistence.insertPending` 的事务内被调用）；
   `PaymentPersistence` / `PaymentResultProcessor` 多构造器处补了显式 `@Autowired` / 兼容构造器。

**待 live 验证（不阻塞合并）**：`scenario-limit.sh` 的 L1~L7 需在**运行中的服务栈 + Redis** 上实跑
（git 工作流规定 live 验证在合并后于主工作区执行）。

**live 验证已完成（2026-09-16，容器模式）**：`scenario-limit.sh` `EXIT=0`，58 条断言全 PASS。详见下方 §7。

## 7. live 验证记录与实跑期修正（2026-09-16）

环境：`docker-compose --profile full` 全栈（10 服务 + mysql + redis + nacos），容器模式，宿主 `curl`。

| # | 现象 | 根因 | 归属 | 处置 |
|---|---|---|---|---|
| 1 | `payment-service` health **503 DOWN**（`redis` 组件 DOWN） | 容器 `environment` **漏配** `SPRING_DATA_REDIS_HOST/PORT`——payment 此前不用 Redis，spec 027 首次引入但 compose 未同步 | **运行配置缺失** | `docker-compose.yml` 补两条 env（对齐 order-service），并加 `redis: condition: service_healthy` |
| 2 | L3/L4 期望 409 却得 **400** | `order-service` 镜像构建于 **13:02**，早于 spec 027 合入；镜像内 `common-core` 是旧版，缺 `LIMIT_EXCEEDED → 409` 映射，回落 `default → 400` | **镜像滞后** | 重建 `order-service` 镜像（fat jar 已是新版） |
| 3 | `行 140: PENDING_BASE： 未绑定的变量`（3 处） | 全角括号紧贴变量名，bash 把它并入变量名（`set -u` 下报未绑定） | 脚本 | 3 处改 `${VAR}` 显式定界 |
| 4 | L2 断言 `used >= 9900` 失败（实际 0） | **语义误解**：L1 建单发生在「无配置」状态，按 FR-012 / INV-2 **不预占、不结算**，本就不该累计 | 脚本 | L1 后新增 **L1b**：正面断言无配置期间 used/pending 恒为 0；L2 起点改为断言「占用为 0」 |
| 5 | L3 期望 409 却得 201 | `USED_BASE + PENDING_BASE` 现为 0 → `set_limit 0`，而 **0 的语义是「该周期不限」**（FR-020），反而放行 | 脚本 | 改为现读 `occupied` + 1 分作为额度，并注释 0 语义陷阱 |
| 6 | L3 `payments` 行数断言查到**上一笔订单** | 建单后**未解析** `orderNo`，`ORDER_NO` 停留在 L2 的值 | 脚本 | 补 `jget "d['orderNo']"` + 非空校验 |
| 7 | L4 前置 `MONTH used=0` | **语义误解**：只配日额度时月周期 `isLimited()` 为 false → 直接 `continue`，**不预占不记账**，月周期永远无占用 | 脚本 | L4 先同时配日/月额度（都极大）让两周期进入受管态，再收窄月额度造短板；加两周期 used > 0 的前置断言 |
| 8 | L5 前置 `pending=19800`（应为 9900） | `clear_limit` **只删配置不清占用**（占用是支付事实的投影，无清空端点）；L2 的在途 pending 一路带入 | 脚本 | L3 收尾补 `settle_success "$L2_PAYMENT_NO"` 结算在途；并把演示用户改为**每轮唯一**（`limit-demo-user-$RANDOM`，可被 `LIMIT_USER` 覆盖）天然隔离历史 |
| 9 | L6 失败回调被 **400 INVALID_ARGUMENT** 拒收 | 回调状态字面量应是 **`FAILURE`**（渠道层 `ChannelResult.Status`），脚本写成 payment 侧的 `FAILED`（`PaymentStatus`） | 脚本 | 改 `FAILURE` 并注释两套枚举的区别 |
| 10 | L7 流水端点 **500** `NoResourceFoundException` | 端点实为 `/internal/limits/payments/{paymentNo}/operations`，**不含** `/users/{userId}`；脚本用 `$LIMIT_URL` 拼接落到静态资源处理器 | 脚本 | 改用完整正确路径 |

**结论**：**实现侧零缺陷**（第 2 条是部署滞后，第 1 条是 compose 配置漏项，其余 8 条均为演示脚本自身问题）。
隔离复现确认核心语义正确：超限失败**零残留**（`pending` 不脏、`limit_operations` 无孤儿 RESERVE）、
无配置**不累计**、失败回调正确释放、重放回调幂等。

