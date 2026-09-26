package com.payment.channelgateway.infra.persistence;

import com.payment.channelgateway.application.ChannelOrderService;
import com.payment.channelgateway.application.ChannelResult;
import com.payment.channelgateway.domain.ChannelOrder;
import com.payment.channelgateway.domain.ChannelOrderRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存支付尝试仓储：仅用于领域/编排单测（不走 Spring 注入），生产由 {@code MybatisChannelOrderRepository} 承接。
 *
 * <p><b>Feature 028 / FR-036 兼容垫片</b>：本类同时实现渠道层写入口 {@link ChannelOrderService}
 * ——测试场景下「仓储」与「写入口」是同一个内存对象，实现两个接口让既有测试
 * {@code new PaymentPersistence(payments, attempts)} 这类调用<b>零改动</b>（SC-012）。</p>
 *
 * <p>生产路径两者是分开的：{@code MybatisChannelOrderRepository}（仓储）
 * 与 {@code ChannelOrderServiceImpl}（写入口，持仓储）。</p>
 */
public class InMemoryChannelOrderRepository implements ChannelOrderRepository, ChannelOrderService {

    private final Map<Long, ChannelOrder> byId = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong();

    @Override
    public Optional<ChannelOrder> findById(Long id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<ChannelOrder> findByChannelNo(String channelNo) {
        if (channelNo == null || channelNo.isBlank()) {
            return Optional.empty();
        }
        return byId.values().stream()
                .filter(a -> channelNo.equals(a.getChannelNo()))
                .findFirst();
    }

    @Override
    public List<ChannelOrder> findByPaymentNo(String paymentNo) {
        return byId.values().stream()
                .filter(a -> a.getPaymentNo().equals(paymentNo))
                .toList();
    }

    @Override
    public ChannelOrder save(ChannelOrder attempt) {
        if (attempt.getId() == null) {
            attempt.setId(idGen.incrementAndGet());
        }
        byId.put(attempt.getId(), attempt);
        return attempt;
    }

    // ---- ChannelOrderService（Feature 028 / FR-002） ----

    /**
     * 与 {@code ChannelOrderServiceImpl} <b>同口径</b>（spec 030 / FR-151）：开尝试时同样
     * 盖入当前染色模态。测试桩<b>MUST</b>与生产一致——否则「单测过了、生产没写模态」
     * 这类偏差要等到真连沙箱才暴露。
     */
    @Override
    public ChannelOrder openChannelOrder(String paymentNo, String channelCode,
                                             long amountMinor, String currencyCode) {
        // FIX-3：与生产同口径的 Payment 1:1 ChannelOrder 写侧断言——测试桩 MUST 与生产一致
        requireNoExistingChannelOrder(paymentNo);
        ChannelOrder attempt = new ChannelOrder(paymentNo, channelCode, 0, amountMinor, currencyCode);
        stampChannelMode(attempt);
        return save(attempt);
    }

    @Override
    public ChannelOrder openRefundAttempt(String paymentNo, String channelCode,
                                            long amountMinor, String currencyCode) {
        ChannelOrder attempt = ChannelOrder.refundAttempt(paymentNo, channelCode, amountMinor, currencyCode);
        stampChannelMode(attempt);
        return save(attempt);
    }

    /**
     * 退款尝试「创建 + 收敛 + 落库」（FIX-4），与 {@code ChannelOrderServiceImpl} <b>同口径</b>。
     *
     * <p>差异只在一处：内存实现没有唯一约束，因此不存在 {@code DuplicateKeyException} 分支
     * （生产侧那条分支用 {@code requireTrueRefundReplay} 区分「真重放」与「引用值写错」）。
     * 除该分支外，写入次数、盖章时机、收敛顺序与生产逐字一致——测试桩与生产口径漂移过的坑
     * 已经踩过一次（模态落库），这里保持一致。</p>
     */
    @Override
    public ChannelOrder recordRefundAttempt(String paymentNo, String channelCode,
                                              long amountMinor, String currencyCode, ChannelResult result) {
        ChannelOrder attempt = ChannelOrder.refundAttempt(paymentNo, channelCode, amountMinor, currencyCode);
        stampChannelMode(attempt);
        converge(attempt, result);
        return save(attempt);
    }

    private void stampChannelMode(ChannelOrder attempt) {
        com.payment.common.core.dye.DyeMode mode = com.payment.common.core.dye.DyeContext.current();
        attempt.putExtra(ChannelOrder.CHANNEL_MODE_KEY,
                (mode == null ? com.payment.common.core.dye.DyeMode.MOCK : mode).name());
    }

    /** 收敛 attempt（与 {@code ChannelOrderServiceImpl} 口径一致：先 accept 后终态）。 */
    @Override
    public boolean converge(ChannelOrder attempt, ChannelResult result) {
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
                // spec 030 / B4（T97）：与 ChannelOrderServiceImpl 同口径——UNKNOWN
                // 分支补渠道引用回填（守卫在 backfillChannelReference 内）。测试桩口径必须与
                // 生产一致，否则单测覆盖不到回填行为。
                attempt.backfillChannelReference(result.channelReference());
                yield attempt.markUnknown(result.reason());
            }
        };
    }

    @Override
    public ChannelOrder converge(Long orderId, ChannelResult result, int retries) {
        ChannelOrder order = require(orderId);
        for (int i = 0; i < retries; i++) {
            order.recordRetry();
        }
        converge(order, result);
        return save(order);
    }

    @Override
    public ChannelOrder markUnknown(ChannelOrder attempt, String reason) {
        attempt.markUnknown(reason);
        return save(attempt);
    }

    @Override
    public ChannelOrder require(Long attemptId) {
        return findById(attemptId).orElseThrow(
                () -> com.payment.common.core.error.BizException.of(
                        com.payment.common.core.error.ErrorCodes.NOT_FOUND,
                        "attempt not found: " + attemptId));
    }

    @Override
    public Optional<ChannelOrder> findChannelOrder(String paymentNo) {
        return findByPaymentNo(paymentNo).stream()
                .filter(a -> ChannelOrder.TYPE_PAYMENT.equals(a.getAttemptType()))
                .findFirst();
    }
    // 注：findByChannelNo / findByPaymentNo 由本类的仓储实现直接满足
    // ChannelOrderService 的同签名方法（同签名多接口，一个实现即可）。
}
