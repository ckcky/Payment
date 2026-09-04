package com.payment.payment.infra.client;

import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.common.core.error.ErrorCodes;
import com.payment.payment.application.OrderGateway;
import com.payment.payment.application.OrderNotPayableException;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * order-service 回写网关的 Feign 适配层（Feature 015 / C5）：
 * 把订单侧「409 ORDER_NOT_PAYABLE」从通用 FeignException 翻译为应用层
 * {@link OrderNotPayableException}，供 {@code PaymentResultProcessor} 精准捕获并触发自动退款；
 * 其余异常原样上抛（对账兜底语义不变）。
 *
 * <p>不做重试：409 是订单终态裁决，重试无意义；瞬时网络错误由对账收敛（ADR-0009）。</p>
 */
public class FeignOrderGateway implements OrderGateway {

    private static final Logger log = LoggerFactory.getLogger(FeignOrderGateway.class);

    private final OrderGateway delegate;

    public FeignOrderGateway(OrderGateway delegate) {
        this.delegate = delegate;
    }

    @Override
    public void notifyPaymentSucceeded(PaymentSucceededRequest request) {
        try {
            delegate.notifyPaymentSucceeded(request);
        } catch (FeignException.Conflict ex) {
            throw toNotPayableIfApplicable(request, ex);
        } catch (FeignException ex) {
            // 部分实现/旧网关以 409 以外的形态返回，按响应体错误码兜底识别
            if (ex.status() == 409 || ex.contentUTF8().contains(ErrorCodes.ORDER_NOT_PAYABLE)) {
                throw toNotPayableIfApplicable(request, ex);
            }
            throw ex;
        }
    }

    private RuntimeException toNotPayableIfApplicable(PaymentSucceededRequest request, FeignException ex) {
        String body = ex.contentUTF8();
        if (body != null && body.contains(ErrorCodes.ORDER_NOT_PAYABLE)) {
            log.warn("订单拒绝支付回写（自动退款触发）：orderNo={} paymentNo={} body={}",
                    request.orderNo(), request.paymentNo(), body);
            return new OrderNotPayableException(request.paymentNo(), request.orderNo(), "NOT_PAYABLE");
        }
        // 409 但不是 ORDER_NOT_PAYABLE（如乐观锁冲突）→ 可重试语义，交由上层重试/对账
        return ex;
    }
}
