package com.payment.order.application;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.order.domain.Order;
import com.payment.order.domain.OrderItem;
import com.payment.order.domain.OrderRepository;
import com.payment.order.domain.OrderStatus;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 订单超时调度器（014）：基于 Redis ZSet 实现「时间轮」。
 *
 * <p>下单进入 PENDING_PAYMENT 后 {@link #schedule(Long)} 将一个 member=orderId、score=到期时间戳 的元素
 * 加入 ZSet。{@link #processExpired()} 定期扫描 score ≤ now 的到期项：释放预占库存并取消订单，
 * 随后 ZREM 移除（无论成功失败都移除，避免重复处理）。</p>
 *
 * <p>失败处理：scan 阶段 Redis 不可用时仅记日志、跳过本轮（不阻断下单；超时能力降级）。</p>
 */
@Service
public class OrderTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutScheduler.class);

    private final StringRedisTemplate redis;
    private final OrderRepository orderRepository;
    private final CatalogClient catalogClient;
    private final OrderTimeoutProperties props;
    private final BusinessMetrics metrics;

    public OrderTimeoutScheduler(StringRedisTemplate redis, OrderRepository orderRepository,
                                CatalogClient catalogClient, OrderTimeoutProperties props, BusinessMetrics metrics) {
        this.redis = redis;
        this.orderRepository = orderRepository;
        this.catalogClient = catalogClient;
        this.props = props;
        this.metrics = metrics;
    }

    /** 下单后登记超时（时间轮：score = 当前时间 + ttl）。 */
    public void schedule(Long orderId) {
        if (!props.isEnabled()) {
            return;
        }
        long score = System.currentTimeMillis() + props.getTtlSeconds() * 1000L;
        redis.opsForZSet().add(props.getZsetKey(), String.valueOf(orderId), score);
    }

    @Scheduled(fixedDelayString = "${order.timeout.poll-millis:5000}")
    public void processExpired() {
        if (!props.isEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        Set<String> expired;
        try {
            expired = redis.opsForZSet().rangeByScore(props.getZsetKey(), 0, now);
        } catch (RuntimeException ex) {
            log.warn("order timeout scan failed (redis unavailable?), skip this round: {}", ex.getMessage());
            return;
        }
        if (expired == null || expired.isEmpty()) {
            return;
        }
        int handled = 0;
        for (String id : expired) {
            try {
                handleExpired(Long.valueOf(id));
                handled++;
            } catch (RuntimeException ex) {
                log.warn("handle expired order {} failed: {}", id, ex.getMessage());
            } finally {
                redis.opsForZSet().remove(props.getZsetKey(), id); // 无论成败都移除，避免重复处理
            }
        }
        if (handled > 0) {
            metrics.counter("order.timeout.cancelled_total", (double) handled, "module", "order");
        }
    }

    private void handleExpired(Long orderId) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
            return;
        }
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT
                && order.getStatus() != OrderStatus.PENDING_CONFIRMATION) {
            return; // 已支付/已取消等，跳过
        }
        // 释放预占库存（幂等：无预占或已确认均吸收）+ 回补秒杀配额（漏了会永久少卖）
        for (OrderItem item : order.getItems()) {
            Long skuId = Long.parseLong(item.getSkuId());
            catalogClient.releaseStock(new ReleaseStockCommand(
                    reservationId(orderId, item.getSkuId()),
                    skuId, item.getQuantity()));
            catalogClient.rollbackSeckill(skuId, item.getQuantity());
        }
        order.cancel();
        orderRepository.save(order);
    }

    private static String reservationId(Long orderId, String skuId) {
        return "order:" + orderId + ":sku:" + skuId;
    }
}
