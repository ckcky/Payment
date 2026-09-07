# Plan: 021-unified-access-logging

> 技术方案。决策依据见 [spec.md](spec.md)（D1~D7）与 [ADR-0068](../../adr/0029-unified-access-logging.md)。

## 1. 组件设计（common-core `accesslog` 包）

### 1.1 AccessLogProperties（配置）

`@ConfigurationProperties("common.access-log")`，record 构造器绑定（项目首个属性类，走 Boot 3 推荐风格）：

```java
public record AccessLogProperties(
        boolean enabled,          // 默认 true；false 时整个 Filter 不装配
        int maxBodyBytes,         // 默认 4096
        List<String> excludePaths // 默认 ["/actuator/**"]
) { }
```

### 1.2 SensitiveBodyMasker（脱敏桩，D3）

```java
public interface SensitiveBodyMasker {
    String mask(String contentType, String body);
}
```

- 默认实现 `PassThroughBodyMasker`：原样返回（**本期预留桩**）。
- `AccessLogFilter` 在落日志前固定调用此钩子——将来启用真脱敏（JSON 字段级 mask、二进制跳过）只换实现，`AccessLogFilter` 与各服务零改动。
- 注册于 `CommonCoreAutoConfiguration`（`@ConditionalOnMissingBean`，服务可覆盖）。

### 1.3 AccessLogFilter（核心）

```java
public class AccessLogFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger("ACCESS_LOG");
    // 依赖：AccessLogProperties、SensitiveBodyMasker
}
```

处理流程：

1. `shouldNotFilter()`：`AntPathMatcher` 匹配 `excludePaths` 命中则放行不记录（`/actuator/**` 默认排除）。
2. 包装：`new ContentCachingRequestWrapper(request)` / `new ContentCachingResponseWrapper(response)`。
3. `long start = System.nanoTime()` → `chain.doFilter(wrappedReq, wrappedResp)`（**不 catch 业务异常**，交给 `GlobalExceptionHandler`；用 try/finally 保证 ACCESS 必打）。
4. finally 内组装字段落一条 INFO：
   - `method`：request.getMethod()
   - `uri`：含 query string
   - `status`：wrappedResp.getStatus()（异常时为 500，resp 为 GlobalExceptionHandler 写入的 JSON）
   - `costMs`：`(System.nanoTime()-start)/1_000_000`
   - `req`：请求体（GET 无 body → `-`）；`resp`：响应体
   - body 取值：ContentCaching 缓存字节数组 → UTF-8 → masker 桩 → 截断（>4096 字节截断 + `...(truncated,total=N)`）
   - multipart / 非 text JSON content-type：不打 body（占位 `<binary>`），防二进制刷屏
5. **必须 `wrappedResp.copyBodyToResponse()`**（放在 finally 内、落日志之后）——否则客户端收不到响应体。
6. Filter 自身异常降级：catch 后仅打 WARN，不影响请求（NFR-002）。

日志行最终形态（traceId 由 MDC 经 pattern 输出）：

```
2026-09-07T10:30:00.123+08:00 level=INFO thread="http-nio-8083-exec-1" traceId=5f0e... service=order-service logger=ACCESS_LOG msg="ACCESS method=POST uri=/api/orders status=200 costMs=37 req={\"idempotencyKey\":...} resp={\"orderNo\":\"OR...\"}"
```

### 1.4 MdcTaskDecorator（trace 包，D7）

捕获提交线程的 MDC + TraceContext → 包装 Runnable 在执行线程恢复 → finally 清理。供 `ThreadPoolTaskExecutor.setTaskDecorator()` 使用；线程复用不串号（与 TraceIdFilter 的 finally 清理同一纪律）。

## 2. 装配与定序（FR-002）

`CommonCoreAutoConfiguration` 追加：

```java
@Bean @ConditionalOnMissingBean
public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration() { ... setOrder(-200); }

@Bean
@ConditionalOnProperty(prefix = "common.access-log", name = "enabled", havingValue = "true", matchIfMissing = true)
public FilterRegistrationBean<AccessLogFilter> accessLogFilterRegistration(props, masker) { ... setOrder(-100); }
```

- 现有 `TraceIdFilter` 裸 bean 注册顺序不定 → 改为 `FilterRegistrationBean` 显式 -200，**保证 IN 时 MDC 已有 traceId**。
- `AccessLogFilter` order=-100：晚于 TraceIdFilter（拿得到 traceId），早于业务 Filter。
- 各服务 pom 已依赖 common-core → 14 个服务自动生效，零代码改动。

## 3. 日志格式固定含服务名（FR-004）

`common-core/src/main/resources/logback-spring.xml`：

```xml
<springProperty scope="context" name="svc" source="spring.application.name" defaultValue="unknown"/>
<pattern>%d{...} level=%-5level thread="%thread" traceId="%X{traceId:-N/A}" service=${svc} logger=%logger{36} msg="%msg"%n</pattern>
```

服务名来自各服务 `application.yml` 的 `spring.application.name`（启动期读一次，无运行时开销）。IDEA 直接跑与 nohup 跑均生效。

## 4. 定时任务 MDC 修复（FR-005，最小侵入）

| 落点 | 改法 |
|---|---|
| `payment/.../reliability/ReliabilityConfig`（线程池） | `executor.setTaskDecorator(new MdcTaskDecorator())` |
| `ChannelQueryScheduler` / `TimeoutScanScheduler` / `order OrderTimeoutScheduler` / `reconciliation AuditScheduler`（@Scheduled，调度线程池非 Spring MVC 线程） | 方法体首行 `TraceContext.getOrCreate(); MDC.put(TraceIdFilter.MDC_KEY, TraceContext.getTraceId());` + finally 清理（抽一个小工具方法 `TraceContext.runWithNewTrace(Runnable)` 收敛样板代码） |

> 备选：`SchedulingConfigurer` 全局装饰——侵入面大（各服务要改配置类），否决；入口显式补齐最直白。

## 5. 日志查看脚本（FR-006，`deployment/demo/`）

- **tail-logs.sh**：`tail -f "$LOG_DIR"/*.log` 按文件流合并，逐行加 `[服务名]` 前缀（从文件名推导）；支持 `tail-logs.sh <关键词>` 附加过滤。
- **trace-grep.sh <traceId>**：`grep -h "traceId=$1" "$LOG_DIR"/*.log`，按文件修改时间排序输出全链路。

## 6. 测试方案

| 用例 | 断言 |
|---|---|
| ACCESS 正常路径（MockHttpServletRequest/Response，照 TraceIdFilterTest 风格） | 一条 ACCESS 全字段齐、costMs≥0、status=200 |
| 异常路径 | chain 抛异常仍打一条 ACCESS（status 语义由测试桩模拟 500） |
| 4KB 截断 | 超长 body 出现 `(truncated,total=N)` |
| 排除路径 | `/actuator/health` 不触发记录 |
| GET | `req=-` |
| 脱敏桩 | masker 被调用、透传实现不改内容 |
| MdcTaskDecorator | 子线程拿到父线程 MDC、执行后清理 |
| copyBodyToResponse | 响应内容仍完整写回客户端 |
| ArchUnit | accesslog 包不越界（仅依赖 slf4j/spring-web/spring-boot） |
