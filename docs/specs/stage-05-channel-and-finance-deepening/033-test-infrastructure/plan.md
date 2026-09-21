# Plan: 033-test-infrastructure（测试基础设施与业务验证体系）

**版本**：v1.0（实现轮）　**日期**：2026-09-21
**前置**：[spec.md](spec.md)（v1.0，已批准）；[ADR-0081](../../../../../docs/adr/0081-test-carrier-and-schema-replayability.md)
**裁决**：2026-09-21 负责人按 spec 推荐方案批准 H-033-1~5 全部五项
（Testcontainers 放宽至仅测试作用域 / 基线入库 / refund 保留标遗留 / R-A 边允许清单 / skip 即红强制）。

---

## 1. 总体 approach

按 spec §14 的五个切片推进，**每个切片可独立构建通过**，逐批提交：

| 切片 | 内容 | 本 plan 的落地形态 |
|---|---|---|
| **033-D**（唯一不依赖裁决的切片，先做以保安全网） | R-A：RPC 边允许清单 + `src/main` 禁 Testcontainers 两条 ArchUnit 规则 | `deployment/architecture-tests` 新增 `RpcEdgeAllowListTest` + `rpc-edges.txt`（进 git）；`ServiceBoundaryTest` 不动（零风险） |
| **033-A**（地基） | `deployment/test-infra` 模块 + `schema-lint.sh` + `016` 方言修复 + initdb `refund` 遗留标注 | Maven 新模块（test 依赖库，非服务）+ deployment 下 shell 脚本；不新增服务/中间件 |
| **033-B**（门禁） | 双路径重放 + 基线 + 三处 CI 挂载 | `deployment/schema-replay.sh`（本地/CI 同一脚本）+ `deployment/schema/baseline/033.sql`（首个基线 = 当前 master 全量重放产物，spec §6.3）+ `.github/workflows/schema.yml`（新增）/ `verify.yml`（+real-db job）/ `e2e.yml`（+nightly demo 五场景与随机序 verify） |
| **033-C**（首批真库落点） | 基座自证（中性合成表），**不写业务用例** | spec §4 红线：033 的 PR MUST NOT 含业务断言。T1-a/b/c 用例本体归 031/032/payment 域；033 交付「落点与绿灯环境」= 基座 + `@Tag("real-db")` 标签机制 + CI `real-db` job |
| **033-E**（nightly 增强） | demo 五个离线场景挂载 + 随机序 verify + 跳过可见性 | 只挂 spec §16 R6 列出的离线可跑场景（happy-path / audit / mq / reconciliation / refund），涉沙箱的 routing 不进 CI |

## 2. `deployment/test-infra` 基座设计（对 spec §5.2 契约的落实）

```text
deployment/test-infra/
  src/main/java/com/payment/testinfra/
    ├── RealMysqlTestSupport   // 抽象基类：单例容器（跨类复用，C-2）+ 类内独占 database（t_<类名小写>）
    │                          //   + Docker 可用性契约（本地 skip+WARN / CI realdb.required=true 时 fail，C-4）
    ├── SchemaBootstrap        // DDL 只能来自 deployment/schema/NN-*.sql（C-1，路径白名单）；
    │                          //   剥 CREATE DATABASE/USE + allowMultiQueries + 必含约束断言（禁假绿②兜底）
    ├── RealDb                 // 组合注解：@SpringBootTest 元注解 + @Tag("real-db") + schema 属性
    ├── RealDbExtension        // JUnit 扩展：beforeAll 里起容器/建库/灌 schema，并把 spring.datasource.*
    │                          //   写入 System properties（Spring Boot 属性源最高优先级，context 创建前生效）
    ├── DockerContract         // skip/fail 决策器（可单测的纯函数部分）
    ├── FaultHooks             // F-2 真库故障注入：SELECT SLEEP / KILL 连接 / 事务不提交（供 034 复用）
    └── MetricsAssert          // 读 MeterRegistry 的 counter/timer 断言工具（供 035 复用）
```

关键取舍：

1. **容器单例 + 类内独占 database**（C-2）：static holder 只 start 一次 MySQL（25~40s 只付一次），
   每个测试类在自己的 `t_<classname>` 库中执行；容器由 Ryuk 在 JVM 退出时回收，不写 @AfterAll。
2. **Spring 集成走 System properties 而非 DynamicPropertySource**：`@DynamicPropertySource` 必须写在
   测试类静态方法上，无法收进共享基座；System properties 在 Spring context 创建前由 JUnit 扩展写入，
   优先级高于 application.yml，等效且零侵入（不要求服务改任何配置）。
3. **C-3 对齐生产**：容器启动参数 `utf8mb4 / utf8mb4_unicode_ci / default-time-zone=+00:00`，
   JDBC URL `serverTimezone=UTC`。
4. **C-5 自证**：基座不含任何业务词汇；自身测试引用真实 `10-audit-schema.sql`（证明「测试所用 DDL ==
   生产 DDL」的通路，仅作脚本名配置，非业务语义）+ 中性合成表 `t_probe_idem`（并发唯一自证）。
5. **依赖零版本号**：全部版本由根 pom 的 Boot BOM / Testcontainers BOM 管理，新增 dependency 版本声明 = 0。

## 3. schema 门禁设计（对 spec §6 的落实）

- **`schema-lint.sh`**（deployment/ 下脚本族，对齐 `lib-mode-guard.sh` 风格）：
  - L-1 去 `--` 注释后 grep `ADD (COLUMN|INDEX) IF NOT EXISTS` → 现违规仅 `016`，随本 Feature 改写；
  - L-2 initdb 库集合 == schema `CREATE DATABASE` 库集合，**扣除遗留清单 `refund`**（H-033-3：保留 + 标注遗留，
    删除另立 chore）；`ledger` 已由 031 补齐，本断言防回退；
  - L-3 含 `ALTER TABLE` 的脚本必须含 `information_schema` 守卫 → 现违规仅 `016`。
- **`schema-replay.sh`**（本地与 CI 同一脚本，输入 = 一个**空** MySQL 端点）：
  - 路径 A：`initdb` → 全量（`[0-9][0-9]-*.sql` + `027-*`）→ 增量（其余 `NN-*.sql`，两段次序保证增量
    所需表先存在，守卫在全新库上天然 no-op）→ 快照 S_A；**连跑两遍**证明幂等（spec §6.3）；
  - 路径 B：drop 用户库 → 恢复 `baseline/033.sql` → 再放全量 + 增量 → 快照 S_B；
  - 门禁：`diff(S_A, S_B)` 为空 + `diff(S_A, S_A')` 为空；快照取 `information_schema` 的
    COLUMNS/STATISTICS（确定性排序，不含自增值等噪声）。
- **`016` 改写**：`information_schema` 守卫 + `PREPARE/EXECUTE`（015/018/019/030/031 同模式），
  文件头按 §6.2-4 写清作用 / 幂等策略 / 存量行为；不改任何列定义语义（`attempt_type`、
  `idx_attempts_payment_type` 原样保留）。
- **首基线 `baseline/033.sql`**：本机 Docker 起临时 MySQL，跑路径 A 后 `mysqldump --no-data`
  （结构 dump，可回放），一次性生成入库（spec §6.3「首次运行时生成并入库」）。

## 4. RPC 边允许清单设计（对 spec §7 的落实）

- `RpcEdgeAllowListTest`（`deployment/architecture-tests`）：ArchUnit 静态抽取各服务 `target/classes` 中
  带 `@FeignClient` 注解的接口 → 三元组 `(caller, target, clientFqcn)`；
  `target` 取注解 `name`（`xxx-service` 去后缀），`caller` 取包名 `com.payment.<svc>..`；
  **允许清单式**断言：提取集合必须**逐行等于** `src/test/resources/rpc-edges.txt` 基线，
  新边/删边都会红 ⇒ 强制 review 讨论。
- 防空转：先断言 client 数量 > 0、已知环 `order -> payment` 在清单中（否则静态抽取失效时恒绿的假绿）。
- `src/main` 禁 `org.testcontainers`：独立 ArchRule（服务字节码即 `target/classes`，test 依赖天然不可见）。
- `ServiceBoundaryTest` **零改动**（红线：不动既有测试；新规则全部落新类）。

## 5. CI 挂载（对 spec §13 的落实，AC-4：不删 job、不改既有触发语义）

| workflow | 变更 |
|---|---|
| `schema.yml`（新增） | `schema-lint`（秒级）+ `schema-replay`（mysql:8.0 service container + 脚本） |
| `verify.yml`（追加 job） | `real-db`：`-pl deployment/test-infra,ledger-service -am -Dgroups=real-db -Drealdb.required=true`（skip 即红，禁假绿①）；**原 verify job 原样保留** |
| `e2e.yml`（追加 job） | `demo-scenarios`：nightly live 栈上 reset → 五个离线场景逐一执行（退出码 + ACCESS_LOG 断言）；`verify-random-order`：`-Dsurefire.runOrder=random`（spec §12#4） |

## 6. 测试策略与归属（spec §4）

- 033 交付：基座自测（中性合成表并发唯一 / DDL 来源断言 / 无 Docker 降级决策 / lint 与重放脚本）；
- **不写** T1-a/T1-b/T1-c 业务用例本体（归 031/032/payment 域）；既有 H2 用例与 `InMemory*Repository` 零删除；
- 唯一被"动"的既有测试：`LedgerPostingConcurrencyTest` 从手搓模式**迁移到共享基座**（spec §5.2 C-1、§11
  「把 030 的既有测试模式泛化为基座」的显式授权），**全部断言原样保留**。

## 7. 风险与回退

| 风险 | 缓解 |
|---|---|
| 无 Docker 开发机被真库测试卡住 | DockerContract 默认 skip + WARN；只有 CI 显式 `-Drealdb.required=true` 才 fail（禁假红） |
| 双路径重放暴露既有脚本非幂等 | 已核对：全量 `CREATE TABLE IF NOT EXISTS`、seed 全部 `ON DUPLICATE KEY UPDATE`、增量守卫齐全；若 CI 首跑发现新缺陷，按 spec §6.1 D-A/D-B 修复后重跑 |
| 基线漂移（新 Feature 不更新基线） | 路径 B 与基线强绑定：新增增量未入基线 → S_B ≠ S_A → 红（spec §16 R4 的正面利用） |
| ArchUnit 注解属性读取受 classpath 影响 | 用字符串形式的注解名 + ArchUnit `tryGetExplicitlyDeclaredProperty`，不要求 openfeign 在 architecture-tests 的 classpath 上 |

## 8. 验证口径

`JAVA_HOME=<JDK 26> ./mvnw -o clean verify -fae` 全绿（总数 / 失败 / 错误 / 跳过记录进 acceptance.md）；
真库测试本机 Docker 可用即实跑（`LedgerPostingConcurrencyTest` + 基座自测），数量进 acceptance.md；
`schema-lint.sh` / `schema-replay.sh` 本机实跑，exit code 记录进 acceptance.md。
