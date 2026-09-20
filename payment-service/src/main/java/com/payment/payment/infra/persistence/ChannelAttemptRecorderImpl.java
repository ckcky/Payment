package com.payment.payment.infra.persistence;

import com.payment.payment.application.channel.ChannelAttemptRecorder;
import com.payment.payment.application.channel.ChannelResult;
import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.dye.DyeMode;
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

    /**
     * 开支付尝试并<b>落本次的渠道模态</b>（spec 030 / FR-151 / FR-303）。
     *
     * <p>模态取入站染色 {@link DyeContext}（未染色 ⇒ {@link DyeMode#MOCK}），写进
     * {@code extra} 的 {@value PaymentAttempt#CHANNEL_MODE_KEY} 键，随行落 {@code extra_json}。
     * 这里是<b>唯一写入点</b>：反向路径（查询 / 退款 / 超时扫描）没有入站请求，ThreadLocal 为空，
     * 只能靠这一刻落下的值还原模态。</p>
     *
     * <p><b>为什么必须在开尝试时写</b>：attempt 行是「这次渠道交互」的事实载体，
     * 模态是这次交互的属性；等反向路径要用时再补就补不回来了。</p>
     */
    @Override
    public PaymentAttempt openPaymentAttempt(String paymentNo, String channelCode,
                                             long amountMinor, String currencyCode) {
        PaymentAttempt attempt = new PaymentAttempt(paymentNo, channelCode, 0, amountMinor, currencyCode);
        stampChannelMode(attempt);
        return attemptRepository.save(attempt);
    }

    @Override
    public PaymentAttempt openRefundAttempt(String paymentNo, String channelCode,
                                            long amountMinor, String currencyCode) {
        PaymentAttempt attempt = PaymentAttempt.refundAttempt(paymentNo, channelCode, amountMinor, currencyCode);
        stampChannelMode(attempt);
        return attemptRepository.save(attempt);
    }

    /**
     * 把当前染色模态盖进 attempt 的 {@code extra}（FR-151）。
     *
     * <p>{@code DyeContext.current() == null}（未染色）⇒ 写 {@code MOCK} 而非留空——
     * 新写入行 <b>MUST NOT 缺失该键</b>（FR-303）：留空会让「未染色」与「读不出来」
     * 在读取侧变得无法区分，两类完全不同的事实被压成同一个结果。</p>
     */
    private void stampChannelMode(PaymentAttempt attempt) {
        DyeMode mode = DyeContext.current();
        attempt.putExtra(PaymentAttempt.CHANNEL_MODE_KEY, (mode == null ? DyeMode.MOCK : mode).name());
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
            case UNKNOWN -> {
                // spec 030 / B4（T97 / FR-205）：**补渠道引用回填**。
                // 受理阶段渠道常常还没给交易号（channel_reference 落 NULL），
                // 后来的一次 UNKNOWN 通知/查询响应里才带上——不回填就等于把这个
                // 观测链断了：后续主动查询拿通道号去问渠道会查不到。
                // backfillChannelReference 自带守卫（仅在引用为空、且行在途时生效，
                // 终态行不动），故这里无需额外判断。
                attempt.backfillChannelReference(result.channelReference());
                yield attempt.markUnknown(result.reason());
            }
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
