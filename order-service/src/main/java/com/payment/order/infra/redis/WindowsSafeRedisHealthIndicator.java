package com.payment.order.infra.redis;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/**
 * 覆盖 Spring Boot 自动配置的 Redis 健康检查（bean 名必须为 {@code redisHealthIndicator}，
 * 自动配置以 {@code @ConditionalOnMissingBean(name = "redisHealthIndicator")} 退让）。
 *
 * <h2>为什么要覆盖</h2>
 * Spring Boot 默认的 {@code RedisHealthIndicator} 会读取 Redis {@code INFO} 的全部字段，
 * 并通过 Spring Data Redis 的 {@code Converters#toProperties} 把整段文本交给
 * {@link java.util.Properties#load} 解析。而 {@code Properties} 会把「反斜杠 + u」
 * 视为 Unicode 转义起点。
 *
 * <p>Windows 上 Redis 的 {@code INFO} 含 {@code executable} 字段，其值是形如
 * {@code c:&#92;users&#92;...&#92;redis-server.exe} 的本地路径；「反斜杠 + u」之后跟的是
 * {@code sers}，并非合法的四位十六进制，于是抛出：
 * <pre>IllegalArgumentException: Malformed &#92;uxxxx encoding</pre>
 * 健康检查因此<b>恒定报 DOWN</b>——即使 Redis 完全可用。
 *
 * <p>后果很实际：把正常实例报成故障，既掩盖真实故障，也让「Redis 不可用」告警失去信噪比。
 * 本实现改用 {@code PING} 判定连通性，完全不解析 {@code INFO}，Windows/Linux 行为一致。
 *
 * <p>与 catalog-service 同名类为有意重复：仅两个服务使用 Redis，抽公共模块的成本高于收益；
 * 若第三个服务引入 Redis，应提取为 {@code common-redis} 模块（见对应 ADR）。
 *
 * <p><b>注</b>：本文件注释刻意用 {@code &#92;u} 而非字面「反斜杠 + u」——javac 在词法分析前
 * 会对全文件（含注释）做 Unicode 转义预处理，写字面量会导致「非法的 Unicode 逃逸」编译错误。
 */
@Component("redisHealthIndicator")
public class WindowsSafeRedisHealthIndicator implements HealthIndicator {

    private final RedisConnectionFactory connectionFactory;

    public WindowsSafeRedisHealthIndicator(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    @Override
    public Health health() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            String pong = connection.ping();
            if (pong != null && "PONG".equalsIgnoreCase(pong.trim())) {
                return Health.up().withDetail("ping", pong).build();
            }
            return Health.status(new Status("UNEXPECTED_PING_RESPONSE"))
                    .withDetail("ping", String.valueOf(pong))
                    .build();
        } catch (RuntimeException ex) {
            return Health.down(ex).build();
        }
    }
}
