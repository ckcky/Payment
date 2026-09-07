# Changelog

本文件记录 PaymentArch 的**重大治理与架构演进**。日常小修小补不在此列；以提交哈希 + 日期溯源。

规范、ADR、技术方案、系统设计同步更新的约定见 Constitution §「提交节奏 / 文档与代码同步」。

---

## [2026-09-07] spec 020：演示界面设计系统统一——DESIGN.md 单一真相源 + 共享 Token 层

**范围**：mock-channel-web 4 个演示页（portal / demo / cashier / audit）视觉层统一。决策见 [spec 020](docs/specs/020-demo-ui-design-system/spec.md)（D1–D5 采纳建议项），设计规范见 [docs/design/DESIGN.md](docs/design/DESIGN.md)。

### 变更
- **设计真相源**：新增 `docs/design/DESIGN.md`（Stripe 风格基底裁剪：靛紫 `#533afd` 主色、weight-300 展示字、tnum 表格数字、pill 按钮）+ `static/design.css` 共享 token 层（157 行，CSS variables + 语义类）；灵感来源 VoltAgent/awesome-design-md（MIT）。
- **状态语义映射收口（FR-004）**：业务状态 → 语义色映射表落 DESIGN.md §2，`st-{STATUS}` / `c-{STATUS}` 动态类名拼接模式不变、样式由 design.css 同名类供给；新增状态 MUST 先登记映射表。
- **四页改造**：收银台加渐变横幅 + 细体大字金额（¥ 格式，tnum）+ pill 按钮分级（实心仅主 CTA）；门户 emoji 换内联 SVG 图标；demo/audit 控制台 token 化 + 表格数字对齐 + dark shell 日志面板统一（`--pa-brand-dark`）。
- **零依赖铁律（NFR-001）**：无 CDN / webfont / 图片 / npm；JS 行为与接口契约零改动（唯一展示性调整为收银台金额格式）。

---

## [2026-09-07] spec 019：order 驱动的两层退款单（TXRF/PMRF）+ 渠道退款异步回调闭环

**范围**：退款链路重设计。决策见 [ADR-0067](docs/adr/0028-order-driven-refund-two-layer-refund-order.md)，实施见 [spec 019](docs/specs/019-order-driven-refund/tasks.md)。

### 核心变更
- **两层退款单**：order 库新增 `transaction_refunds`（TXRF+雪花，幂等键=TXRF 可重入）；payment 库 `refunds.refund_no` 改自生成 PMRF+雪花（存量 RF 保留），加 `transaction_refund_no` 双向互记。
- **transactions 补资金口径**：加 `payment_no`（生效支付单，首张成功写入不覆盖）+ `refunded_minor`（累加已退）；`OrderStatus` 补 `PARTIALLY_REFUNDED`/`REFUNDED`。
- **退款异步回调闭环**：Mock 渠道退款改「受理 + 延迟推送」异步模式；payment 新增渠道退款回调端点（HMAC 验签防重放）；`RefundResultProcessor` 三路收敛（同步受理 / 渠道回调 / 人工 resolve）到同一编排；收敛后通知 order（`/internal/orders/on-refund-result`，双号 TXRF+PMRF）。
- **编排归属收口（ADR-0054 延伸）**：业务下游扇出（履约终止 / 权益撤销 / 秒杀回补）归 order 侧；payment 侧删除 `RefundPostProcessOrchestrator` 及 fulfillment/entitlement 直调；权益撤销沿 fulfillment → entitlement 既定链（fulfillment `onRefund` 触发）。
- **下线与修复**：删除 `POST /internal/refunds` 创建入口（resolve 保留）；记账幂等键统一 `REFUND:{PMRF}`（修双重前缀）；`PaymentResultProcessor` 订单通知失败不再静默吞（WARN + 指标）。
- **演示**：mock-channel-web 增加退款回调推送代理（`/mock-channel/refund-callback`）与 TXRF 追踪段；`scenario-refund.sh` 改调 order 入口。
- **迁移**：`019-order-driven-refund.sql`（幂等，已在本地 MySQL 重放验证）。

### 明确不做（负责人拍板）
- 退款 UNKNOWN 自动收敛器（回调丢失靠 resolve 兜底）；resolve 端点 Admin Token 鉴权；部分退款次数上限。

### 回归
- payment-service 135/135、order-service 61/61、fulfillment 21/21 全绿；全仓 `mvn -o clean install -fae` BUILD SUCCESS。


## [2026-09-07] spec 018：表结构列序规范化 + payment_attempts 金额留痕 + 按 order_item 粒度履约

**范围**：全项目 22 张表列序规范化 + 履约粒度升级 + 演示可观测性。决策见 [ADR-0066](docs/adr/0027-schema-normalization-and-item-granular-fulfillment.md)，实施记录见 [spec 018](docs/specs/018-schema-normalization-item-fulfillment/spec.md)。

### 变更
- **列序规范化（FR-001）**：统一为「自增 id → 业务主键 → 唯一索引列 → 业务列 → 审计列」；违规 5 张表（payment_attempts / payments / refunds / fulfillments / entitlements 等）经幂等迁移脚本 `deployment/schema/018-schema-normalization.sql`（information_schema 守卫 + PREPARE 动态 SQL，存量库重放两次验证幂等）归位，基线 CREATE TABLE 与 H2 测试 schema 同步。
- **order_item_no 业务单号引入（FR-004 / ADR-0062）**：`BusinessNoType.ORDER_ITEM("OI")`，order_items 第 2 列 + 唯一键；`fulfillments.order_item_id` 语义升级为 OI 单号。
- **按订单明细粒度履约（FR-005 / 消除 null 硬编码）**：`PaymentSucceededRequest` 契约加 `List<ItemLine>`（payment 传 null，order 以本库 order_items 富化后转发，单一事实源）；fulfillment 逐明细建履约，幂等键细化 `(source_payment_no, order_item_id)`；`onRefund` 遍历取消全部 PENDING；每条履约各自触发权益授予（授予链零改动）；`/fulfillments/by-order` 改返回数组。
- **payment_attempts 金额留痕（FR-002）**：加 amount_minor / currency_code（PAYMENT=支付金额；REFUND=所属支付单金额，决策 D2）。
- **演示可观测性**：全链路 DB 数据中文注释（16 调用点）；新增 portal.html 门户主界面（演示 / 对账 / Grafana / Prometheus / 压测五类入口）。

### 附带修复
- 迁移脚本对存量库 `attempt_type` 列（016 时代表尾追加）归位到第 4 列。
- 016 迁移脚本 MariaDB 方言问题（`ADD COLUMN IF NOT EXISTS`）记入代码缺陷待办清单 #13，不在本 spec 修复。

---

## [2026-09-07] v1.0.0 发行形态切换：源码包 → 预构建二进制发行包

**范围**：发行工程，不动业务代码。首个正式版本 tag `v1.0.0`，由 CI（`.github/workflows/release.yml`）自动构建并发布。

### 变更
- **`make-release.sh` → `deployment/release/`**：与发行包启停脚本（start/stop/reset-demo）同居，打包从脚本位置上溯仓库根；CI 引用同步更新。产物为 `payment-platform-<V>-bin.tar.gz`（10 个 fat jar + 一键启停 + 建表 SQL + 演示场景 + k6 压测），目标机器只需 JDK 21 + Docker，零 Maven/构建。
- **退役旧「源码发布包」形态**：删除根目录 `run.sh` / `stop.sh` / `run-tests.sh`（源码包解压入口，`git archive` 快照 + 现场 Maven 全量构建，违背「可直接运行」初衷）；`run-stress.sh` → `deployment/performance/` 与负载生成器同目录。
- **发行包默认向 Nacos 注册 127.0.0.1**（单机包最稳，`PAYMENT_NACOS_IP` 可覆盖）；`restart-payment.sh` 双模式兼容包内 jars/ 与源码仓。
- **RELEASE.md** 重写为二进制发布流程（tag push → CI → make-release.sh → Release 资产）。
- 实机冒烟：冷启动 90s 10/10 服务 UP；happy-path / 退款（幂等重放 + 防超额）场景包内连跑全过。

### 附带修复
- `scenario-refund.sh` 幂等键动态化（原 rk-001/rk-002 写死，重跑命中重放污染断言）；漏网全角括号吞变量两处（`$REFUND_STATUS）`、`$SCENARIO）`）。
- 演示控制台补退款流程与渠道尝试展示（attempt_type=REFUND）；复位脚本对齐 Feature 015 退款并库。
- 根目录脚本清零：仓库根仅保留 Maven 标配（`mvnw` / `pom.xml` / `VERSION`）与文档。

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
