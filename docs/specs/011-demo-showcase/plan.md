# Plan: 011-demo-showcase

**对应 Spec**：[spec.md](spec.md)
**日期**：2026-08-31

## 1. 实施策略

三批推进，**先资产后代码**：先把"不改生产代码就能演示"的部分做完（demo 脚本 + 控制台 + 对账素材生成），
再回头做**最小必要**的生产改动（Mock 场景配置化 + 两个只读查询端点）。任何改动若能通过脚本侧绕开，就绕开。

```text
批次 A（零生产改动）
  A1 demo/lib.sh        公共库：env / http / json / assert / service 生命周期
  A2 demo/seed.sh       种子数据（商户 → 商品 → SKU）
  A3 demo/reset.sh      清库（显式表清单）+ 重灌
  A4 demo/cashier/index.html   Mock 渠道控制台（静态页）
  A5 对账素材生成器        从平台事实生成周期账单 CSV → 写入 target/classes

批次 B（最小生产改动，均带测试）
  B1 MockChannelAdapter 场景配置化      payment.channel.mock-scenario
  B2 GET /fulfillments/by-order/{id}    只读查询端点 + 测试
  B3 GET /entitlements/by-order/{id}    只读查询端点 + 测试

批次 C（编排与验证）
  C1 4 个 scenario 脚本 + run-all.sh
  C2 启动全栈实测，修复脚本缺陷
  C3 mvn -o clean verify -fae 全绿
  C4 文档同步 + 提交合并 master
```

## 2. 关键设计决策

### 2.1 演示脚本只做编排，不伪造事实

> 来自 `next-stage-design.md` §2.1，本 Feature 将其固化为可执行约束：

- 脚本只能调用公开 API / 只读 SQL，MUST NOT 直接 `UPDATE` 业务状态、MUST NOT 跳过状态机。
- 「制造异常」只能通过**配置**（`mock-scenario`）或**真实外部行为**（不发回调、重复回调），
  不能通过改库把支付改成 UNKNOWN。
- 断言失败即退出，不允许"打印警告继续跑"——假绿比失败更危险（与 `technical-solution.md` §2.4.2 同源）。

### 2.2 UNKNOWN 场景必须重启 payment-service

`MockChannelAdapter.Scenario` 是构造期注入的，运行期不可切换。要在脚本里演示 UNKNOWN，
只有两条路：① 新增运行期切换端点（为演示改生产契约，且易被误用）；② 以不同配置重启服务。

选 ②：`demo/lib.sh` 提供 `restart_payment_service <scenario>`，由 `run-all.sh` 编排。
代价是场景 2 有服务重启（约 20s），换来**生产代码零新增端点**。

### 2.3 对账素材走 classpath，不改生产代码

`CsvChannelStatementLoader` 已支持 `{dir}/{period}.csv`（详见 spec §2）。
`spring-boot:run` 的 classpath 根即 `reconciliation-service/target/classes`，
因此脚本把生成的 CSV 写进 `target/classes/fixtures/channel-statements/{period}.csv`
即可**真实命中周期账单**，不触发 fallback，也无需动一行生产代码。

### 2.4 JSON 解析不依赖单一工具

`jq` 在 Windows 默认不存在，Python 在 macOS/Linux 普遍存在。
`lib.sh` 的 `json_get` 按 `jq → python3 → python` 探测，三者皆无则**明确报错退出**，
不做"grep 硬凑"的脆弱实现。

## 3. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 9 个服务同时启动内存不足 | 实测跑不起来 | `start-stack.sh` 串行启动并等待 health，失败即报出未就绪服务；`env.sh` 可调 JVM 参数 |
| 脚本在 Windows Git Bash 行为差异 | 换行符 / 路径 | 全部脚本 LF、`.gitattributes` 已约束；路径统一用 `/` |
| 时序导致断言抖动（履约异步完成） | 偶发失败 | 所有"最终态"断言走 `wait_until`（带超时），不做 `sleep` 硬等 |
| 新增端点破坏 ArchUnit 门禁 | 构建失败 | 端点在 `api` 包、依赖 domain 接口，符合既有分层；构建期验证 |

## 4. 交付物清单

| 类型 | 文件 |
|---|---|
| Spec | `docs/specs/011-demo-showcase/{spec,plan,tasks,acceptance}.md` |
| ADR | `docs/adr/0012-demo-showcase-decisions.md`（ADR-0048~0051） |
| 生产代码 | `MockChannelAdapter`、`FulfillmentController`、`EntitlementController`（+ 测试） |
| 演示资产 | `demo/{lib,env,seed,reset,start-stack,stop-stack,run-all}.sh`、`demo/scenario-*.sh`、`demo/cashier/index.html`、`demo/tools/DbQuery.java` |
| 文档同步 | `roadmap.md`、`next-stage-design.md`、`docs/README.md`、`docs/operations/runbook.md`、`docs/adr/README.md` |
