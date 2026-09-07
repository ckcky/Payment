package com.payment.order.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.id.BusinessNoType;
import com.payment.common.core.id.BusinessNos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 订单聚合：用户买什么、向谁买、金额与购买生命周期。
 *
 * <p>金额不变量：订单总额 = 明细小计之和；已支付金额 &le; 总额；已退款金额 &le; 已支付金额。
 * 金额一律最小货币单位（long），溢出用 {@link Math#addExact} 拒绝。</p>
 */
public class Order {

    private Long id;
    /** 业务单号（OR + 雪花，ADR-0062）：对外展示与客服检索用，内部引用仍用 Long id。 */
    private String orderNo;
    /** 乐观锁并发令牌：由仓储读写，保护并发状态迁移不被覆盖。 */
    private Integer version;
    private final String userId;
    private final String merchantId;
    /** 下游支付单号（payment-service 的 payment.id），下单时同步 RPC 返回、支付成功回调确认。 */
    private String paymentNo;
    private OrderStatus status = OrderStatus.PENDING_CONFIRMATION;
    private final String currencyCode;
    private final long totalMinor;
    private long paidMinor = 0L;
    private long refundedMinor = 0L;
    private final List<OrderItem> items;

    public Order(String userId, String merchantId, String currencyCode, List<OrderItem> items) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId");
        this.currencyCode = Objects.requireNonNull(currencyCode, "currencyCode");
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("order must have at least one item");
        }
        long total = 0L;
        for (OrderItem item : items) {
            if (!currencyCode.equals(item.getCurrencyCode())) {
                throw new IllegalArgumentException("item currency mismatch: " + item.getCurrencyCode());
            }
            total = Math.addExact(total, item.subtotalMinor());
        }
        this.totalMinor = total;
        this.orderNo = BusinessNos.of(BusinessNoType.ORDER);
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
    }

    /**
     * 持久化重建：用既有快照明细与历史状态/金额还原聚合，绕过创建期状态机（不改变业务规则）。
     */
    public static Order rehydrate(Long id, String orderNo, String userId, String merchantId, String paymentNo,
                                  OrderStatus status, String currencyCode, List<OrderItem> items,
                                  long paidMinor, long refundedMinor, Integer version) {
        Order order = new Order(userId, merchantId, currencyCode, items);
        order.id = id;
        order.orderNo = orderNo;
        order.paymentNo = paymentNo;
        order.status = status;
        order.paidMinor = paidMinor;
        order.refundedMinor = refundedMinor;
        order.version = version;
        return order;
    }

    // ---- 状态机（唯一的状态变更入口）----

    public void confirm() {
        requireStatus(OrderStatus.PENDING_CONFIRMATION, "confirm");
        this.status = OrderStatus.PENDING_PAYMENT;
    }

    /** 关联下游支付单（跨系统引用一律用业务单号 paymentNo，ADR-0063），不改变订单状态。 */
    public void recordPayment(String paymentNo) {
        this.paymentNo = Objects.requireNonNull(paymentNo, "paymentNo");
    }

    /** 支付成功：PENDING_PAYMENT → PAID，记录下游支付单号并把已支付金额置为订单总额（不支持部分支付）。 */
    public boolean markPaid(String paymentNo) {
        if (this.status == OrderStatus.PAID) {
            return false; // 幂等重复回调，吸收
        }
        requireStatus(OrderStatus.PENDING_PAYMENT, "markPaid");
        this.paymentNo = Objects.requireNonNull(paymentNo, "paymentNo");
        this.paidMinor = this.totalMinor;
        this.status = OrderStatus.PAID;
        return true;
    }

    public void markFulfilling() {
        requireStatus(OrderStatus.PAID, "markFulfilling");
        this.status = OrderStatus.FULFILLING;
    }

    public void complete() {
        requireStatus(OrderStatus.FULFILLING, "complete");
        this.status = OrderStatus.COMPLETED;
    }

    public void cancel() {
        requireAnyStatus("cancel", OrderStatus.PENDING_CONFIRMATION, OrderStatus.PENDING_PAYMENT);
        this.status = OrderStatus.CANCELLED;
    }

    public void close() {
        requireAnyStatus("close", OrderStatus.COMPLETED, OrderStatus.CANCELLED);
        this.status = OrderStatus.CLOSED;
    }

    /** 记录退款金额；累计退款不得超过已支付金额。 */
    public void recordRefund(long amountMinor) {
        if (amountMinor <= 0) {
            throw BizException.of(ErrorCodes.AMOUNT_INVARIANT_VIOLATION, "refund amount must be > 0");
        }
        long newRefunded = Math.addExact(this.refundedMinor, amountMinor);
        if (newRefunded > paidMinor) {
            throw BizException.of(ErrorCodes.AMOUNT_INVARIANT_VIOLATION, "refund exceeds paid amount");
        }
        this.refundedMinor = newRefunded;
    }

    /**
     * 退款收口（spec 019 / ADR-0067）：二次校验超退 + 金额累加 + 状态推进。
     * 可退前置状态：PAID / FULFILLING / COMPLETED / PARTIALLY_REFUNDED；
     * 退满（refunded ≥ paid）→ REFUNDED，否则 PARTIALLY_REFUNDED。
     */
    public void applyRefund(long amountMinor) {
        requireAnyStatus("refund", OrderStatus.PAID, OrderStatus.FULFILLING,
                OrderStatus.COMPLETED, OrderStatus.PARTIALLY_REFUNDED);
        recordRefund(amountMinor);
        this.status = (refundedMinor >= paidMinor) ? OrderStatus.REFUNDED : OrderStatus.PARTIALLY_REFUNDED;
    }

    /** 可退款金额 = 已支付 - 已退款。 */
    public long getRefundableMinor() {
        return Math.subtractExact(paidMinor, refundedMinor);
    }

    private void requireStatus(OrderStatus expected, String op) {
        if (this.status != expected) {
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "illegal " + op + " from " + this.status + " (expected " + expected + ")");
        }
    }

    private void requireAnyStatus(String op, OrderStatus... allowed) {
        for (OrderStatus s : allowed) {
            if (this.status == s) {
                return;
            }
        }
        throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION, "illegal " + op + " from " + this.status);
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public Long getId() {
        return id;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getUserId() {
        return userId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getPaymentNo() {
        return paymentNo;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public long getTotalMinor() {
        return totalMinor;
    }

    public long getPaidMinor() {
        return paidMinor;
    }

    public long getRefundedMinor() {
        return refundedMinor;
    }

    public List<OrderItem> getItems() {
        return items;
    }
}
