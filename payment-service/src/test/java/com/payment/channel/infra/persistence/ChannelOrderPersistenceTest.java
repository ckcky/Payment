package com.payment.channel.infra.persistence;

import com.payment.channel.domain.ChannelOrder;
import com.payment.channel.domain.ChannelOrderErrorType;
import com.payment.channel.domain.ChannelOrderRepository;
import com.payment.channel.domain.ChannelOrderStatus;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.payment.PaymentApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 渠道单持久化集成测试（spec 041 / T08 验收：真库唯一键 / 并发）：
 * PO↔领域映射、{@code uk_attempts_channel_no} / {@code uk_attempts_channel_reference}
 * 唯一键兜底、乐观锁并发拒绝。
 *
 * <p>与 {@code payment/infra/persistence/PaymentPersistenceTest#attemptRoundTrip} 的关系：
 * 后者从 Payment 侧顺带验渠道单映射；本类是渠道域<b>自有</b>的持久化验收，
 * 覆盖唯一键与并发这两个 Payment 侧不关心的问题。</p>
 */
// classes 必须显式给：启动类 PaymentApplication 在 com.payment.payment 包，
// 从 com.payment.channel.** 向上找不到 @SpringBootConfiguration
@SpringBootTest(classes = PaymentApplication.class)
class ChannelOrderPersistenceTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 每用例唯一支付单号：schema.sql 只在上下文启动时刷一次，同号会跨用例互相污染。 */
    private static String paymentNo() {
        return "PM-T08-" + String.format("%016d", SEQ.incrementAndGet());
    }

    @Autowired
    private ChannelOrderRepository orderRepository;

    @Test
    @DisplayName("roundTrip：字段全量映射（channelNo / errorType / extra 模态 / REFUND 类型）")
    void channelOrderRoundTrip() {
        String paymentNo = paymentNo();
        ChannelOrder order = ChannelOrder.refundAttempt(paymentNo, "MOCK", 100L, "CNY");
        order.setErrorType(ChannelOrderErrorType.TRANSIENT);
        order.putExtra(ChannelOrder.CHANNEL_MODE_KEY, "SANDBOX");
        order.accept("ch-ref-rt");
        orderRepository.save(order);

        ChannelOrder reloaded = orderRepository.findByChannelNo(order.getChannelNo()).orElseThrow();
        assertThat(reloaded.getPaymentNo()).isEqualTo(paymentNo);
        assertThat(reloaded.getChannelNo()).isEqualTo(order.getChannelNo());
        assertThat(reloaded.getChannelCode()).isEqualTo("MOCK");
        assertThat(reloaded.getAttemptType()).isEqualTo(ChannelOrder.TYPE_REFUND);
        assertThat(reloaded.getStatus()).isEqualTo(ChannelOrderStatus.ACCEPTED);
        assertThat(reloaded.getChannelReference()).isEqualTo("ch-ref-rt");
        assertThat(reloaded.getErrorType()).isEqualTo(ChannelOrderErrorType.TRANSIENT);
        assertThat(reloaded.getExtra())
                .containsExactly(Map.entry(ChannelOrder.CHANNEL_MODE_KEY, "SANDBOX"));
        assertThat(reloaded.getChannelMode().name()).isEqualTo("SANDBOX");
        assertThat(reloaded.getVersion()).isEqualTo(1);
        assertThat(orderRepository.findByPaymentNo(paymentNo)).hasSize(1);
    }

    @Test
    @DisplayName("唯一键：channelNo 重复插入被拒（uk_attempts_channel_no）")
    void duplicateChannelNoRejected() {
        String paymentNo = paymentNo();
        ChannelOrder first = new ChannelOrder(paymentNo, "CH-DUP-0000000000000001", "MOCK", 0, 100L, "CNY");
        orderRepository.save(first);

        ChannelOrder second = new ChannelOrder(paymentNo, "CH-DUP-0000000000000001", "MOCK", 1, 100L, "CNY");
        assertThatThrownBy(() -> orderRepository.save(second))
                .as("同一网关单号落库第二次必须撞唯一键——单号是跨域身份，绝不复用")
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("唯一键：channel_reference 重复插入被拒（uk_attempts_channel_reference）")
    void duplicateChannelReferenceRejected() {
        String paymentNo = paymentNo();
        ChannelOrder first = new ChannelOrder(paymentNo, "CH-REF-0000000000000001", "MOCK", 0, 100L, "CNY");
        first.accept("ch-ref-dup");
        orderRepository.save(first);

        ChannelOrder second = new ChannelOrder(paymentNo, "CH-REF-0000000000000002", "MOCK", 0, 100L, "CNY");
        second.accept("ch-ref-dup");
        assertThatThrownBy(() -> orderRepository.save(second))
                .as("渠道引用是幂等收敛的权威锚点，重复引用必须被 DB 拒绝（INV-004 兜底）")
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("乐观锁：陈旧版本 save 被拒（CONFLICT），先到者的事实不被覆盖")
    void optimisticLockRejectsStaleUpdate() {
        ChannelOrder order = new ChannelOrder(paymentNo(), "CH-LOCK-000000000000001", "MOCK", 0, 100L, "CNY");
        orderRepository.save(order);

        ChannelOrder first = orderRepository.findByChannelNo(order.getChannelNo()).orElseThrow();
        ChannelOrder second = orderRepository.findByChannelNo(order.getChannelNo()).orElseThrow();

        first.markUnknown("timeout");
        orderRepository.save(first);

        second.fail("late failure");
        assertThatThrownBy(() -> orderRepository.save(second))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.CONFLICT));

        ChannelOrder reloaded = orderRepository.findByChannelNo(order.getChannelNo()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ChannelOrderStatus.UNKNOWN);
        assertThat(reloaded.getFailureReason()).isEqualTo("timeout");
    }

    @Test
    @DisplayName("并发：8 线程竞争同一 channelNo ⇒ 恰好一个赢家（INV-004 不产生第二笔渠道单）")
    void concurrentSameChannelNoHasSingleWinner() throws InterruptedException {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch fire = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            int seq = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    fire.await();
                    ChannelOrder order = new ChannelOrder(
                            paymentNo(), "CH-RACE-0000000000000001", "MOCK", seq, 100L, "CNY");
                    orderRepository.save(order);
                    winners.incrementAndGet();
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        fire.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(winners.get())
                .as("同一 channelNo 的并发开立必须被唯一键收敛到恰好一笔，实际赢家=%d", winners.get())
                .isEqualTo(1);
        assertThat(failures)
                .as("输家只允许是撞唯一键，不允许其他失败形态")
                .allMatch(t -> t instanceof DuplicateKeyException);
        assertThat(orderRepository.findByChannelNo("CH-RACE-0000000000000001")).isPresent();
    }

    @Test
    @DisplayName("回读权威形态：rehydrate 落库的 requestedAt 原样可读回")
    void rehydratePreservesTimestamps() {
        Instant requestedAt = Instant.now().minusSeconds(30);
        ChannelOrder order = ChannelOrder.rehydrate(null, paymentNo(), "CH-TS-00000000000000001", "MOCK", 0,
                requestedAt, null, null, ChannelOrderStatus.PENDING, null, null, null,
                ChannelOrder.TYPE_PAYMENT, 100L, "CNY", null);
        orderRepository.save(order);

        ChannelOrder reloaded = orderRepository.findByChannelNo("CH-TS-00000000000000001").orElseThrow();
        // 列是秒级 TIMESTAMP：按 epochSecond 比较，避免纳秒/毫秒精度抖动造成假失败
        assertThat(reloaded.getRequestedAt().getEpochSecond()).isEqualTo(requestedAt.getEpochSecond());
    }
}
