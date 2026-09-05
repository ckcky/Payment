package com.payment.order.application;

import com.payment.common.dto.rpc.CreatePaymentRequest;
import com.payment.common.dto.rpc.CreatePaymentResponse;
import com.payment.common.dto.rpc.RefundCommandRequest;
import com.payment.common.dto.rpc.RefundCommandResponse;

/**
 * order → payment 的出站同步 RPC 端口；生产用 Feign，测试用 fake。
 *
 * <p>Feature 016（ADR-0054）：order 升为业务编排者后，transaction 层在判定
 * 「重复 / 超额（surplus）」时以 {@code transactionNo + paymentNo} 调用
 * {@link #refund(RefundCommandRequest)} 发起自动退款；payment 仅作执行方。</p>
 */
public interface PaymentGateway {

    CreatePaymentResponse createPayment(CreatePaymentRequest request);

    /** 发起自动退款命令（FR-004/FR-005）；payment 走退款域三步链执行。 */
    RefundCommandResponse refund(RefundCommandRequest request);
}
