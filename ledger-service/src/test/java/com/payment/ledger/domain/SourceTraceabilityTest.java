package com.payment.ledger.domain;

import static com.payment.ledger.LedgerTestSupport.channelSettlement;
import static com.payment.ledger.LedgerTestSupport.paymentCapture;
import static com.payment.ledger.LedgerTestSupport.refund;
import static com.payment.ledger.LedgerTestSupport.wiring;
import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.ledger.LedgerTestSupport.Wiring;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 溯源（spec 031 §12 / ADR-0063）：每笔 Posting 携带业务来源 (sourceType, sourceId)，
 * 可按业务单号反查且互不串源。031 起查询粒度是交易级（POST /internal/ledger/postings），
 * 分录本身不再单独携带 sourceType（挂在所属交易上）。
 */
class SourceTraceabilityTest {

    @Test
    @DisplayName("每笔入账携带业务来源：sourceType/sourceId 落在交易上，分录可归属到交易")
    void everyPostingCarriesItsBusinessSource() {
        Wiring w = wiring();
        Posting capture = w.engine().post(paymentCapture("PM-SRC-1", 10000, 80, 60, "M001", "ALIPAY"));

        assertThat(capture.getSourceType()).isEqualTo(LedgerSourceType.PAYMENT);
        assertThat(capture.getSourceId()).isEqualTo("PM-SRC-1");
        assertThat(capture.getEntries()).isNotEmpty()
                .allSatisfy(e -> assertThat(e.getPostingId()).isEqualTo(capture.getId()));
    }

    @Test
    @DisplayName("按 (sourceType, sourceId) 反查：PAYMENT/REFUND/SETTLEMENT 各回各的交易")
    void postingsAreRetrievableBySourceTypeAndId() {
        Wiring w = wiring();
        w.engine().post(paymentCapture("PM-SRC-2", 10000, 80, 60, "M001", "ALIPAY"));
        w.engine().post(paymentCapture("PM-SRC-3", 10000, 0, 0, "M001", "ALIPAY"));
        w.engine().post(refund("RF-SRC-1", 3000, 0, 0, "M001", "ALIPAY"));
        w.engine().post(channelSettlement("ST-SRC-1", 9400, "ALIPAY"));

        assertThat(w.ledger().findBySource(LedgerSourceType.PAYMENT, "PM-SRC-2"))
                .singleElement().satisfies(p -> {
                    assertThat(p.getEventType()).isEqualTo(AccountingEventType.PAYMENT_CAPTURE);
                    assertThat(p.getEntries()).hasSize(6);
                });
        assertThat(w.ledger().findBySource(LedgerSourceType.REFUND, "RF-SRC-1"))
                .singleElement().satisfies(p -> assertThat(p.getEventType()).isEqualTo(AccountingEventType.REFUND));
        assertThat(w.ledger().findBySource(LedgerSourceType.SETTLEMENT, "ST-SRC-1"))
                .singleElement().satisfies(p ->
                        assertThat(p.getEventType()).isEqualTo(AccountingEventType.CHANNEL_SETTLEMENT));
    }

    @Test
    @DisplayName("反查不串源：同号不同源返回空，未知 sourceId 返回空")
    void sourceQueryDoesNotLeakAcrossSources() {
        Wiring w = wiring();
        w.engine().post(paymentCapture("PM-SRC-4", 10000, 80, 60, "M001", "ALIPAY"));
        w.engine().post(paymentCapture("PM-SRC-5", 10000, 0, 0, "M001", "ALIPAY"));
        w.engine().post(refund("RF-SRC-2", 3000, 0, 0, "M001", "ALIPAY"));

        assertThat(w.ledger().findBySource(LedgerSourceType.REFUND, "PM-SRC-4")).isEmpty();
        assertThat(w.ledger().findBySource(LedgerSourceType.PAYMENT, "RF-SRC-2")).isEmpty();
        assertThat(w.ledger().findBySource(LedgerSourceType.PAYMENT, "PM-NONE")).isEmpty();
        // 同源多条交易都能查到（对照 PM-SRC-4/PM-SRC-5）
        assertThat(w.ledger().findBySource(LedgerSourceType.PAYMENT, "PM-SRC-4")).hasSize(1);
        assertThat(w.ledger().findBySource(LedgerSourceType.PAYMENT, "PM-SRC-5")).hasSize(1);
    }
}
