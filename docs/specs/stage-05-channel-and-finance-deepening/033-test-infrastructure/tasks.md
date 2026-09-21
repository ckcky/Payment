# Tasks: 033-test-infrastructure（测试基础设施与业务验证体系）

> 执行顺序 = spec §14 切片（D 可先行）。验收证据见 [acceptance.md](acceptance.md)。

## 033-D 架构门禁（不依赖裁决，先做）

- [x] **T1** `deployment/architecture-tests`：新增 `RpcEdgeAllowListTest`——静态抽取 `@FeignClient`
      三元组 `(caller, target, clientFqcn)`，与 `rpc-edges.txt` 基线逐行比对（允许清单式，spec §7）；
      附防空转阳性对照（client 数 > 0、已知环 `order -> payment` 必在清单）
- [x] **T2** 同模块新增 `srcMainMustNotDependOnTestcontainers` ArchRule（ADR-0081 决策 1 的封口规则）
- [x] **T3** 生成 `rpc-edges.txt` 基线（12 条真实边，进 git）

## 033-A 地基

- [x] **T4** 根 pom 注册 `deployment/test-infra` 模块（零版本号新依赖：testcontainers / mysql-connector-j /
      junit-jupiter / assertj / spring-boot-test / micrometer-core，版本全由既有 BOM 管理）
- [x] **T5** `RealMysqlTestSupport`：单例容器（跨类复用）+ 类内独占 database（`t_<类名>`）+
      C-3 生产对齐（utf8mb4/utf8mb4_unicode_ci/UTC）+ 无 Docker 降级契约（本地 skip+WARN /
      `realdb.required=true` 时 fail）
- [x] **T6** `SchemaBootstrap`：DDL 只能来自 `deployment/schema/`（路径白名单，C-1）+ 剥
      `CREATE DATABASE`/`USE` + `allowMultiQueries` + 必含约束断言（禁假绿②）
- [x] **T7** `RealDb` 组合注解 + `RealDbExtension`（`@SpringBootTest` 元注解 + `@Tag("real-db")` +
      spring.datasource.* System property 注入 + schema 属性灌库）
- [x] **T8** `FaultHooks`（SELECT SLEEP / KILL 连接 / 事务不提交）+ `MetricsAssert`（MeterRegistry 断言）
- [x] **T9** `DockerContract` 决策器 + 基座自测：中性合成表并发唯一自证（真库）/ DDL 来源与剥离断言 /
      skip-vs-fail 决策单测——基座内零业务断言（spec §4 红线）
- [x] **T10** `deployment/schema-lint.sh`：L-1 方言禁令 / L-2 initdb 与 schema 库集合一致（refund 遗留豁免清单）/
      L-3 `ALTER TABLE` 必带 `information_schema` 守卫
- [x] **T11** `016-refund-channel-attempt.sql` 守卫模式改写（列与索引定义语义不变，文件头三件事齐全）+
      `initdb/01-create-databases.sql` 补 `refund` 遗留标注（H-033-3：保留不删）

## 033-A/C 桥接：既有真库测试切换基座

- [x] **T12** `LedgerPostingConcurrencyTest` 迁移到 `RealMysqlTestSupport` + `SchemaBootstrap`
      （spec §11 显式授权的泛化；**三条用例与全部断言原样保留**，`@Tag("real-db")`）

## 033-B 门禁

- [x] **T13** 本机生成首个基线 `deployment/schema/baseline/033.sql`（路径 A 产物 mysqldump --no-data，
      spec §6.3 一次性动作）
- [x] **T14** `deployment/schema-replay.sh`：路径 A（含连跑两遍幂等自检）+ 路径 B（基线恢复 → 全量+增量）
      → `information_schema` 快照 diff 门禁
- [x] **T15** `.github/workflows/schema.yml`（新增）：`schema-lint` + `schema-replay` 两 job
- [x] **T16** `verify.yml` 追加 `real-db` job（`-Dgroups=real-db -Drealdb.required=true`，skip 即红）；
      既有 `verify` / `contract-snapshot` job 零改动（AC-4）

## 033-E nightly 增强

- [x] **T17** `e2e.yml` 追加 `demo-scenarios`（离线五场景：happy-path / audit / mq / reconciliation / refund，
      退出码 + ACCESS_LOG 断言）与 `verify-random-order`（`-Dsurefire.runOrder=random`，spec §12#4）

## 文档与收口

- [x] **T18** `engineering-standards`：§4 补测试载体规则与 L1→L2b 升级判据 + 归属边界；新增「迁移脚本可重放」
      小节（§6.2 六条条文）；§11 漂移清单补 `rpc-edges.txt` 一致性
- [x] **T19** Constitution §Engineering.3 集成测试载体条目更新（Testcontainers 已落地：仅测试作用域 + 基座 + ADR-0081）
- [x] **T20** ADR-0081 状态 🟡 Proposed → 🟢 Accepted（2026-09-21 负责人裁决，按 spec 推荐方案批准）；
      ADR README 索引同步
- [x] **T21** `docs/specs/README.md` 033 行 → 🟢 已实现；`roadmap.md` stage-05 状态行同步；
      `CHANGELOG.md` 顶部新增 feat(033) 条目
- [x] **T22** 全量 `./mvnw -o clean verify -fae` 绿（JDK 26 override）；`schema-lint.sh` /
      `schema-replay.sh` 本机实跑 → [acceptance.md](acceptance.md) 填真实证据
