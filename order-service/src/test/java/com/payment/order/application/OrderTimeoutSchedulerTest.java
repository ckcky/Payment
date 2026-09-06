package com.payment.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.order.application.ratelimit.RateLimitProperties;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderItem;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

/**
 * 订单超时调度器单元测试（014）：验证 ZSet 登记与到期扫描的编排（Redis 用 mock）。
 */
@ExtendWith(MockitoExtension.class)
class OrderTimeoutSchedulerTest {

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ZSetOperations<String, String> zSet;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private CatalogClient catalogClient;
    @Mock
    private BusinessMetrics metrics;

    private OrderTimeoutScheduler scheduler;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForZSet()).thenReturn(zSet);
        OrderTimeoutProperties props = new OrderTimeoutProperties();
        props.setEnabled(true);
        props.setTtlSeconds(900);
        props.setZsetKey("order:timeouts");
        scheduler = new OrderTimeoutScheduler(redis, orderRepository, catalogClient, props, metrics);
    }

    @Test
    void scheduleAddsZSetMemberWithExpiryScore() {
        scheduler.schedule(42L);

        ArgumentCaptor<Double> score = ArgumentCaptor.forClass(Double.class);
        verify(zSet).add(eq("order:timeouts"), eq("42"), score.capture());
        // score 应约为 now + 900s
        long expected = System.currentTimeMillis() + 900_000L;
        assertThat(score.getValue()).isBetween((double) (expected - 2000), (double) (expected + 2000));
    }

    @Test
    void scheduleSkippedWhenDisabled() {
        OrderTimeoutProperties props = new OrderTimeoutProperties();
        props.setEnabled(false);
        OrderTimeoutScheduler disabled = new OrderTimeoutScheduler(redis, orderRepository, catalogClient, props, metrics);
        disabled.schedule(1L);
        verify(zSet, times(0)).add(anyString(), anyString(), anyDouble());
    }

    @Test
    void processExpiredCancelsPendingOrderAndReleasesStockAndRemovesMember() {
        when(zSet.rangeByScore(eq("order:timeouts"), anyDouble(), anyDouble())).thenReturn(Set.of("7"));
        OrderItem item = new OrderItem("OI-TEST-1", "7", "SKU", "n", 2, 100L, "CNY");
        Order order = new Order("u1", "m1", "CNY", List.of(item));
        order.confirm(); // PENDING_PAYMENT
        when(orderRepository.findById(7L)).thenReturn(java.util.Optional.of(order));

        scheduler.processExpired();

        // 释放库存 + 回补秒杀配额 + 取消订单 + 移除 ZSet 成员
        verify(catalogClient).releaseStock(org.mockito.ArgumentMatchers.any(ReleaseStockCommand.class));
        // 不回补秒杀配额会导致超时订单的 Redis 配额永久泄漏（少卖），故必须一并回补
        verify(catalogClient).rollbackSeckill(7L, 2L);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        verify(orderRepository).save(order);
        verify(zSet).remove("order:timeouts", "7");
    }
}
