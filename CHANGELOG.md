# Changelog

本文件记录 PaymentArch 的**重大治理与架构演进**。日常小修小补不在此列；以提交哈希 + 日期溯源。

规范、ADR、技术方案、系统设计同步更新的约定见 Constitution §「提交节奏 / 文档与代码同步」。

---

## [2026-09-04 ~ 2026-09-06] Feature 015/016 落地与文档治理

### 架构演进（ADR-0059 ~ ADR-0065）
- `0019-enable-nacos.md` → **ADR-0059**：启用 Nacos 服务发现（硬依赖），撤销 ADR-0056「暂不启用」偏离；12 个 `@FeignClient` 去掉硬编码 URL，未起 Nacos 则跨服务调用 `Connection refused`。
- `0020-redis-lettuce-pool.md` → **ADR-0060**：Redis 客户端启用 Lettuce 连接池。
- `0021-observability-panel-fix.md` → **ADR-0061**：可观测性补全与演示脚本漂移修复。
- `0022-business-no-snowflake.md` → **ADR-0062**：业务单号统一两字母前缀 + 雪花算法（TX/OR/PM/RF/SB/RB/LP）。
- `0023-cross-service-reference-by-business-no.md` → **ADR-0063**：跨系统关联一律用业务单号。
- `0024-multi-payment-per-transaction.md` → **ADR-0064**：一交易多支付单 + 退款域并入 payment-service（服务数 10→9，端口 8085 退役）。
- `0025-order-payment-orchestration.md` → **ADR-0065**：支付编排职责归位（order 升为编排者，spec 016）；Supersedes ADR-0064 §决策#4。

### Feature 落地
- **015**：多支付单模型 + 退款域并入 payment-service + 雪花业务单号 + 跨系统业务单号关联；下单改为两步式（`POST /orders` 仅建单，显式 `POST /orders/{orderNo}/payments` 建支付单）。
- **016**：支付编排职责归位决策已确认（ADR-0065），代码迁移进行中（现状 payment 直调履约待迁移，见 `technical-solution.md` §4.3.1）。
- `mock-channel-web` 收银台链路（payUrl 跳转）随 011 落地；演示页 `demo.html` 适配两步式建单。

### 文档治理（2026-09-06）
- `technical-solution.md` §7（项目计划与资源）与 §9（ADR 追溯索引）迁至 `roadmap.md`（单一事实源，正文内联体现 ADR）；同步修正 Nacos 状态、订单→支付两步式链路、服务数 10→9。
- `roadmap.md` 增补 `项目计划与资源` 与 `附录 A：ADR 追溯索引`；修正进程计数与 Feature 状态。
- `deployment/README.md`、`docs/operations/runbook.md`、根 `README.md`、`demo/README.md` 对齐：JDK 21 前提、Nacos 硬依赖、服务/端口事实表（删 refund-service 8085、补 ledger-service 8090 / mock-channel-web 8091）、进程计数 = 9 领域服务 + 1 演示组件共 10。
- `initdb/01-create-databases.sql`：移除已退役 `refund` 库，补 `ledger` 库。
- `docs/README.md`、`CLAUDE.md`：纳入 `docs/operations/` 导航；说明 ADR 双轨编号（文件号 / 决策号）。

### 相关提交
- 见 `git log` 中 `feat(observability)`、`feat(016)`、`docs:` 等条目。

---

## [2026-09-03] 文档与目录治理（审计整改）

**范围**：仅文档与目录治理，不动任何业务代码（例外：`.gitignore` 与 `git rm --cached` 属版本库治理）。对应核心指令「已决策 ADR 在技术方案/系统设计中体现；历史文档归档；系统架构与方案审计；目录规划清晰」。

### 新增
- 宪法 `.specify/memory/constitution.md` 升级 **v2.3.0**：固化 2026-08-30 裁决（§II.2 金额=long 分+currencyCode、Money VO 不启用；§Security.4/§Obs.2 脱敏加本期例外；§ES.1/3/§Obs.3 Checkstyle/Testcontainers/Tracing 标 `[目标]`；§Anti-Goals 撤「不引入 Redis」；新增「文档与代码同步」提交纪律）。顶部追加 Sync Impact Report。
- `docs/adr/0016-core-payment-correctness.md` → **ADR-0054**（确认性：002-payment-order-callback 资金约束）。
- `docs/adr/0017-entry-and-infra-decisions.md` → **ADR-0055/0056/0057**（幂等键由 order 生成 / Nacos 暂不启用·偏离 ADR-0002·待 R1 / 服务未容器化）。
- `docs/adr/0018-performance-baseline.md` → **ADR-0058**（性能基线；Phase 4 实测：读 p99 16.83ms、命令 seckill p99 434ms、DB 卸载 99.98%）。
- `docs/architecture/systems/ledger-service.md`：补齐第 10 篇系统设计（复式记账、4 预置科目、借贷平衡门禁、幂等、FR-001~011、三来源记账接入点）。
- `docs/architecture/technical-solution.md` §9 **ADR 追溯索引**（9.1 P0 内联 / 9.2 P1 索引 / 9.3 ADR 文件三组，全部 `ADR-` 前缀，便于 grep 审计）。
- `docs/architecture/systems/payment-service.md` §7 回调与出站安全；`order-service.md` §8 超时与库存释放（ADR-0043）。
- `docs/archive/audits/`：4 篇历史审计报告归档（2026-08-26/28/30），正文标注 `Status: archived / superseded`。
- `CHANGELOG.md`（本文件）。

### 修正（防漂移）
- `technical-solution.md` 6 处 P0 + 17 处漂移（9→10 服务、骨架→已实现、Money 铁律、Redis 已引入、退款 Ledger 冲正已接入、人工收敛已实现、Nacos/Tracing/Testcontainers/Checkstyle 标 `[目标]`、当前阶段→roadmap 走至 014、Feature 依赖图对齐实际含 008 缺口）。
- `systems/` 8 篇缓存文案（`[待定]` → `[已评估·本期不引入]`；reconciliation 状态机已接线、catalog 库存归 catalog + SkuCache、order 入口强幂等键、payment 撤回「50%打开熔断」等）。
- 根 `README.md`、`deployment/README.md` 端口/服务数/资金变动路径修正；`docs/specs` 3 处路径 `docs/deployment` → `deployment`。
- `docs/adr/` 15 个聚合文件补 53 个 `<a id="adr-XXXX">` 锚点；`README.md` 跳转表扩展至 **ADR-0058**。

### ADR 状态收口
- `0008`（0022/0023 → Accepted）、`0006`（0047 → Accepted）、`0014`（0044 补压测证据）、`0015`（0053 收口为部分完成）。

### 版本库治理
- `.workbuddy/`（含 `memory/` 工作日志）整体退出版本库（磁盘保留，git 不跟踪），避免 AI 工作记忆污染工程变更历史（commit `2b53434`）。
- 压测产物归位 `deployment/performance/`。

### 待负责人二次裁决（未自动执行）
- **R1**：ADR-0056 是否 `Supersedes` ADR-0002（Nacos 暂不启用·偏离）。
- **R2**：payment-service Resilience4j 去留（撤回「50%打开熔断」表述，现状待定）。
- **R3**：ADR-0058 已标「未验证目标」，待生产/压测环境复核。
- **R4**：`catalog-service` / `order-service` 下 `src/.../infra/redis/` 未跟踪目录是否纳入版本库。

### 相关提交
- `72ebeef` 补提交根 README / deployment README 漂移修正（阶段④ 续）
- `3130168` 已决策 ADR 在技术方案/系统设计中体现 + 新立 ADR-0054~0058 + 17 处漂移修正（阶段④）
- `b111334` 修正 technical-solution 与 systems 的 11 处 ADR 冲突（P0）+ 8 篇缓存文案（阶段③）
- `671aad0` 宪法 v2.3.0 + ADR 状态收口 + 跳转表（阶段②）
- `2b53434` .workbuddy 退出版本库，压测产物归位 deployment/performance

---

## [2026-08-31] 文档同步（2026-08-30 裁决落地）

- ADR-0016 回退、ADR-0024/0025 降级为空实现、ADR-0027/0028/0034~0037 代码清理、新增 ADR-0047。
- 全部 spec / 架构 / 运维文档同步（详见 `docs/archive/audits/2026-08-30-project-audit.md` 历史快照说明）。

---

## [2026-08-30] 宪法 v2.1.0 → 裁决固化前置

- 审计发现文档漂移，启动「先核对代码事实再改文档」整改流程，形成本项目防漂移基线。
