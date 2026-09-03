package com.payment.catalog.infra.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Redis 健康检查单元测试（014）：验证 PING 判定语义，确保 Windows 上不再出现
 * 「Redis 可用却报 DOWN」的假阴性。
 */
@ExtendWith(MockitoExtension.class)
class WindowsSafeRedisHealthIndicatorTest {

    @Mock
    private RedisConnectionFactory connectionFactory;

    @Mock
    private RedisConnection connection;

    @Test
    void pongReportsUp() {
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("PONG");

        Health health = new WindowsSafeRedisHealthIndicator(connectionFactory).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("ping", "PONG");
    }

    @Test
    void pingIsCaseInsensitive() {
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("pong");

        Health health = new WindowsSafeRedisHealthIndicator(connectionFactory).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void unexpectedPingResponseIsNotUp() {
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("+OK");

        Health health = new WindowsSafeRedisHealthIndicator(connectionFactory).health();

        assertThat(health.getStatus().getCode()).isEqualTo("UNEXPECTED_PING_RESPONSE");
    }

    /**
     * 核心回归点：连接不可用时必须 DOWN，且不能抛出（健康检查抛异常会让整个端点 500）。
     * 这正是原 Spring 实现在 Windows 上的行为：它解析 INFO 时抛出「非法的 Unicode 逃逸」，
     * 把可用实例报成 DOWN。
     */
    @Test
    void connectionFailureReportsDownWithoutThrowing() {
        when(connectionFactory.getConnection()).thenThrow(new IllegalStateException("redis unreachable"));

        Health health = new WindowsSafeRedisHealthIndicator(connectionFactory).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }
}
