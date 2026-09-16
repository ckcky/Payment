# Changelog

本文件记录 PaymentArch 的**重大治理与架构演进**。日常小修小补不在此列；以提交哈希 + 日期溯源。

规范、ADR、技术方案、系统设计同步更新的约定见 Constitution §「提交节奏 / 文档与代码同步」。

---

## [2026-09-16] docs：spec 进度文档刷新 + 陈旧分支清理

**范围**：纯文档 + 仓库清理，无代码改动、无 ADR。

- `docs/specs/026-containerized-local-stack/spec.md`：状态由 `Draft` 改为
  `✅ Implemented`，补齐三条收口合并点（`61e9e3d`/`6a07a3d`、`08f3bb1`、`01193ff`）与实测口径。
  原 `Draft` 系文件未刷新的滞后标记，非任务未完成（tasks.md 早为 68/68）。
- `docs/architecture/roadmap.md`：Current Status 补齐 `018`~`026` 九个 spec 的落地汇总表
  （此前只登记到 `017`，`018`~`026` 全部缺失）；订正「当前能力」行的模块清单
  （10 服务 → **9 服务**，反映 spec 019 退款域并入 payment；补 `e2e-tests` 模块）；
  登记 `003`/`006`/`007` 的非阻塞遗留测试项。
- **分支清理**（均已确认并入 master）：远端删 `chore/022-pr-contract-gate`、
  `fix/023-ops-closeout`、`feature/026-container-stack`；本地删 `chore/022-pr-contract-gate`、
  `feature/007-outbound-resilience`、`fix/023-ops-closeout`。

---

## [2026-09-15] fix：容器模式收银台 payUrl 客户端不可达（spec 026 补丁）

**范围**：修 `PAYMENT_MOCK_CASHIER_BASE_URL` 在容器模式下的取值错误 + 补「payUrl 客户端可达性」回归用例。
无 ADR（属 spec 026 容器化的缺陷修复，非新决策）。

- **缺陷**：容器模式下 compose 把 `payment.mock-cashier.base-url` 配成 `http://mock-channel-web:8091`
  （容器内服务名）。该值被 `buildPayUrl()` 拼进 `payUrl`，交给前端 `window.open()` 由**浏览器**打开——
  浏览器所在宿主解析不了容器内网名字（NXDOMAIN）。表现为「下单成功、支付单也建了，但收银台跳转不了」，
  而容器内 curl 该 URL 反而 200，极具迷惑性。
- **扫清的第二个错误候选**：`host.docker.internal` **同样不可用**——实测该名仅容器内可解析（宿主 NXDOMAIN）。
  正解是 `localhost:8091`（8091 已 publish 到宿主）。与 `prometheus.yml` 的取值差异是有意的：
  那里是**容器主动出站抓取**，请求由 prometheus 容器发起；此处地址是给浏览器用的，方向相反。
- **测试缺口（根因）**：既有 e2e / demo 全是 curl 打接口断言状态码，**从不打开接口返回的链接**，
  故此类缺陷对整套自动化隐形。新增 `CashierPayUrlReachabilityTest`：按**绝对 URL** 直连 payUrl 要求 2xx，
  并断言客户端不共享服务网络时 payUrl 不得含容器内服务名。
  已做**有效性验证**：把配置改回错误值 → 用例立刻变红并直指根因（HTTP 503 / NXDOMAIN），非空跑。
- **展示层兜底**：`DemoProxyController` 对上游响应做 `mock-channel-web:8091` / `host.docker.internal:8091`
  → `localhost:8091` 的改写，即使环境变量又被配错，/demo 页拿到的 payUrl 也保证可用。
- **顺带修复**：`Dump.write` 未净化文件名——step 含完整 URL 时 `? : / &` 使写盘失败，
  失败时恰恰丢掉诊断产物。已改为替换非法字符并限长。
- **回归**：容器模式 e2e 24/25（新增用例绿；唯一红仍是已知本地代理伪影 MISSING_POSTING，CI 为准）；
  demo 5 场景退出码 0。

---

## [2026-09-15] spec 026：本地全栈容器化（双模式 · Compose 而非 K8s）

**范围**：spec 026 立项 + P1~P7 全部落地并实测通过（P4 可观测适配、P5 演示脚本模式分支、P7 双模验证矩阵）。
详见 [docs/specs/026-containerized-local-stack/](docs/specs/026-containerized-local-stack/)，决策见 **ADR-0070**

- **Supersedes ADR-0057「服务未容器化」**：原决策理由「学习项目，容器化非当前目标」被推翻——
  宿主 JDK 版本绑架启动（`RunMojo` 需 Java 17+，本机默认 java 11 → `UnsupportedClassVersionError`）
  与宿主进程生命周期脆弱（任务回收致 payment 8084=000、压测数据作废）两个真实痛点证明其价值。
- **双轨并存（D2）**：新增容器模式 `deployment/start-container.sh`，保留宿主模式 `deployment/start-all.sh`；
  两者端口契约一致（8081–8091）故**互斥**，由 `deployment/lib-mode-guard.sh` 提供双向守卫（已实测拦截生效）。
- **宿主打 jar，镜像只 COPY（D3）**：`deployment/docker/Dockerfile` 一份参数化通用镜像服务 10 个模块，
  复用父 POM 已产出的 `deployment/output/jars/*.jar`，镜像内不跑 Maven（避免 3 个 common 模块被重编译 10 次）。
  基础镜像 `eclipse-temurin:21-jre-jammy`。
- **配置适配不改源码（D4）**：compose `environment` 覆盖硬编码的 `127.0.0.1`（Nacos `nacos:8848`、
  MySQL `mysql:3306`、Redis `redis`、mock-channel-web 的 `/proxy` 目标改容器服务名），业务源码零变更。
- **compose 增加 `pay-arch` 网络与 profiles**：`infra`（7 中间件）/ `full`（+10 应用）；
  应用 `depends_on nacos: condition: service_healthy`，避免 Connection refused 假成功。
- **P4 可观测适配**：`prometheus.yml` **刻意不改**——抓取目标沿用 `host.docker.internal:808x`，
  容器模式端口已 publish 到宿主，一份配置双模通吃（改 compose 服务名会让宿主模式失联）。
  实测容器模式 Prometheus 10 target 全 UP、Loki 10 个服务均有日志。
  应用日志落挂载卷靠 Dockerfile 的 `tee` 双写：项目 `logback-spring.xml` 只有 CONSOLE appender，
  `logging.file.name` 无效，只能由入口把 stdout 分流到文件（同时保留 `docker logs`）。
- **P5 演示脚本**：`restart-payment.sh` 按当前模式自动选宿主重启 / 容器重建
  （`docker compose up -d -e PAYMENT_CHANNEL_MOCK_SCENARIO=...`）；容器模式 demo 5 场景退出码 0。
- **P7 双模验证矩阵（已全部执行）**：demo 两种模式均退出码 0；e2e 两种模式**同形 22/23**
  （唯一红为已知本地代理伪影 `MISSING_POSTING`，CI 为准）；压测两种模式均 `chain_completed=100`、
  0 个 5xx（吞吐 rps 36.4 → 26.2，catalog 609 → 552，源于容器堆 256m < 宿主 384m + NAT 开销，可接受）。
- **容器模式专属修复（无此则 e2e 必挂）**：①reconciliation 容器挂载宿主 `/tmp/e2e-channel-statements`
  ——渠道账单 CSV 由测试 JVM（宿主）写入，容器不挂就读不到；②Dockerfile 加 `TZ=Asia/Shanghai`
  ——基础镜像默认 UTC，与宿主差 8 小时会让按日期窗口的断言分叉。
- **内存教训**：容器堆必须**低于**宿主（`-Xmx256m` + `metaspace 128m`）并给每个服务
  `mem_limit: 512m`（catalog 承载秒杀主流量放宽到 768m）。不限量时 10 个 JVM 顶穿 Docker Desktop
  默认配额 7.75GiB，导致 VM 被杀、全部容器 Exited(255)。稳态实测约 5.3GiB。
- **前提提示**：镜像构建需能访问 Docker Hub 拉取 `eclipse-temurin:21-jre-jammy`；网络不可达时
  `docker compose build` 会在 `FROM` 阶段失败，此时用宿主模式。

---

## [2026-09-08] spec 022 收尾 + 023 live 验证：测试有效性实测、PR 契约门禁、运维验证闭环

**范围**：spec 022 批次 G 收尾（T433/T434/T435/T436）+ spec 023 剩余项（T5/T14/T16/T17/T18），全部 live 栈实测。详见各自 tasks.md 附注。

- **T433 测试有效性实测（SC-002/SC-003）**：临时注入缺陷后还原（未入库）——①旁路 order 可退余额校验 + payment 累计校验 → 超退用例红 2/3（断言含订单号金额，dump 落盘）；附带发现仅去 payment 侧校验仍绿，409 闸在 order 侧（防御纵深）；②`RefundOrder` 不回填 `payment_refund_no` → 单号链用例红（双号互记断裂）。
- **T434 全量回归**：`./mvnw -B verify` BUILD SUCCESS；E2E 全量 22/23 绿 + 1 skip，唯一红（对账矩阵 MISSING 检出）经 general_log / 访问日志 / 无沙箱复跑三重实证为本机透明代理吞写伪影，CI 通道为准（`Db.execute` 已走 `/demo/db-exec` 代执行 + 落库回读，b6af115）。
- **T430 PR 契约门禁**：`verify.yml` 新增 `contract-snapshot` job（最小栈仅跑 L3 API 快照），ArchUnit 随 reactor verify 生效——契约漂移 PR 即红，不等 nightly。
- **023 live 验证**：Prometheus targets 无 `job="refund"`；`scenario-happy-path.sh` 全断言通过；SIGTERM 优雅停机日志完整（Commencing → complete，端口释放）；`start-all.sh` Nacos 超时改 exit（d40602d）。
- **T436 评估结论**：`demo/run-all.sh`（现场演示编排）与 E2E 模块（无头回归门禁）受众与生命周期不同，不合并。

---

## [2026-09-07] spec 022：全链路自动化测试体系（代码落地，ADR-0069）

**范围**：新建黑盒 `deployment/e2e-tests` Maven 模块（不依赖业务模块），承载「退款正常 / 超退拦截 / 对账准确 / 单号记对」四类全链路验证；决策与验收见 [spec 022](docs/specs/022-full-chain-automated-testing/spec.md)。**live 实跑验证待办**（T433/T434，`bash deployment/e2e-tests/run.sh`）。

- **支撑层**：Env（local/ci 双环境）/ Api（JDK HttpClient 黑盒，4xx 原样返回）/ Db（9 schema JDBC 探针）/ Await（Awaitility 统一轮询，禁 Thread.sleep）/ Trace / Dump（失败自动落盘）/ Invariants（9 条断言原语：超退守卫、单号链、记账平衡、权益撤销、履约终止、库存守恒、幂等重放、无孤儿）/ E2eBase（用例级数据隔离 + 造单助手）。
- **P0/P1 用例**：退款主链（部分/全额，六库一致 + 权益撤销 + 履约终止）、超退守卫（单次/累计/并发 4×2000 不超付）、对账四核对（LIVE CLEAN + 8 类 FAULT 注入矩阵，DB 直改备份→注入→检出→还原）、CSV 渠道账实差异注入（长/短/金额不符/重复，经 `statement-dir-override` 运行时落盘）、结算门禁、单号链、幂等与回调异常路径（重复 3 次 / 丢失→resolve→后处理不丢 / 乱序不回退）、API schema 快照（L3，`-De2e.update-snapshots=true` 重录基线）。
- **确定性故障注入（T429 / D7）**：`MockChannelAdapter` 请求级触发——金额尾数 11=超时 / 12=无结论 / 15=业务拒绝；基线 `PAYMENT_MOCK_SCENARIO` 保留；E2E 经 catalog API 造指定价格 SKU 控制金额。
- **CI 门禁（D4）**：PR 快跑不变（e2e 模块默认 `skipTests=true`）；新增 `e2e.yml` nightly + workflow_dispatch + v* tag 强制（起 MySQL/Nacos → 起 9 服务 → `-De2e.env=ci` → 上传 surefire + dump）。
- **flaky 策略（T432）**：不自动重试，连续 flaky `@Disabled` 降级登记；秒杀用例未配置 SKU 时 Assumptions 跳过（防假红 NFR-005）。

---

## [2026-09-07] spec 023：审计中性项收尾——可观测与一致性加固

**范围**：2026-09-07 审计报告中性工程遗留项（安全类按负责人裁决保持桩实现不在范围，Testcontainers/E2E 归 spec 022）。决策与验收见 [spec 023](docs/specs/023-audit-ops-remediation/spec.md)。

- **可观测**：删除 Prometheus 死目标 `refund`（:8085 已随 ADR-0064 退役，REFUND 指标由 payment-service 同进程暴露）。
- **优雅停机**：9 领域服务统一 `server.shutdown=graceful` + 30s drain（审计 M5），SIGTERM 不再掐断在途回调。
- **事务收窄（审计 M1）**：`onPaymentSucceeded` 的出站 RPC（confirmStock/履约）移出事务边界，DB 段改 TransactionTemplate 编程式事务；confirm 失败仅告警 + `order.stock_confirm_failed` 指标，不回滚 PAID（事实不回滚，ADR-0054）。
- **文档漂移**：order/payment 系统文档对齐两步式下单与 ADR-0054/0067/019 实况，清除「Proposed 未实施」过时标注；docs 导航补 runbook（基线 H1/H3/H4/H5 全闭环）。

---

## [2026-09-07] spec 022（立项·待实施）：全链路自动化测试体系

**状态**：仅落地文档（spec 四件套 + [ADR-0069](docs/adr/0030-end-to-end-automated-testing.md)），**代码未开发**；决策 D1~D8 见 [spec 022](docs/specs/022-full-chain-automated-testing/spec.md)，任务清单见 [tasks.md](docs/specs/022-full-chain-automated-testing/tasks.md)（批次 A 已完成，B~G 待实施）。

**范围**：把「在 demo 控制台发起支付/退款 → 观察各系统状态与 DB 数据」变成可重复、可报告、可进 CI 的自动化测试，覆盖**退款功能正常 / 超退能拦截 / 对账准确 / 单号记对**四件事。

- **形态**：新建独立 Maven 模块 `deployment/e2e-tests`（JUnit5 + AssertJ + Awaitility + JDK HttpClient + JDBC，黑盒不依赖业务模块），业务代码零改动。
- **环境**：默认 `-De2e.env=local` 复用本地已起栈；`ci` profile 才用 Testcontainers；数据隔离用唯一业务号前缀 + 按单号过滤。
- **断言**：行级复用现成 `/demo/trace`（跨 9 库 14 表快照），聚合不变量（借贷平衡/退款累计/单号互记/孤儿单）直连 MySQL，沉淀为 `Invariants` 断言原语库。
- **门禁**：不引 SCC/Pact（改做内部 API schema 快照）；PR 快跑（单元+集成+快照+ArchUnit），E2E 与对账差异注入放 nightly。
- **硬约束**：禁断言渠道回调验签（ADR-0025 占位恒放行，会假绿）；对账验收必须走 audit LIVE 模式（MOCK 纯前端）；「对账准不准」拆真实数据一致性与差异检出能力两套断言。

---

## [2026-09-07] spec 020：演示界面设计系统统一——DESIGN.md 单一真相源 + 共享 Token 层

**范围**：mock-channel-web 4 个演示页（portal / demo / cashier / audit）视觉层统一。决策见 [spec 020](docs/specs/020-demo-ui-design-system/spec.md)（D1–D5 采纳建议项），设计规范见 [docs/design/DESIGN.md](docs/design/DESIGN.md)。

### 变更
- **设计真相源**：新增 `docs/design/DESIGN.md`（Stripe 风格基底裁剪：靛紫 `#533afd` 主色、weight-300 展示字、tnum 表格数字、pill 按钮）+ `static/design.css` 共享 token 层（157 行，CSS variables + 语义类）；灵感来源 VoltAgent/awesome-design-md（MIT）。
- **状态语义映射收口（FR-004）**：业务状态 → 语义色映射表落 DESIGN.md §2，`st-{STATUS}` / `c-{STATUS}` 动态类名拼接模式不变、样式由 design.css 同名类供给；新增状态 MUST 先登记映射表。
- **四页改造**：收银台加渐变横幅 + 细体大字金额（¥ 格式，tnum）+ pill 按钮分级（实心仅主 CTA）；门户 emoji 换内联 SVG 图标；demo/audit 控制台 token 化 + 表格数字对齐 + dark shell 日志面板统一（`--pa-brand-dark`）。
- **零依赖铁律（NFR-001）**：无 CDN / webfont / 图片 / npm；JS 行为与接口契约零改动（唯一展示性调整为收银台金额格式）。

---

## [2026-09-07] spec 021：统一访问日志——结束时单条 ACCESS + 固定格式含服务名 + 异步 MDC 修复

**范围**：全服务可观测性基建。决策见 [ADR-0068](docs/adr/0029-unified-access-logging.md)，实施见 [spec 021](docs/specs/021-unified-access-logging/tasks.md)。

### 核心变更
- **AccessLogFilter**（common-core `accesslog` 包）：`OncePerRequestFilter` + ContentCaching 包装，每请求结束落一条 `ACCESS_LOG` INFO——`method/uri/status/costMs/req/resp` 全字段单行（D2）；异常路径 try/finally 必打；`copyBodyToResponse()` 保证响应完整（NFR-002）。
- **报文口径**（D4）：GET `req=-`；multipart/非文本 `<binary>` 占位；4KB 截断带 `...(truncated,total=N)`；`/actuator/**` 排除；`common.access-log.enabled=false` 整体关闭。
- **脱敏桩**（D3）：`SensitiveBodyMasker` 接口 + `PassThroughBodyMasker` 透传（本期不真脱敏）；服务可覆盖，Filter 零改动。
- **格式固定含服务名**（D5）：logback `springProperty` 注入 `spring.application.name`，全服务行格式统一 `service=<名>`。
- **异步 MDC 修复**（D7）：`MdcTaskDecorator` + `TraceContext.runWithNewTrace()`；ChannelQuery / TimeoutScan / OrderTimeout / Audit 4 个 Scheduler 入口补 traceId，后台日志可全链路捞取。
- **日志查看**（D6）：`deployment/demo/tail-logs.sh`（全栈合并实时视图，[服务名] 前缀）+ `trace-grep.sh <traceId>`（跨服务全链路捞取）。
- **装配定序**：`TraceIdFilter` 显式 `FilterRegistrationBean` order=-200 → `AccessLogFilter` order=-100，14 服务零代码改动自动生效。

### 明确不做（负责人拍板）
- 真脱敏实现（只留桩）；Loki+Promtail 集中采集（后续期）；AOP 方法级日志；FileAppender。


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
