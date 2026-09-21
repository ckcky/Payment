package com.payment.settlement.posting.infra;

import com.payment.common.dto.rpc.AccountingEventRequest;
import com.payment.common.dto.rpc.AccountingEventResponse;
import com.payment.settlement.infra.client.LedgerFeignClient;
import com.payment.settlement.posting.application.PostingEventTypes;
import com.payment.settlement.posting.application.PostingPendingRecorder;
import com.payment.settlement.posting.application.PostingReplayer;
import com.payment.settlement.posting.domain.PendingPosting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 台账补投分派器（spec 034 §12.1 / plan §2.3，settlement 落点）：把台账行的原始载荷
 * 原样重发到 ledger-service。
 *
 * <p><b>载荷零改写</b>：payload_json 与首次请求逐字节一致（登记时序列化原文），重放反序列化后
 * 原样重发——不重算派生键、不重构请求，杜绝二次漂移（plan §2.1）。</p>
 *
 * <p><b>幂等闸门在目的地</b>：Ledger 按 {@code MERCHANT_SETTLEMENT:{batchNo}} 派生键吸收重复
 * （031 §9）——本类不添加任何「重放标记」（029/031 协议红线）。</p>
 */
@Component
public class PostingReplayDispatcher implements PostingReplayer {

    private static final Logger log = LoggerFactory.getLogger(PostingReplayDispatcher.class);

    private final PostingPendingRecorder recorder;
    private final LedgerFeignClient ledgerClient;

    public PostingReplayDispatcher(PostingPendingRecorder recorder, LedgerFeignClient ledgerClient) {
        this.recorder = recorder;
        this.ledgerClient = ledgerClient;
    }

    @Override
    public boolean replay(PendingPosting posting) {
        String payload = posting.getPayloadJson();
        return switch (posting.getEventType()) {
            case PostingEventTypes.MERCHANT_SETTLEMENT -> {
                AccountingEventRequest request = recorder.readPayload(payload, AccountingEventRequest.class);
                AccountingEventResponse response = ledgerClient.postEvent(request);
                log.info("结算记账补投受理 batchNo={} postingId={}",
                        posting.getSourceId(), response.postingId());
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
