# Acceptance: 033-test-infrastructure

> 回写文档（2026-09-21）。证据为 `mvnw -o clean verify -fae` 全量实跑（915 tests 全绿，EXIT=0）、
> `schema-lint.sh` / `schema-replay.sh` 本机实跑、缺陷注入反向验证与 CI workflow 对照。
> 设计期 AC-1~AC-7（spec §15 上半）已在 spec 评审阶段逐条通过；本文件验收**实现交付**。

## 1. 切片交付对照（spec §14）

| 切片 | 交付物 | 结果 |
|---|---|---|
| **033-D**（先行） | `RpcEdgeAllowListTest`（边允许清单 + 阳性对照 + `src/main` 禁 Testcontainers）+ `rpc-edges.txt` | ✅ architecture-tests **13/13 绿**（10 ServiceBoundary + 1 AccountingVocabulary + 2 RpcEdge，零改动既有 11 条） |
| **033-A** | `deployment/test-infra` 模块（单例容器 / 类内独占库 / SchemaBootstrap / RealDb 组合注解 / DockerContract / FaultHooks / MetricsAssert）+ `schema-lint.sh` + 016 方言修复 + initdb refund 遗留标注 | ✅ 模块自测 **17/17 绿**（DockerContract 4 + SchemaBootstrap 契约 3 + SchemaBootstrap 真库 1 + 基座真库自证 3 + FaultHooks 3 + MetricsAssert 3） |
| **033-A/C 桥接** | `LedgerPostingConcurrencyTest` 迁移共享基座 | ✅ **三条用例与全部断言逐字保留**，ledger-service **70/70 绿**（含真库 3 条） |
| **033-B** | `schema-replay.sh`（双路径）+ 首基线 `baseline/033.sql` + CI 三处挂载 | ✅ 见 §2 / §3 |
| **033-E** | nightly 五个离线 demo 场景 + ACCESS_LOG 断言 + `verify-random-order` | ✅ `e2e.yml` 两 job 就位（nightly 实跑依赖 GitHub runner，见 §5） |
| **文档与收口** | engineering-standards §4/§11/§12、Constitution §Engineering.3、ADR-0081 🟢 Accepted、specs README / roadmap、CHANGELOG | ✅ 全部同步，见 §4 |

## 2. 门禁实跑证据（本机，Docker UP）

- **schema-lint**（L-1 方言 / L-2 库集合 / L-3 守卫）：`bash deployment/schema-lint.sh` → **exit 0**；
  L-1/L-3 断言对改写前 `016`（MariaDB `ADD COLUMN IF NOT EXISTS`）可复现报红（修复后转绿）。
- **schema-replay**（双路径）：
  - 路径 A（空库全量重放 **连跑两遍**）→ 快照 S_A == S_A2（幂等自检过）；
  - 路径 B（`baseline/033.sql` 恢复 → 全量+增量重放）→ 快照 S_B；
  - 门禁 `diff(S_A, S_B)` 为空（**543 条结构事实**全等）→ **exit 0**。
- **D-A 缺陷注入（门禁可证伪）**：向 `03-payment-schema.sql` 的 `payments` 建表注入 `negcheck_probe` 列后
  在**全新空库**重跑 → 路径 A 全量不补列、路径 B 增量补列 → 快照 diff 非空 → **exit 1**，diff 精确指出注入列
  （注入列已回滚，仓库文件无残留）。
- **rpc-edges.txt**：**15 条** `@FeignClient` 边（tasks T3 预估 12 条，实抽 15 条——以真实字节码为准，均为
  既有边，无业务变更）；`RpcEdgeAllowListTest` 逐行全等断言 + 阳性对照（client 数 ≥10、`order -> payment` 必在）。

## 3. 全量回归（2026-09-21）

`JAVA_HOME=<jdk-26> ./mvnw -o clean verify -fae` → **BUILD SUCCESS，EXIT=0**：

- **915 tests，0 failures / 0 errors / 0 skipped**（Testcontainers 真库用例在本机随默认组执行，Docker UP 故无 skip）；
- 16 个 Maven 模块全 SUCCESS；与本特性相关：`test-infra` **17**（新）、`architecture-tests` **13**（+2 RpcEdge）、
  `ledger-service` **70**（含迁移后真库并发 3 条）；
- 其余 12 模块用例数与合入前基线一致（零删除、零弱化；既有 H2 用例与 `InMemory*Repository` 桩原样保留）。

## 4. CI 挂载与文档同步对照（spec §13.1/§13.2）

| 挂载点 | 内容 | 红线 |
|---|---|---|
| `schema.yml`（新增） | `schema-lint`（秒级）+ `schema-replay`（mysql:8.0 service container 跑同一脚本） | 触发语义为新增，不动既有 workflow |
| `verify.yml`（追加 job） | `real-db`：`-pl deployment/test-infra,ledger-service -am -Dgroups=real-db -Drealdb.required=true` | 既有 `verify` / `contract-snapshot` job 逐字未动（AC-4）；无 Docker ⇒ job 红（禁假绿①） |
| `e2e.yml`（追加） | nightly 栈上 `reset.sh` → 五个离线场景（happy-path / audit / mq / reconciliation / refund）→ ACCESS_LOG 日志文件数 ≥5 断言；`verify-random-order`（`-Dsurefire.runOrder=random`，排除 real-db 组避免与 real-db job 重复起容器） | 涉沙箱 routing 场景不进 CI（§16 R6）；compose 默认容器名不变 |

文档：engineering-standards §4（L2b 已落地 + L1/L2a→L2b 升级判据 + 归属边界）、§11 新增第 6 条
（`rpc-edges.txt` 一致性）、新增 §12「迁移脚本可重放」六条条文（spec §6.2 逐条对齐，AC-3）；Constitution
§Engineering.3 集成测试载体条目更新；ADR-0081 → 🟢 Accepted（README 索引两处同步）；specs README 033 行
🟢 已实现；roadmap stage-05 状态行同步（待裁决数 27→15，H-033-1~5 已裁）；CHANGELOG 顶部 feat(033) 条目。

## 5. 归属边界遵守与遗留（spec §4 红线）

- **033 零业务断言**：基座自证用中性合成表 `t_probe_idem`（并发唯一）；T1-a/b/c 用例本体归 031/032/payment 域
  （033 交付落点与绿灯环境，spec §4.2）。
- **既有测试零删除/零弱化**：全量 diff 无测试断言删改；`LedgerPostingConcurrencyTest` 仅换载体，断言逐字保留。
- **遗留（不阻塞本验收）**：
  - CI job（schema-replay / real-db / demo-scenarios / verify-random-order）的首次 runner 实跑在 PR CI 上完成，
    本文件 §2 证据来自本机同脚本实跑（CI 与本地同一脚本，无分叉）；
  - L2b 扩面（031 §10 投影并发、032 导入幂等）归各业务 Feature；
  - `refund` 遗留库的物理删除留待独立 chore（H-033-3 只做标注）。
