package com.payment.payment.api;

import com.payment.payment.application.reliability.ChannelQueryService;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentRepository;
import com.payment.payment.domain.PaymentStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * UNKNOWN 人工队列视图（spec 034 §7.2 / T14）：只读聚合——数量 / 最老年龄 / 年龄分桶 /
 * 队列明细（有界），供告警排查与人工收敛决策，不提供任何写操作。
 *
 * <p>年龄事实源 = 状态机记录的 {@code enteredUnknownAt}（不新增列，spec §7.2 红线）。
 * {@code olderThan}（ISO-8601 时长，如 {@code PT30M}）只做明细过滤（视图聚焦「老」单），
 * 聚合数字始终统计全部 UNKNOWN。明细单页有界（{@code payment.unknown.view-limit}，默认 100），
 * 防大规模积压时响应放大。</p>
 *
 * <p>守卫：{@code /internal/**} 由 {@code InternalServiceAuthInterceptor} 统一鉴权。</p>
 */
@RestController
public class UnknownQueueController {

    private final PaymentRepository paymentRepository;
    private final int viewLimit;

    public UnknownQueueController(PaymentRepository paymentRepository,
                                  @Value("${payment.unknown.view-limit:100}") int viewLimit) {
        this.paymentRepository = paymentRepository;
        this.viewLimit = viewLimit;
    }

    @GetMapping("/internal/payments/unknown")
    public UnknownQueueView unknownQueue(
            @RequestParam(name = "olderThan", required = false) Duration olderThan) {
        Instant now = Instant.now();
        List<Payment> unknown = paymentRepository.findByStatus(PaymentStatus.UNKNOWN);

        Map<String, Long> buckets = new LinkedHashMap<>();
        buckets.put("0_5m", 0L);
        buckets.put("5_30m", 0L);
        buckets.put("30m_24h", 0L);
        buckets.put("gt_24h", 0L);
        long oldestSeconds = 0;
        for (Payment payment : unknown) {
            Duration age = payment.getEnteredUnknownAt() == null ? Duration.ZERO
                    : Duration.between(payment.getEnteredUnknownAt(), now).truncatedTo(ChronoUnit.SECONDS);
            if (age.isNegative()) {
                age = Duration.ZERO;
            }
            buckets.merge(ChannelQueryService.bucketOf(age), 1L, Long::sum);
            oldestSeconds = Math.max(oldestSeconds, age.toSeconds());
        }
        Duration floor = olderThan == null ? Duration.ZERO : olderThan;
        List<UnknownQueueItem> items = unknown.stream()
                .map(p -> UnknownQueueItem.from(p, now))
                .filter(i -> i.ageSeconds() >= floor.toSeconds())
                .sorted((a, b) -> Long.compare(b.ageSeconds(), a.ageSeconds()))
                .limit(viewLimit)
                .toList();
        return new UnknownQueueView(unknown.size(), oldestSeconds, buckets, items);
    }

    /** 队列明细行（有界；金额最小货币单位，禁止浮点）。 */
    public record UnknownQueueItem(String paymentNo, String orderNo, long amountMinor,
                                   String status, int queryAttempts, long ageSeconds) {

        static UnknownQueueItem from(Payment payment, Instant now) {
            Duration age = payment.getEnteredUnknownAt() == null ? Duration.ZERO
                    : Duration.between(payment.getEnteredUnknownAt(), now);
            if (age.isNegative()) {
                age = Duration.ZERO;
            }
            return new UnknownQueueItem(payment.getPaymentNo(), payment.getOrderNo(),
                    payment.getAmountMinor(), payment.getStatus().name(),
                    payment.getQueryAttempts(), age.toSeconds());
        }
    }

    /** 只读聚合视图：总量 / 最老年龄（秒）/ 分桶 / 明细（有界，按年龄降序）。 */
    public record UnknownQueueView(int total, long oldestAgeSeconds, Map<String, Long> buckets,
                                   List<UnknownQueueItem> items) {
    }
}
