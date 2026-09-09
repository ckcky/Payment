# Feature 011 — demo-showcase 验收（acceptance）

**版本**：0.1
**日期**：2026-08-31
**关联**：`spec.md`、`tasks.md`、`docs/adr/0012-demo-showcase-decisions.md`（ADR-0048~0051）、`docs/adr/0013-channel-callback-signature-decisions.md`（ADR-0052 ⛔ Not Implemented）、`docs/adr/0015-wip-ahead-of-roadmap.md`（ADR-0053）

## 1. 构建门禁（已通过 ✅）

本地执行（`./mvnw` 不可用，统一本机 Maven + `-o` 离线）：

```bash
'C:\Users\user\apache-maven-3.9.5\bin\mvn.cmd' -o clean verify -fae
```

**结果：BUILD SUCCESS**，16 个 reactor 条目全部通过：

| 模块 | 结果 |
| --- | --- |
| root / common-core / common-dto / common-mybatis | ✅ |
| merchant / catalog / order / payment / refund | ✅ |
| fulfillment / entitlement / reconciliation / settlement / ledger | ✅ |
| **mock-channel-web**（011 新增演示组件，端口 8091） | ✅ |
| **architecture-tests**（ArchUnit 边界门禁） | ✅ |

- `architecture-tests` 的 `ServiceBoundaryTest.SERVICES` 不含 `mock-channel-web`，证实演示组件**未被错误纳入服务边界**——边界门禁通过。
- `ChannelCallbackSecurityTest`（payment-service）**6/6 通过**：在 ADR-0025 验签空实现下，伪造/缺失/过期/篡改签名回调一律放行，测试显式断言该占位行为。

## 2. 已交付内容（011 范围）

- **新增 `mock-channel-web` 演示组件**（端口 8091，非生产服务、不进 `SERVICES` 边界）：收银台页 + 渠道回调转发 + 演示控制台 + 同源代理（`/proxy/{service}/**`）。
- **payment 接入 payUrl 跳转链路**：`CreateOrderResponse` / `CreatePaymentResponse` 增加 `payUrl`（仅 `mock-cashier` 启用时返回）。
- **Mock 渠道场景配置化**（ADR-0049，`payment.channel.mock-scenario`）：`SUCCESS` / `FAILURE` / `BUSINESS_UNKNOWN` / `TIMEOUT` 等，构造期注入、坏值 FAIL FAST，运行时切换需重启。
- **对账演示账单生成**：写入 `target/classes`，不改生产代码（ADR-0050）。
- **`demo/` 演示脚本骨架 + 四场景**：`scenario-happy-path`（含重复回调幂等吸收 + 明文回调放行演示）、`scenario-payment-unknown`、`scenario-refund`（防超退 409）、`scenario-reconciliation`（关单闸门 400 → 处理差异 → 关闭 200）；`run-all.sh` 串联；`README.md` 说明前置与断言表。
- **脚本纪律**（ADR-0051）：只编排不伪造、断言失败即非零退出；`bash -n` 全部通过。

## 3. 关键裁决回落（需负责人知悉）

- **ADR-0052 渠道回调真实验签 ⛔ Not Implemented**：2026-08-31 负责人确认回退到 ADR-0025 空实现。`ChannelCallbackSignatureFilter#verifySignature` 恒返回 `true`（占位放行），`application.yml` 已移除 `payment.security.*`。
  - **直接后果**：`mock-channel-web` 演示控制台的「伪造签名 403」按钮**无效**（点下去依旧放行），`demo/scenario-happy-path.sh` 已改为演示「明文/伪造回调同样放行（ADR-0025 占位）」。

## 4. 全栈运行时验证（✅ 2026-09-09 已实跑通过）

> **历史状态（2026-08-31 记录）**：当时本机 Docker 不可用、MySQL 不可达，四场景脚本属
> 「契约正确但未实跑」。2026-09-09 环境具备后已完整实跑，结论如下。

**执行方式**

```bash
bash deployment/start-all.sh          # 起容器（MySQL/Nacos/Prometheus/Grafana）+ 10 个进程
bash deployment/demo/run-all.sh       # 复位 → 主链 → 退款 → UNKNOWN 收敛 → 每日对账 → 审计闭环
```

**结果：全部通过，96 条断言，0 失败**

| 场景 | 结果 | 覆盖要点 |
| --- | --- | --- |
| `scenario-happy-path` | ✅ | 建 SKU → 下单 → 两步式建支付单 → 收银台回调 → 支付 SUCCEEDED、订单 PAID |
| `scenario-refund` | ✅ | 两层退款单 TXRF/PMRF 双号互记、异步回调收敛、第二笔为新退款单非回放、超额退款 409 `AMOUNT_INVARIANT_VIOLATION`、订单 `PARTIALLY_REFUNDED` |
| `scenario-payment-unknown` | ✅ | `BUSINESS_UNKNOWN` 场景（重启 payment 注入）→ UNKNOWN 堆积 → 人工裁定收敛为终态 |
| `scenario-reconciliation` | ✅ | 每日对账跑批、差异处理、关批 |
| `scenario-audit`（spec 017） | ✅ | 审计批 `AB223428991976165376`：差异检出 → 挂账 → 调账 → 收口 → 关批 → 试算平衡（`suspendedAmountMinor` = `adjustedAmountMinor` = 38850） |

**实跑中发现并修复的脚本缺陷（三处，均已合入 master）**

1. `scenario-refund.sh`：轮询把 `UNKNOWN` 当终态提前 break。退款提交渠道后先落 UNKNOWN
   （`failureReason="channel refund unknown / accepted in-flight"`），再由异步回调推成终态。
   实测 20:43:07.951 断言拿到 UNKNOWN，08.172 才收敛 —— 纯时序问题，改为只以
   SUCCEEDED/FAILED 退出。
2. `restart-payment.sh`：`port_pids()` 用 Windows 语法 `netstat -ano`，macOS 的 BSD netstat
   不支持 `-o`，配合 `set -euo pipefail` 令脚本在打印任何内容前静默退出（run-all 跑完退款
   场景后无输出直接 EXIT=1）。改为按平台分支，macOS 走 `lsof`。
3. 同上文件：`lsof -ti tcp:8084` 不带 `-sTCP:LISTEN` 会返回所有与 8084 有 TCP 关联的进程，
   含调用方持有的出站连接 —— 一次重启连带干掉 9 个服务。加 `-sTCP:LISTEN` 限定监听进程；
   并显式传 `--server.port`，避免启动环境的 `SERVER_PORT`/`PORT` 变量抢占端口
   （实测三个服务被拉到 60956 而启动失败）。

## 5. 已知偏离（提交负责人复盘，见 ADR-0053）

working tree 中除 011 外，还包含 **013-inventory-reservation / 014-seckill-and-cache** 的实质性实现（catalog `Stock*` 聚合 + 三段式库存、order `OrderTimeoutScheduler` Redis ZSet 时间轮 + `SeckillResult` + 限流 + 幂等 + Lua）。该代码：

- **超前 roadmap 顺序**（011→012→013→014）；
- **缺 spec/plan/tasks/acceptance 与 ADR-0041~0046**；
- **014 的 Redis 引入未经 roadmap §7「压测基线→论证引入」闸门**。

决策（ADR-0053）：**保留代码**（编译+测试通过，且与 011 在 `order-service` 纠缠无法干净拆分），不静默删除；spec/ADR 补写列为 TODO，待负责人复盘后走 Spec Kit 流程收口。本验收仅对 011 范围负责，013/014 的 acceptance 待其 spec 补齐后单独出具。

## 6. 结论

- ✅ 011 构建门禁通过、交付内容齐备、验签回落与脚本纪律符合裁决。
- ✅ **全栈运行时验收已通过**（2026-09-09）：5 个场景、96 条断言、0 失败；实跑中暴露的
  三处脚本缺陷均已修复并合入 master（详见 §4）。
- ⚠️ 013/014 超前落地为 SOP 偏离，已落 ADR-0053 待复盘。
