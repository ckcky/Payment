package com.payment.payment.application;

import com.payment.payment.application.channel.ChannelResult;
import com.payment.payment.domain.PaymentAttempt;
import com.payment.payment.domain.PaymentAttemptRepository;
import com.payment.payment.domain.PaymentAttemptStatus;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 退款尝试收敛服务（fix：与 {@link com.payment.refund.application.RefundResultProcessor} 三路收敛同源）：
 * 退款取得权威终态（同步终态 / 渠道回调 / resolve）时，把对应 REFUND 尝试行
 * （{@code payment_attempts.attempt_type=REFUND}）从 UNKNOWN/ACCEPTED/PENDING 收敛到
 * SUCCEEDED/FAILED——异步受理（UNKNOWN 落库）不再永久滞留。
 *
 * <p>刻意只依赖 {@link PaymentAttemptRepository}（不碰渠道 {@code PaymentChannel}）：
 * 若经 {@link PaymentRefundService} 走渠道 bean，会形成
 * mockChannelAdapter → 回调桥 → RefundResultProcessor → 本收敛 → 渠道 的循环依赖。</p>
 *
 * <p>匹配规则：优先按 {@code channelReference} 精确匹配（受理与推送共用同一渠道流水号）；
 * 引用为空（resolve 路径）回退该支付单<b>最近一条未收敛</b>（UNKNOWN/ACCEPTED）的退款尝试。
 * 精确引用存在但无匹配行时不臆测归属（只 WARN）。终态吸收/重复收敛天然幂等。</p>
 */
@Service
public class RefundAttemptSettlementService {

    private static final Logger log = LoggerFactory.getLogger(RefundAttemptSettlementService.class);

    private final PaymentAttemptRepository attemptRepository;

    public RefundAttemptSettlementService(PaymentAttemptRepository attemptRepository) {
        this.attemptRepository = attemptRepository;
    }

    /**
     * 将该支付单的退款尝试收敛到终态。
     *
     * @param paymentNo        所属支付单号（尝试行的归属锚点）
     * @param channelReference 渠道退款流水号（优先精确匹配；resolve 路径可能为 null，回退最近一条未收敛尝试）
     * @param outcome          渠道权威结果（仅 SUCCESS/FAILURE 有效，UNKNOWN 不收敛）
     */
    public void convergeRefundAttempt(String paymentNo, String channelReference, ChannelResult outcome) {
        if (outcome.status() == ChannelResult.Status.UNKNOWN) {
            return; // 未取得权威结论，尝试行保持未收敛态
        }
        List<PaymentAttempt> refundAttempts = attemptRepository.findByPaymentNo(paymentNo).stream()
                .filter(a -> PaymentAttempt.TYPE_REFUND.equals(a.getAttemptType()))
                .toList();
        PaymentAttempt target;
        if (channelReference != null) {
            target = refundAttempts.stream()
                    .filter(a -> channelReference.equals(a.getChannelReference()))
                    .findFirst()
                    .orElse(null);
            if (target == null) {
                log.warn("退款尝试收敛未找到匹配行（不臆测归属）paymentNo={} channelRef={} targetStatus={}",
                        paymentNo, channelReference, outcome.status());
                return;
            }
        } else {
            // resolve 路径无渠道引用：取最近一条未收敛的退款尝试（按主键 id 最大 = 最后插入）
            target = refundAttempts.stream()
                    .filter(a -> a.getStatus() == PaymentAttemptStatus.UNKNOWN
                            || a.getStatus() == PaymentAttemptStatus.ACCEPTED)
                    .max(Comparator.comparing(PaymentAttempt::getId))
                    .orElse(null);
            if (target == null) {
                log.debug("退款尝试收敛：无可收敛的未终态尝试行 paymentNo={}", paymentNo);
                return;
            }
        }
        boolean changed = switch (outcome.status()) {
            case SUCCESS -> target.succeed();
            case FAILURE -> target.fail(outcome.reason() == null ? "channel refund failed" : outcome.reason());
            default -> false;
        };
        if (changed) {
            attemptRepository.save(target);
            log.info("退款尝试已收敛 attemptId={} paymentNo={} channelRef={} -> {}",
                    target.getId(), paymentNo, target.getChannelReference(), target.getStatus());
        } else {
            log.debug("退款尝试收敛被吸收（终态/重复）attemptId={} status={}", target.getId(), target.getStatus());
        }
    }
}
