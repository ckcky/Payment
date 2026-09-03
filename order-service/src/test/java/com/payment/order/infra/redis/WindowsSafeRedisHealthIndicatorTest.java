package com.payment.order.infra.redis;

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
 * Redis 健康检查单元测试：验证 PING 判定语义，确保 Windows 上不再出现
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
    void unexpectedPingResponseIsNotUp() {
        when(connectionFactory.getConnection()).thenReturn(connection);
        when(connection.ping()).thenReturn("+OK");

        Health health = new WindowsSafeRedisHealthIndicator(connectionFactory).health();

        assertThat(health.getStatus().getCode()).isEqualTo("UNEXPECTED_PING_RESPONSE");
    }

    @Test
    void connectionFailureReportsDownWithoutThrowing() {
        when(connectionFactory.getConnection()).thenThrow(new IllegalStateException("redis unreachable"));

        Health health = new WindowsSafeRedisHealthIndicator(connectionFactory).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }
}
