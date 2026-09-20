package com.payment.payment.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.payment.application.channel.ChannelAttemptRecorder;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptRepository;
import java.util.Optional;

/**
 * 把 {@link PaymentAttemptRepository} 适配为渠道层写入口 {@link ChannelAttemptRecorder}
 * （Feature 028 / FR-036 兼容垫片）。
 *
 * <p><b>为什么需要适配器</b>：Feature 028 把 {@code payment_attempts} 的写入口从仓储提升为
 * 「渠道层端口」，但既有约 10 个测试是按「传仓储」的签名写的。若测试内存实现
 * （{@code InMemoryPaymentAttemptRepository}）本身已实现该端口，适配零成本直传；
 * 否则用本适配器包一层——两种情况下既有测试都<b>零改动</b>（SC-012）。</p>
 *
 * <p>生产路径<b>不走本适配器</b>：那里是 {@code ChannelAttemptRecorderImpl}（持
 * {@code MybatisPaymentAttemptRepository}）显式装配，职责分离清晰。</p>
 */
public final class ChannelAttemptRecorders {

    private ChannelAttemptRecorders() {
    }

    /** 取得写入口：实现已具备则直传，否则包一层适配。 */
    public static ChannelAttemptRecorder of(PaymentAttemptRepository repository) {
        if (repository instanceof ChannelAttemptRecorder recorder) {
            return recorder;
        }
        return new RepositoryBackedRecorder(repository);
    }

    /** 仓储支撑的写入口适配：行为与 {@code ChannelAttemptRecorderImpl} 逐字一致。 */
    private static final class RepositoryBackedRecorder implements ChannelAttemptRecorder {

        private final PaymentAttemptRepository repository;

        private RepositoryBackedRecorder(PaymentAttemptRepository repository) {
            this.repository = repository;
        }

        @Override
        public PaymentAttempt openPaymentAttempt(String paymentNo, String channelCode,
                                                 long amountMinor, String currencyCode) {
            // FIX-3：与生产同口径的 Payment 1:1 PaymentAttempt 写侧断言
            requireNoExistingPaymentAttempt(paymentNo);
            return repository.save(new PaymentAttempt(paymentNo, channelCode, 0, amountMinor, currencyCode));
        }

        @Override
        public PaymentAttempt openRefundAttempt(String paymentNo, String channelCode,
                                                long amountMinor, String currencyCode) {
            return repository.save(PaymentAttempt.refundAttempt(paymentNo, channelCode, amountMinor, currencyCode));
        }

        /**
         * 退款尝试「创建 + 收敛 + 落库」（FIX-4），与 {@code ChannelAttemptRecorderImpl} 同流程。
         *
         * <p>本垫片只被「既有测试直接传仓储」的兼容构造使用；若仓储本身已实现端口
         * （{@code InMemoryPaymentAttemptRepository}），{@link #of} 会直传、走不到这里。</p>
         */
        @Override
        public PaymentAttempt recordRefundAttempt(String paymentNo, String channelCode,
                                                  long amountMinor, String currencyCode, ChannelResult result) {
            PaymentAttempt attempt = PaymentAttempt.refundAttempt(paymentNo, channelCode, amountMinor, currencyCode);
            converge(attempt, result);
            try {
                return repository.save(attempt);
            } catch (org.springframework.dao.DuplicateKeyException ex) {
                return ChannelAttemptRecorder.requireTrueRefundReplay(repository, paymentNo,
                        result.channelReference());
            }
        }

        @Override
        public boolean converge(PaymentAttempt attempt, ChannelResult result) {
            attempt.setErrorType(result.errorType());
            return switch (result.status()) {
                case SUCCESS -> {
                    if (result.channelReference() != null) {
                        attempt.accept(result.channelReference());
                    }
                    yield attempt.succeed();
                }
                case FAILURE -> {
                    if (result.channelReference() != null) {
                        attempt.accept(result.channelReference());
                    }
                    yield attempt.fail(result.reason());
                }
                case UNKNOWN -> attempt.markUnknown(result.reason());
            };
        }

        @Override
        public PaymentAttempt markUnknown(PaymentAttempt attempt, String reason) {
            attempt.markUnknown(reason);
            return repository.save(attempt);
        }

        @Override
        public PaymentAttempt require(Long attemptId) {
            return repository.findById(attemptId)
                    .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "attempt not found: " + attemptId));
        }

        @Override
        public Optional<PaymentAttempt> findPaymentAttempt(String paymentNo) {
            return repository.findByPaymentNo(paymentNo).stream()
                    .filter(a -> PaymentAttempt.TYPE_PAYMENT.equals(a.getAttemptType()))
                    .findFirst();
        }

        @Override
        public PaymentAttempt save(PaymentAttempt attempt) {
            return repository.save(attempt);
        }
    }
}
