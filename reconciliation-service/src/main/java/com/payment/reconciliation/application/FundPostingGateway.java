package com.payment.reconciliation.application;

import com.payment.common.dto.rpc.AccountingEventRequest;

/**
 * 渠道资金事实入账出站端口（spec 032 §10.2，G3）：经 ledger 唯一事件契约
 * {@code POST /internal/ledger/accounting-events} 发 CHANNEL_SETTLEMENT / CHANNEL_FEE 事件。
 * 幂等由 ledger 按 {@code {eventType}:{sourceId}} 派生键承接（同账单更正重放天然吸收）。
 */
public interface FundPostingGateway {

    /** @return ledger 侧 posting 单号与 id。 */
    PostingResult postEvent(AccountingEventRequest request);

    record PostingResult(String postingNo, String postingId) {
    }
}
