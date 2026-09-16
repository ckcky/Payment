package com.payment.payment.infra.persistence;

import com.payment.payment.application.channel.ChannelAttemptRecorder;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptRepository;
import org.springframework.stereotype.Component;

/**
 * 渠道层写入口实现（Feature 028 / FR-002，ADR-0072）：
 * 本类是 {@code payment_attempts} 表写操作的<b>唯一</b>入口，持 {@link PaymentAttemptRepository}。
 *
 * <p><b>落点说明（plan §实现期待确认项 2）</b>：放在 {@code infra/persistence}（近仓储），与
 * {@code PaymentAttemptRepository} 同层——{@code infra → infra} 依赖，不违反 INV-4。
 * 若团队后续倾向「渠道层自治」，可平移到 {@code infra/channel}，两种都不违反 INV-4。</p>
 *
 * <p><b>不做自己的事务</b>：本类方法运行在调用方（{@code PaymentPersistence}）开启的同一本地事务内
 * （INV-5 边界澄清：分层 ≠ 拆事务）。</p>
 */
@Component
public class ChannelAttemptRecorderImpl implements ChannelAttemptRecorder {

    private final PaymentAttemptRepository attemptRepository;

    public ChannelAttemptRecorderImpl(PaymentAttemptRepository attemptRepository) {
        this.attemptRepository = attemptRepository;
    }

    @Override
    public PaymentAttempt openPaymentAttempt(String paymentNo, String channelCode,
                                             long amountMinor, String currencyCode) {
        return attemptRepository.save(
                new PaymentAttempt(paymentNo, channelCode, 0, amountMinor, currencyCode));
    }

    @Override
    public PaymentAttempt openRefundAttempt(String paymentNo, String channelCode,
                                            long amountMinor, String currencyCode) {
        return attemptRepository.save(
                PaymentAttempt.refundAttempt(paymentNo, channelCode, amountMinor, currencyCode));
    }

    /**
     * 把权威渠道结果收敛到 attempt 状态机。
     *
     * <p><b>顺序与旧 {@code PaymentResultApplier} 逐字一致</b>（plan §B4 风险点）：
     * 先 {@code accept}（回填渠道引用）→ 再 {@code succeed}/{@code fail}/{@code markUnknown}。
     * 顺序不能变，否则乐观锁版本号与幂等重放断言会变。</p>
     */
    @Override
    public boolean converge(PaymentAttempt attempt, ChannelResult result) {
        // 错误分类由双响应码派生后落库，供观测排障（ADR-0012）；不参与重试判定。
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
        return attemptRepository.save(attempt);
    }

    @Override
    public PaymentAttempt require(Long attemptId) {
        return attemptRepository.findById(attemptId)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "attempt not found: " + attemptId));
    }

    @Override
    public java.util.Optional<PaymentAttempt> findPaymentAttempt(String paymentNo) {
        return attemptRepository.findByPaymentNo(paymentNo).stream()
                .filter(a -> PaymentAttempt.TYPE_PAYMENT.equals(a.getAttemptType()))
                .findFirst();
    }

    @Override
    public PaymentAttempt save(PaymentAttempt attempt) {
        return attemptRepository.save(attempt);
    }
}
