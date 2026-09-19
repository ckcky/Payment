# Feature Specification: 统一访问日志——结束时单条 ACCESS + 固定格式含服务名 + 全链路日志查看

**Feature Branch**: `021-unified-access-logging`

**Created**: 2026-09-07

**Status**: ✅ Accepted → Implemented（2026-09-07 负责人逐项拍板，见 [ADR-0068](../../adr/0029-unified-access-logging.md)；代码已实施，任务见 [tasks.md](tasks.md)）

**Input**: 负责人 2026-09-07 日志规范化讨论（原文归纳）：

> 「日志要统一一下，每个请求的入口和出口都要加上日志，入口打 request 完整报文，出口处的日志打 response 报文，还要加上耗时信息比如耗时多少毫秒。」
> 「每个日志格式也要固定一下 [服务名]。应该还有很多好的规范，你看看业内比较先进的做法是什么样的。」

> 追加拍板（2026-09-07 第二轮）：①入口/出口两条日志**改为结束时一条 ACCESS**（全字段单行）；②敏感字段脱敏**只留桩**（接口 + 透传默认实现，本期不真脱敏）；③GET 请求全量打；④报文截断阈值 4KB；⑤追问「系统跑起来后怎么看日志，不可能一个文件一个文件翻」——本期补聚合查看脚本，集中式采集（Loki）列后续期。

## 当前代码现实（已核实，禁止按绿地项目理解）

| # | 缺口 | 代码证据 | 影响 |
|---|---|---|---|
| **G1** | 无任何请求级访问日志 | 全仓无 `CommonsRequestLoggingFilter`/`ContentCaching*Wrapper`/`@Aspect`；请求进出只有业务代码零散 log | 排障时看不到「这个请求打了什么、回了什么、花了多久」，只能靠业务日志反推 |
| **G2** | 日志格式无服务名标识 | `common-core/logback-spring.xml` pattern 只有时间/level/thread/traceId/logger/msg | 14 个服务日志聚合后无法区分来源；跨服务检索必须靠文件名 |
| **G3** | 定时任务/线程池 MDC 断链 | `OrderTimeoutScheduler`、`ChannelQueryScheduler`、`TimeoutScanScheduler`（`ReliabilityConfig` 线程池）、`AuditScheduler` 均未传播 MDC/TraceContext | 后台线程打的日志没有 traceId，链路追踪在异步处断开 |
| **G4** | 日志查看无工具 | demo 10 服务 = `deployment/logs/` 下 10 个文件，纯手工翻 | 「怎么看日志」无答案；跨服务排障效率极低 |
| **G5** | 报文日志无规范 | 无截断、无脱敏钩子、无开关 | 若各服务自行打报文，会出现大报文刷爆日志与敏感信息泄露风险 |

## 业内调研结论（2026-09-07 核实）

| 业内方 | 做法 | 对本设计的启示 |
|---|---|---|
| Spring 官方 `CommonsRequestLoggingFilter` | 只打请求、无响应、无耗时 | 太弱，不采用 |
| Spring 生态主流实践 | `OncePerRequestFilter` + `ContentCachingRequestWrapper/ResponseWrapper`：完整报文 + 状态码 + 全链路耗时，一个过滤器覆盖全部端点 | **采用**（计时口径=整条请求，含序列化） |
| AOP 切 Controller | 拿方法入参对象，拿不到原始报文与响应头写出时机 | 不采用（业内不推荐做报文日志） |
| 网关/接入层日志形态 | 两种流派：请求结束打**一条结构化 ACCESS**（method/path/status/耗时/请求体/响应体）vs IN/OUT 两条 | 负责人最终拍板**一条 ACCESS**（先要两条后修正）：天然对齐、检索一条顶两条 |
| 阿里《Java 开发手册》日志规约 | 敏感信息脱敏、日志分级、占位符、异常必须带堆栈 | 脱敏为硬要求——本期留桩（拍板），钩子先行 |
| 大厂生产环境趋势 | 集中式采集：ELK 或 **Loki+Promtail+Grafana**（轻量替代，资源 1/10） | 项目已有 Grafana，后续期补 Loki 即可；本期先用聚合脚本 |

## 决策记录（负责人 2026-09-07 逐项拍板）

| # | 决策点 | 拍板结果 |
|---|---|---|
| D1 | 实现方式 | `OncePerRequestFilter` + `ContentCachingRequestWrapper/ResponseWrapper`（非 AOP、非 CommonsRequestLoggingFilter） |
| D2 | 日志条数 | **结束时一条 ACCESS**（method/uri/status/costMs/req/resp 全字段单行），放弃 IN/OUT 两条 |
| D3 | 脱敏 | **预留桩**：`SensitiveBodyMasker` 接口 + 透传默认实现；Filter 固定调用钩子，本期不真脱敏，将来换实现零侵入 |
| D4 | 报文口径 | GET 全量打（无 body 时 `req=-`）；截断阈值 **4KB**；排除 `/actuator/**`；multipart/二进制只打头不打 body |
| D5 | 格式固定含服务名 | logback `<springProperty source="spring.application.name">` 注入，pattern 追加 `service=` 字段，全服务统一 |
| D6 | 日志查看 | 本期补 `tail-logs.sh`（全栈合并实时视图）+ `trace-grep.sh <traceId>`（跨服务全链路捞取）；Loki+Promtail+Grafana 集中式采集列后续期 |
| D7 | 异步链路 MDC | 顺带修复 G3：`MdcTaskDecorator` + 各 Scheduler 入口补 traceId（定时任务日志纳入 traceId 体系） |

## 目标链路

```
【入站】请求 → TraceIdFilter（order=-200：提取/生成 X-Trace-Id → MDC/TraceContext）
        → AccessLogFilter（order=-100：ContentCaching 包裹；若排除路径/multipart 则直接放行不记录）
        → 业务 Controller（行为不变）
【出站】GlobalExceptionHandler 兜底（如有异常）→ AccessLogFilter finally：
        一条 ACCESS 日志（ACCESS_LOG 专用 logger，INFO）：
        ACCESS method={} uri={} status={} costMs={} req={} resp={}（req/resp 经 masker 桩 + 4KB 截断）
        → copyBodyToResponse() 把缓存响应写回客户端
【异步】@Scheduled / 自定义线程池 → MdcTaskDecorator 或入口补 TraceContext.getOrCreate()+MDC.put
        → 后台日志同带 traceId，可被 trace-grep.sh 捞出
【查看】tail-logs.sh：全栈实时合并视图（[服务名] 前缀）
        trace-grep.sh <traceId>：跨 10 个日志文件捞全链路
```

## 用户故事与验收标准

### US1：每个 HTTP 请求一条完整访问日志
**As** 开发者/运维，**I want** 每个请求结束时自动落一条含方法、路径、状态码、耗时（毫秒）、请求报文、响应报文的 ACCESS 日志，**so that** 排障时一眼看清「打了什么、回了什么、多久」。
- AC1.1 所有 HTTP 端点（含 GET）请求结束必出一条 ACCESS；异常路径（500）同样必出。
- AC1.2 `costMs` 为 Filter 层整链路耗时；body 超 4KB 截断并带省略标记；GET 请求 `req=-`。
- AC1.3 `/actuator/**` 不产生 ACCESS 日志；`common.access-log.enabled=false` 可整体关闭。

### US2：日志格式全局统一且自带服务名
**As** 开发者，**I want** 全部服务日志行格式一致并携带 `service=<服务名>`，**so that** 多服务日志聚合后仍可区分来源、统一检索。
- AC2.1 各服务日志行均含 `service=<spring.application.name>`。
- AC2.2 服务代码零改动（logback springProperty 注入），无服务名时回退 `unknown`。

### US3：异步链路日志可追踪
**As** 开发者，**I want** 定时任务与自定义线程池的日志带 traceId，**so that** trace-grep 能把后台动作与触发它的请求串起来。
- AC3.1 4 个 Scheduler 与 `ReliabilityConfig` 线程池日志带 traceId（入口生成或传播）。
- AC3.2 线程复用不串号（MDC 用后清理）。

### US4：日志可看、链路可捞
**As** 运维/开发者，**I want** 一条命令看全栈实时日志、一条命令按 traceId 捞全链路，**so that** 不再逐文件翻日志。
- AC4.1 `tail-logs.sh` 输出带 `[服务名]` 前缀的合并实时日志。
- AC4.2 `trace-grep.sh <traceId>` 命中全部相关服务的日志行。

## 功能需求（FR）

- **FR-001** common-core 新增 `accesslog` 包：`AccessLogFilter`（`OncePerRequestFilter` + ContentCaching 包装）、`AccessLogProperties`（`common.access-log.*`：`enabled=true`/`maxBodyBytes=4096`/`excludePaths=[/actuator/**]`）、`SensitiveBodyMasker`（接口 + 透传桩）。
- **FR-002** 装配：`CommonCoreAutoConfiguration` 用 `FilterRegistrationBean` 注册并显式定序——`TraceIdFilter` order=-200（先写 MDC）、`AccessLogFilter` order=-100；`enabled=false` 不装配；各服务零代码改动。
- **FR-003** ACCESS 日志走专用 logger 名 `ACCESS_LOG`（可独立路由）；单行 key=value，字段：`method/uri/status/costMs/req/resp`（traceId 由 MDC 经 pattern 输出）。
- **FR-004** logback-spring.xml：`<springProperty source="spring.application.name">` 注入，pattern 追加 `service=` 字段。
- **FR-005** MDC 传播：新增 `MdcTaskDecorator`（trace 包）；`ReliabilityConfig` 线程池装饰；4 个 Scheduler 入口补 `TraceContext.getOrCreate()` + `MDC.put`，用后清理。
- **FR-006** 查看脚本 `deployment/demo/tail-logs.sh`、`deployment/demo/trace-grep.sh`（Git Bash 兼容）。

## 非功能需求（NFR）

- **NFR-001** 报文截断与排除路径均可配置；关闭开关只关 ACCESS，不影响既有日志。
- **NFR-002** 过滤器不得改变响应语义：`copyBodyToResponse()` 必须执行；过滤器自身异常不得吞掉请求（catch 后降级仅打日志）。
- **NFR-003** masker 桩为唯一脱敏入口，接口签名含 contentType（将来 JSON 字段级脱敏与二进制跳过都在实现内解决）。

## 成功标准（SC）

- **SC-001** `mvn -o clean verify -fae` 全绿。
- **SC-002** demo 冒烟：起 order/payment → curl 下单与支付接口 → 日志可见 `service=order-service ... ACCESS method=POST uri=... status=200 costMs=... req=... resp=...`，且同一请求 traceId 与业务日志一致。
- **SC-003** `trace-grep.sh <traceId>` 能捞出该请求跨服务的全部日志（含定时任务触发的下游动作日志）。

## 依赖与风险

- **依赖**：无前置 spec（纯 common-core 基建 + 脚本）；与 spec 020（demo 界面设计系统）无耦合。
- **风险**：ContentCachingResponseWrapper 对流式响应有延迟写出影响——本项目无 SSE，mock-channel-web 静态资源经 excludePaths 排除；报文日志会使日志量上升——4KB 截断 + 开关兜底。
- **明确不做**（负责人拍板）：真脱敏实现（只留桩）；Loki/Promtail 集中采集（后续期）；基于 AOP 的方法级日志；日志落盘 FileAppender（沿用控制台 + nohup 重定向形态）。
