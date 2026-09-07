# Tasks: 021-unified-access-logging

> 承载目标：结束时单条 ACCESS 访问日志 + 日志格式固定含服务名 + 异步 MDC 传播修复 + 日志查看脚本。
> **当前状态：T301~T313 全部完成（2026-09-07），spec 021 闭环**。全量回归绿；demo 冒烟通过。
> 每个任务完成后跑对应模块测试门禁，最后统一 `mvn -o clean verify -fae`。

## 批次 A — 文档与决策（已完成）

- [x] **T301** 编写 spec 021 四件套：spec.md（业内对比 / 目标链路 / US1~US4 / FR-001~006 / NFR / SC / 决策 D1~D7）
- [x] **T302** 立项 [ADR-0068](../../adr/0029-unified-access-logging.md)（统一访问日志与固定格式）+ `docs/adr/README.md` 注册

## 批次 B — common-core 访问日志（依赖：无）

- [x] **T303** `accesslog` 包骨架：`AccessLogProperties`（record 绑定 `common.access-log.*`：enabled/maxBodyBytes=4096/excludePaths=[/actuator/**]）+ `SensitiveBodyMasker` 接口与 `PassThroughBodyMasker` 透传桩（FR-001）
- [x] **T304** `AccessLogFilter`：OncePerRequestFilter + ContentCaching 包装 + 排除路径（AntPathMatcher）+ multipart/二进制占位 + 4KB 截断 + try/finally 必打一条 ACCESS（`ACCESS_LOG` logger）+ `copyBodyToResponse()`（FR-001/003；方案见 [plan.md §1.3](plan.md#13-accesslogfilter核心)）
- [x] **T305** 装配与定序：`CommonCoreAutoConfiguration` 追加 `FilterRegistrationBean`（TraceIdFilter=-200 → AccessLogFilter=-100）+ `@ConditionalOnProperty` 开关（FR-002）
- [x] **T306** common-core 单测（照 TraceIdFilterTest 风格）：正常路径全字段 / 异常路径必打 / 4KB 截断 / 排除路径 / GET req=- / masker 桩被调用 / copyBodyToResponse 响应完整（见 [plan.md §6](plan.md#6-测试方案)）

## 批次 C — 格式固定与异步 MDC（依赖：批次 B）

- [x] **T307** logback-spring.xml：`<springProperty>` 注入 `spring.application.name`，pattern 追加 `service=` 字段（FR-004；无服务名回退 unknown）
- [x] **T308** `MdcTaskDecorator`（trace 包：捕获/恢复/清理 MDC+TraceContext）+ `ReliabilityConfig` 线程池装饰 + 4 个 Scheduler（ChannelQuery/TimeoutScan/OrderTimeout/Audit）入口补 traceId（FR-005；方案见 [plan.md §4](plan.md#4-定时任务-mdc-修复最小侵入)）
- [x] **T309** 批次 C 测试：MdcTaskDecorator 子线程传播/执行后清理；Scheduler 入口 traceId 就位；ArchUnit ModuleBoundary 无越界

## 批次 D — 日志查看与演示（依赖：批次 B/C）

- [x] **T310** 查看脚本：`deployment/demo/tail-logs.sh`（全栈合并实时视图，[服务名] 前缀）+ `deployment/demo/trace-grep.sh <traceId>`（跨服务全链路捞取）（FR-006）
- [x] **T311** demo 冒烟：起 order/payment → curl 下单与支付 → 核对 `service=order-service ... ACCESS method=POST uri=... status=200 costMs=...` 与 traceId 一致性（SC-002）；tail-logs.sh / trace-grep.sh 各演练一次（SC-003）

## 批次 E — 收尾

- [x] **T312** 全量回归：`mvn -o clean verify -fae` 全绿（SC-001）
- [x] **T313** 文档收口：spec/ADR 状态推进（已实施）；tasks 勾结；CHANGELOG

## 明确不做（负责人 2026-09-07 拍板）

- 真脱敏实现（`SensitiveBodyMasker` 只留接口 + 透传桩）。
- Loki + Promtail + Grafana 集中式日志采集（列后续期，见 spec §业内调研结论）。
- 基于 AOP 的方法级日志；日志落盘 FileAppender（沿用控制台 + nohup 重定向形态）。
