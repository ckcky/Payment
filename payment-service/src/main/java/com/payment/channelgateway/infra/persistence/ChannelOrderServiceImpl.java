package com.payment.channelgateway.infra.persistence;

import com.payment.channelgateway.application.ChannelOrderService;
import com.payment.channelgateway.application.ChannelResult;
import com.payment.common.core.dye.DyeContext;
import com.payment.common.core.dye.DyeMode;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.channelgateway.domain.ChannelOrder;
import com.payment.channelgateway.domain.ChannelOrderRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 渠道单服务实现（spec 041）：{@code channel_orders} 表的写操作<b>唯一</b>入口。
 *
 * <h3>落位</h3>
 * <p>{@code infra/persistence}——与 {@link ChannelOrderRepository} 同层，靠它做增删改查；
 * 实现 {@code application} 层的 {@link ChannelOrderService} 端口（{@code infra → application}，
 * 不违反 INV-4）。</p>
 *
 * <h3>事务（spec 041 / D2：与 payment 侧拆分）</h3>
 * <p>本类的每个写方法<b>自带事务</b>：渠道单的落库独立于 payment 单的落库提交。
 * 改造前这些方法跑在 {@code PaymentPersistence} 的事务里（INV-5「分层 ≠ 拆事务」），
 * 代价是 payment 侧必须知道渠道单的存在——{@code insertPending} 要替渠道层开单、
 * {@code applyAndPersist} 要替渠道层 save。spec 041 把这份耦合断掉，
 * 换来渠道域自治，代价是两侧终态可能瞬时不一致 ⇒ 由主动查询 / 超时扫描 / 对账收敛。</p>
 */
@Service
public class ChannelOrderServiceImpl implements ChannelOrderService {

    private final ChannelOrderRepository orderRepository;

    public ChannelOrderServiceImpl(ChannelOrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * 开渠道单并<b>落本次的渠道模态</b>（spec 030 / FR-151 / FR-303）。
     *
     * <p>模态取入站染色 {@link DyeContext}（未染色 ⇒ {@link DyeMode#MOCK}），写进
     * {@code extra} 的 {@value ChannelOrder#CHANNEL_MODE_KEY} 键，随行落 {@code extra_json}。
     * 这里是<b>唯一写入点</b>：反向路径（查询 / 退款 / 超时扫描）没有入站请求，ThreadLocal 为空，
     * 只能靠这一刻落下的值还原模态。</p>
     */
    @Override
    @Transactional
    public ChannelOrder openChannelOrder(String paymentNo, String channelCode,
                                         long amountMinor, String currencyCode) {
        // FIX-3：Payment 1:1 ChannelOrder 的写侧断言（口径在端口默认方法上，各实现共享）
        requireNoExistingChannelOrder(paymentNo);
        ChannelOrder order = new ChannelOrder(paymentNo, channelCode, 0, amountMinor, currencyCode);
        stampChannelMode(order);
        return orderRepository.save(order);
    }

    @Override
    @Transactional
    public ChannelOrder openRefundAttempt(String paymentNo, String channelCode,
                                          long amountMinor, String currencyCode) {
        ChannelOrder order = ChannelOrder.refundAttempt(paymentNo, channelCode, amountMinor, currencyCode);
        stampChannelMode(order);
        return orderRepository.save(order);
    }

    /**
     * 退款渠道单的「创建 + 收敛 + 落库」（FIX-4）：整体在渠道层完成，payment 层不再自己 new / save。
     *
     * <p>收敛在<b>落库之前</b>完成，因此是一次带终态的 INSERT（不是「先插 PENDING 再 UPDATE」）。</p>
     *
     * <p>撞 {@code uk_attempts_channel_reference} 时按 {@link #requireTrueRefundReplay} 判定：
     * 只有真幂等重放才吸收，引用值写错则抛错——不再无条件吸收（F5 的伪装来源）。</p>
     */
    @Override
    @Transactional
    public ChannelOrder recordRefundAttempt(String paymentNo, String channelCode,
                                            long amountMinor, String currencyCode, ChannelResult result) {
        ChannelOrder order = ChannelOrder.refundAttempt(paymentNo, channelCode, amountMinor, currencyCode);
        stampChannelMode(order);
        converge(order, result);
        try {
            return orderRepository.save(order);
        } catch (DuplicateKeyException ex) {
            return ChannelOrderService.requireTrueRefundReplay(
                    orderRepository, paymentNo, result.channelReference());
        }
    }

    /**
     * 把当前染色模态盖进渠道单的 {@code extra}（FR-151）。
     *
     * <p>{@code DyeContext.current() == null}（未染色）⇒ 写 {@code MOCK} 而非留空——
     * 新写入行 <b>MUST NOT 缺失该键</b>（FR-303）。</p>
     */
    private void stampChannelMode(ChannelOrder order) {
        DyeMode mode = DyeContext.current();
        order.putExtra(ChannelOrder.CHANNEL_MODE_KEY, (mode == null ? DyeMode.MOCK : mode).name());
    }

    /**
     * 把权威渠道结果收敛到渠道单状态机。
     *
     * <p><b>顺序不可变</b>：先 {@code accept}（回填渠道引用）→ 再 {@code succeed}/{@code fail}/
     * {@code markUnknown}。顺序变了乐观锁版本号与幂等重放断言就会变。</p>
     *
     * <p>本方法<b>只改内存</b>（无 DB 操作），落库由 {@link #save} 完成——调用方据此把
     * 「收敛」与「落库」编排在同一个渠道事务内。</p>
     */
    @Override
    public boolean converge(ChannelOrder order, ChannelResult result) {
        order.setErrorType(result.errorType());
        return switch (result.status()) {
            case SUCCESS -> {
                if (result.channelReference() != null) {
                    order.accept(result.channelReference());
                }
                yield order.succeed();
            }
            case FAILURE -> {
                if (result.channelReference() != null) {
                    order.accept(result.channelReference());
                }
                yield order.fail(result.reason());
            }
            case UNKNOWN -> {
                // spec 030 / B4（T97 / FR-205）：补渠道引用回填。受理阶段渠道常还没给交易号
                // （channel_reference 落 NULL），后来的一次 UNKNOWN 通知/查询响应里才带上。
                order.backfillChannelReference(result.channelReference());
                yield order.markUnknown(result.reason());
            }
        };
    }

    @Override
    @Transactional
    public ChannelOrder converge(Long orderId, ChannelResult result, int retries) {
        ChannelOrder order = require(orderId);
        for (int i = 0; i < retries; i++) {
            order.recordRetry();
        }
        converge(order, result);
        return orderRepository.save(order);
    }

    @Override
    @Transactional
    public ChannelOrder markUnknown(ChannelOrder order, String reason) {
        order.markUnknown(reason);
        return orderRepository.save(order);
    }

    @Override
    public ChannelOrder require(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "channel order not found: " + orderId));
    }

    @Override
    public java.util.Optional<ChannelOrder> findChannelOrder(String paymentNo) {
        return orderRepository.findByPaymentNo(paymentNo).stream()
                .filter(o -> ChannelOrder.TYPE_PAYMENT.equals(o.getAttemptType()))
                .findFirst();
    }

    @Override
    @Transactional
    public ChannelOrder save(ChannelOrder order) {
        return orderRepository.save(order);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<ChannelOrder> findByChannelNo(String channelNo) {
        return orderRepository.findByChannelNo(channelNo);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<ChannelOrder> findByPaymentNo(String paymentNo) {
        return orderRepository.findByPaymentNo(paymentNo);
    }
}
