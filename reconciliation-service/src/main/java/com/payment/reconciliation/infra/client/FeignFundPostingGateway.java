package com.payment.reconciliation.infra.client;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.dto.rpc.AccountingEventRequest;
import com.payment.common.dto.rpc.AccountingEventResponse;
import com.payment.reconciliation.application.FundPostingGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link FundPostingGateway} Feign 实现：复用 ledger 事件契约客户端
 * （{@code POST /internal/ledger/accounting-events}，spec 031 §6.2 契约不改形状）。
 * 失败不静默——PERIOD_CLOSED 等业务拒绝原样上抛（§11 #8），其余包装 INTERNAL_ERROR。
 */
@Component
public class FeignFundPostingGateway implements FundPostingGateway {

    private static final Logger log = LoggerFactory.getLogger(FeignFundPostingGateway.class);

    private final LedgerEventFeignClient ledgerClient;

    public FeignFundPostingGateway(LedgerEventFeignClient ledgerClient) {
        this.ledgerClient = ledgerClient;
    }

    @Override
    public PostingResult postEvent(AccountingEventRequest request) {
        try {
            AccountingEventResponse response = ledgerClient.postEvent(request);
            log.info("channel fund event posted: eventType={} sourceId={} postingNo={} postingId={}",
                    request.eventType(), request.sourceId(), response.postingNo(), response.postingId());
            return new PostingResult(response.postingNo(), String.valueOf(response.postingId()));
        } catch (BizException ex) {
            throw ex; // PERIOD_CLOSED / EVENT_FIELD_MISSING 等业务语义原样传播
        } catch (RuntimeException ex) {
            log.error("channel fund event posting failed: sourceId={} reason={}",
                    request.sourceId(), ex.getMessage());
            throw BizException.of(ErrorCodes.INTERNAL_ERROR,
                    "channel fund posting failed: " + ex.getMessage());
        }
    }
}
