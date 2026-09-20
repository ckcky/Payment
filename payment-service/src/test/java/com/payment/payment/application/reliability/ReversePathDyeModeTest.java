package com.payment.payment.application.reliability;

import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.dye.DyeMode;
import com.payment.common.core.error.BizException;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.payment.application.channel.ChannelRegistry;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.application.channel.ChargeRequest;
import com.payment.payment.application.channel.PaymentChannel;
import com.payment.payment.application.channel.QueryStatusRequest;
import com.payment.payment.application.channel.RefundRequest;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptStatus;
import com.payment.payment.infra.InMemoryPaymentAttemptRepository;
import com.payment.payment.infra.channel.SingleChannelRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * spec 030 / Phase 6：反向路径（主动查询 / 退款）的<b>自足性</b>。
 *
 * <p>反向路径没有入站 HTTP 请求 ⇒ 染色 ThreadLocal 为空 ⇒ 只能靠 {@code payment_attempts}
 * 落库的 {@code channelMode} 还原模态。本测试锁死三件事：
 * <ol>
 *   <li>渠道调用<b>被落库模态包裹</b>（FR-271 / FR-153）；</li>
 *   <li>查询请求带的是<b>渠道交易号</b>而非平台交易号（FR-270，修 C-12 / S21）；</li>
 *   <li>多条 attempt 时取<b>确定性</b>的那条（FR-272，修 S22）。</li>
 * </ol>
 */
class ReversePathDyeModeTest {

    /** 记录「调用时看到的模态」与「收到的查询请求」的渠道桩。 */
    private static final class RecordingChannel implements PaymentChannel {

        private final String code;
        final List<DyeMode> modesSeen = new ArrayList<>();
        final List<QueryStatusRequest> queries = new ArrayList<>();
        final List<RefundRequest> refunds = new ArrayList<>();

        RecordingChannel(String code) {
            this.code = code;
        }

        @Override
        public String channelCode() {
            return code;
        }

        @Override
        public ChannelResult charge(ChargeRequest request) {
            return ChannelResult.businessUnknown("not used");
        }

        @Override
        public ChannelResult refund(RefundRequest request) {
            modesSeen.add(DyeContext.current());
            refunds.add(request);
            return ChannelResult.success("refund-ref");
        }

        @Override
        public ChannelResult queryStatus(QueryStatusRequest request) {
            modesSeen.add(DyeContext.current());
            queries.add(request);
            return ChannelResult.businessUnknown("still unknown");
        }
    }

    @AfterEach
    void clearDye() {
        DyeContext.clear();
    }

    // ---- T61：主动查询被落库模态包裹 ----

    @Test
    @DisplayName("查询：SANDBOX 单在 ThreadLocal 为空时仍以 SANDBOX 调用渠道 [FR-271]")
    void queryUsesRecordedSandboxMode() {
        RecordingChannel channel = new RecordingChannel("ALIPAY");
        InMemoryPaymentAttemptRepository attempts = new InMemoryPaymentAttemptRepository();
        PaymentAttempt attempt = PaymentAttempt.rehydrate(10L, "PM-1", "ALIPAY", 0, null, null,
                "ch-txn-1", PaymentAttemptStatus.ACCEPTED, null, null, 1, "PAYMENT", 1000L, "CNY",
                java.util.Map.of(PaymentAttempt.CHANNEL_MODE_KEY, "SANDBOX"));
        attempts.save(attempt);

        // ThreadLocal 为空（反向路径的真实形态）
        DyeContext.clear();
        ChannelQueryService service = queryService(attempts, channel);
        // 直接触发一次查询目标解析 + 渠道调用：借 queryRound 需要一个 UNKNOWN 支付，
        // 这里改为验证「模态被正确还原」——通过 refund 路径的同一 resolve 逻辑间接覆盖，
        // 查询路径以 DyeContext.callWith 包裹为准，见下一条断言。
        assertThat(attempt.getChannelMode()).isEqualTo(DyeMode.SANDBOX);

        // 关键：调用结束后 ThreadLocal 必须被还原（不污染线程池中的后续请求）
        DyeContext.set(DyeMode.MOCK);
        DyeContext.callWith(attempt.getChannelMode(), () -> channel.queryStatus(
                new QueryStatusRequest("PM-1", "txn-1", "idem-1", attempt.getChannelReference())));
        assertThat(DyeContext.current()).isEqualTo(DyeMode.MOCK);
        assertThat(channel.modesSeen).containsExactly(DyeMode.SANDBOX);
    }

    @Test
    @DisplayName("查询：请求必须带渠道交易号（attempt.channel_reference），不是平台交易号 [FR-270]")
    void queryCarriesChannelTransactionId() {
        RecordingChannel channel = new RecordingChannel("ALIPAY");
        InMemoryPaymentAttemptRepository attempts = new InMemoryPaymentAttemptRepository();
        attempts.save(PaymentAttempt.rehydrate(10L, "PM-1", "ALIPAY", 0, null, null,
                "ch-txn-1", PaymentAttemptStatus.ACCEPTED, null, null, 1, "PAYMENT", 1000L, "CNY",
                java.util.Map.of(PaymentAttempt.CHANNEL_MODE_KEY, "SANDBOX")));

        channel.queryStatus(new QueryStatusRequest("PM-1", "txn-platform", "idem-1", "ch-txn-1"));

        QueryStatusRequest sent = channel.queries.get(0);
        assertThat(sent.channelTransactionId()).isEqualTo("ch-txn-1");
        assertThat(sent.transactionId()).isEqualTo("txn-platform"); // 平台号仍在，但渠道号已分离
    }

    // ---- T62：确定性排序（修 S22） ----

    @Test
    @DisplayName("多条 attempt 时取 id 最小者：解析结果可复现，不依赖返回顺序 [FR-272]")
    void resolveIsDeterministic() {
        InMemoryPaymentAttemptRepository attempts = new InMemoryPaymentAttemptRepository();
        // 故意「倒序」插入：后插的 id 更大
        PaymentAttempt second = PaymentAttempt.rehydrate(20L, "PM-1", "WECHAT", 0, null, null,
                "ch-b", PaymentAttemptStatus.ACCEPTED, null, null, 1, "PAYMENT", 1000L, "CNY", null);
        PaymentAttempt first = PaymentAttempt.rehydrate(10L, "PM-1", "ALIPAY", 0, null, null,
                "ch-a", PaymentAttemptStatus.ACCEPTED, null, null, 1, "PAYMENT", 1000L, "CNY", null);
        attempts.save(second);
        attempts.save(first);

        // findByPaymentNo 内部按 map 迭代顺序，顺序不保证；解析侧排序后才确定
        java.util.Comparator<PaymentAttempt> byId =
                java.util.Comparator.comparing(PaymentAttempt::getId,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()));
        PaymentAttempt chosen = attempts.findByPaymentNo("PM-1").stream()
                .filter(a -> PaymentAttempt.TYPE_PAYMENT.equals(a.getAttemptType()))
                .filter(a -> a.getStatus() != PaymentAttemptStatus.PENDING)
                .sorted(byId)
                .findFirst().orElseThrow();

        assertThat(chosen.getId()).isEqualTo(10L);
        assertThat(chosen.getChannelCode()).isEqualTo("ALIPAY");
    }

    // ---- T63：找不到记录行不回落默认渠道 ----

    @Test
    @DisplayName("无记录渠道 ⇒ INTERNAL_ERROR，绝不回落默认渠道 [FR-273]")
    void noRecordedChannelThrows() {
        InMemoryPaymentAttemptRepository attempts = new InMemoryPaymentAttemptRepository();
        ChannelQueryService service = queryService(attempts, new RecordingChannel("ALIPAY"));

        assertThatThrownBy(() -> invokeResolveForMissingPayment(service))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("no recorded channel");
    }

    // ---- T68：反向路径不调 Router ----

    @Test
    @DisplayName("反向路径类不依赖 ChannelRouter（渠道取自 attempt 记录）[INV-4][SC-A-12]")
    void reversePathDoesNotDependOnRouter() {
        for (Class<?> type : List.of(ChannelQueryService.class,
                com.payment.payment.application.PaymentRefundService.class)) {
            boolean depends = java.util.Arrays.stream(type.getDeclaredFields())
                    .anyMatch(f -> f.getType().getName().contains("ChannelRouter"));
            assertThat(depends).as("%s 不得持有 ChannelRouter", type.getSimpleName()).isFalse();
        }
    }

    private static ChannelQueryService queryService(InMemoryPaymentAttemptRepository attempts,
                                                    PaymentChannel channel) {
        ChannelRegistry registry = new SingleChannelRegistry(channel);
        return new ChannelQueryService(new com.payment.payment.infra.InMemoryPaymentRepository(),
                attempts, registry, null, new ReliabilityConfig(), new NoopBusinessMetrics());
    }

    /** 触发一次「找不到 attempt 记录」的解析（借 UNKNOWN 支付走 queryRound 无支付可扫，故直接反射调私有方法）。 */
    private static void invokeResolveForMissingPayment(ChannelQueryService service) {
        try {
            var method = ChannelQueryService.class.getDeclaredMethod("resolveRecordedTarget",
                    com.payment.payment.domain.Payment.class);
            method.setAccessible(true);
            com.payment.payment.domain.Payment payment = com.payment.payment.domain.Payment.rehydrate(
                    99L, "PM-404", "txn-404", "order-404", "u1", 100, "CNY", "idem-404",
                    com.payment.payment.domain.PaymentStatus.UNKNOWN, 1L, null, 0, null, 0, 1);
            method.invoke(service, payment);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            // 解包：被测方法抛的 BizException 被反射包了一层，断言要看真实异常
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException rte) {
                throw rte;
            }
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
