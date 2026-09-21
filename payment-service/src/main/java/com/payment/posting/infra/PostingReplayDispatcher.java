package com.payment.posting.infra;

import com.payment.common.dto.rpc.AccountingEventRequest;
import com.payment.common.dto.rpc.AccountingEventResponse;
import com.payment.common.dto.rpc.PaymentSucceededRequest;
import com.payment.common.dto.rpc.RefundResultNotification;
import com.payment.payment.application.OrderGateway;
import com.payment.payment.infra.client.LedgerFeignClient;
import com.payment.posting.application.PostingEventTypes;
import com.payment.posting.application.PostingPendingRecorder;
import com.payment.posting.application.PostingReplayer;
import com.payment.posting.domain.PendingPosting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 台账补投分派器（spec 034 §12.1 / plan §2.3）：按事件类型把台账行的原始载荷重发到原目的地。
 *
 * <p><b>载荷零改写</b>：payload_json 与首次请求逐字节一致（登记时序列化原文），重放反序列化后
 * 原样重发——不重算派生键、不重构请求，杜绝二次漂移（plan §2.1）。</p>
 *
 * <p><b>幂等闸门在目的地</b>：Ledger 按 {@code {eventType}:{sourceId}} 派生键吸收重复（031 §9）；
 * order 侧支付成功 / 退款结果按终态吸收重复——本类不添加任何「重放标记」（029/031 协议红线）。</p>
 */
@Component
public class PostingReplayDispatcher implements PostingReplayer {

    private static final Logger log = LoggerFactory.getLogger(PostingReplayDispatcher.class);

    private final PostingPendingRecorder recorder;
    private final LedgerFeignClient ledgerClient;
    private final OrderGateway orderGateway;

    public PostingReplayDispatcher(PostingPendingRecorder recorder,
                                   LedgerFeignClient ledgerClient,
                                   OrderGateway orderGateway) {
        this.recorder = recorder;
        this.ledgerClient = ledgerClient;
        this.orderGateway = orderGateway;
    }

    @Override
    public boolean replay(PendingPosting posting) {
        String payload = posting.getPayloadJson();
        return switch (posting.getEventType()) {
            case PostingEventTypes.PAYMENT_CAPTURE, PostingEventTypes.REFUND -> {
                AccountingEventRequest request = recorder.readPayload(payload, AccountingEventRequest.class);
                AccountingEventResponse response = ledgerClient.postEvent(request);
                log.info("记账补投受理 event={} sourceId={} postingId={}",
                        posting.getEventType(), posting.getSourceId(), response.postingId());
                yield true;
            }
            case PostingEventTypes.ORDER_NOTIFY_SUCCEEDED -> {
                PaymentSucceededRequest request = recorder.readPayload(payload, PaymentSucceededRequest.class);
                orderGateway.notifyPaymentSucceeded(request);
                log.info("支付成功通知补投重发 sourceId={} orderNo={}", posting.getSourceId(), request.orderNo());
                yield true;
            }
            case PostingEventTypes.ORDER_NOTIFY_REFUND_RESULT -> {
                RefundResultNotification notification = recorder.readPayload(payload, RefundResultNotification.class);
                orderGateway.notifyRefundResult(notification);
                log.info("退款结果通知补投重发 sourceId={} txrf={}",
                        posting.getSourceId(), notification.transactionRefundNo());
                yield true;
            }
            default -> {
                // 未知事件类型 = 编码缺陷：不抛（避免整轮中断），按失败退避并在人工队列可见
                log.error("台账行事件类型不可重放 event={} sourceId={}",
                        posting.getEventType(), posting.getSourceId());
                yield false;
            }
        };
    }
}
