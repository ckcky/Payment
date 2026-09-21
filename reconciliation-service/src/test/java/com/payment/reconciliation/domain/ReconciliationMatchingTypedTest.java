package com.payment.reconciliation.domain;

import com.payment.reconciliation.domain.ReconciliationMatching.StatementLineView;
import com.payment.reconciliation.statement.StatementLine;
import com.payment.reconciliation.statement.TypedStatementLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * typed 匹配（spec 032 §8.1/§8.2，T11）：强键 (merchantId, referenceType, reference)、
 * 回退键 (referenceType, reference)、legacy 4 列 reference 单键通道；
 * FEE_MISMATCH / DUPLICATE_CHANNEL / UNKNOWN_MAPPING 差异；FEE/SETTLEMENT 行不参与匹配。
 */
class ReconciliationMatchingTypedTest {

    private static PlatformFact fact(String type, String reference, long amountMinor, String merchantId) {
        return new PlatformFact(reference, type, amountMinor, "CNY", "SUCCEEDED", merchantId);
    }

    private static StatementLineView line(int lineNo, String type, String reference, String merchantId,
                                          long amountMinor, long feeMinor, String status) {
        return new TypedStatementLine(new StatementLine(null, lineNo, "MOCK", "TXN-" + lineNo, type, reference,
                "CHANNEL_TXN", merchantId, amountMinor, feeMinor, "CNY", status, null, "raw-" + lineNo));
    }

    @Test
    void strongKeyMatchesWhenMerchantAndTypeAgree() {
        ReconciliationMatchingResult result = ReconciliationMatching.matchTyped(
                List.of(fact("PAYMENT", "CH-1", 1000L, "M-1")),
                List.of(line(1, "PAYMENT", "CH-1", "M-1", 1000L, 0L, "SUCCEEDED")));

        assertThat(result.matches()).hasSize(1);
        assertThat(result.differences()).isEmpty();
    }

    @Test
    void merchantMismatchFallsBackToTypedKeyWithoutGuessingOwnership() {
        // 同 reference 但商户不同 ⇒ 强键不命中、不猜归属 ⇒ 双侧差异（不误平）
        ReconciliationMatchingResult result = ReconciliationMatching.matchTyped(
                List.of(fact("PAYMENT", "CH-1", 1000L, "M-1")),
                List.of(line(1, "PAYMENT", "CH-1", "M-2", 1000L, 0L, "SUCCEEDED")));

        assertThat(result.matches()).isEmpty();
        assertThat(result.differences()).extracting("type")
                .containsExactlyInAnyOrder(DifferenceType.PLATFORM_ONLY, DifferenceType.CHANNEL_ONLY);
    }

    @Test
    void legacyRowWithoutMerchantOrTypeMatchesByReferenceSingleKey() {
        // legacy 4 列行（referenceType 空 + PLATFORM_NO）：走 reference 单键通道（plan §2.5）
        StatementLineView legacy = new TypedStatementLine(new StatementLine(null, 1, "MOCK", null, null, "CH-1",
                "PLATFORM_NO", null, 1000L, 0L, "CNY", "SUCCEEDED", null, "CH-1,1000,CNY,SUCCEEDED"));
        ReconciliationMatchingResult result = ReconciliationMatching.matchTyped(
                List.of(fact("PAYMENT", "CH-1", 1000L, "M-1")), List.of(legacy));

        assertThat(result.matches()).hasSize(1);
        assertThat(result.differences()).isEmpty();
    }

    @Test
    void feeOnMatchedPrincipalRowProducesFeeMismatchInsteadOfChannelFee() {
        // 本金一致而渠道行 feeMinor > 0 ⇒ FEE_MISMATCH（只产差异，不补发 CHANNEL_FEE）
        ReconciliationMatchingResult result = ReconciliationMatching.matchTyped(
                List.of(fact("PAYMENT", "CH-1", 1000L, "M-1")),
                List.of(line(1, "PAYMENT", "CH-1", "M-1", 1000L, 30L, "SUCCEEDED")));

        assertThat(result.matches()).isEmpty();
        assertThat(result.differences()).hasSize(1);
        assertThat(result.differences().get(0).getType()).isEqualTo(DifferenceType.FEE_MISMATCH);
        assertThat(result.differences().get(0).getFeeAmountMinor()).isEqualTo(30L);
    }

    @Test
    void duplicateChannelTxnRowsProduceDuplicateChannelDifference() {
        // 第二行与第一行同 (channelTxnNo, referenceType) ⇒ DUPLICATE_CHANNEL（首条为准，不覆盖）
        StatementLineView duplicate = new TypedStatementLine(new StatementLine(null, 2, "MOCK", "TXN-1",
                "PAYMENT", "CH-1", "CHANNEL_TXN", "M-1", 1000L, 0L, "CNY", "SUCCEEDED", null, "raw-2"));
        ReconciliationMatchingResult result = ReconciliationMatching.matchTyped(
                List.of(fact("PAYMENT", "CH-1", 1000L, "M-1")),
                List.of(line(1, "PAYMENT", "CH-1", "M-1", 1000L, 0L, "SUCCEEDED"), duplicate));

        assertThat(result.matches()).hasSize(1); // 首条为准
        assertThat(result.differences()).extracting("type").containsExactly(DifferenceType.DUPLICATE_CHANNEL);
        assertThat(result.differences().get(0).getReference()).isEqualTo("TXN-1");
    }

    @Test
    void v2RowWithoutMerchantIsUnknownMappingNotSilentlySkipped() {
        ReconciliationMatchingResult result = ReconciliationMatching.matchTyped(
                List.of(),
                List.of(line(1, "PAYMENT", "CH-9", null, 500L, 0L, "SUCCEEDED")));

        assertThat(result.matches()).isEmpty();
        assertThat(result.differences()).extracting("type").containsExactly(DifferenceType.UNKNOWN_MAPPING);
    }

    @Test
    void settlementAndFeeRowsDoNotParticipateInMatching() {
        // FEE/SETTLEMENT 行由 G3 渠道资金事实聚合消费，不参与业务匹配（不产 CHANNEL_ONLY）
        ReconciliationMatchingResult result = ReconciliationMatching.matchTyped(
                List.of(fact("PAYMENT", "CH-1", 1000L, "M-1")),
                List.of(line(1, "PAYMENT", "CH-1", "M-1", 1000L, 0L, "SUCCEEDED"),
                        line(2, "FEE", "CH-FEE-1", "M-1", 30L, 0L, "SUCCEEDED"),
                        line(3, "SETTLEMENT", "CH-STL-1", "M-1", 970L, 0L, "SUCCEEDED")));

        assertThat(result.matches()).hasSize(1);
        assertThat(result.differences()).isEmpty();
    }
}
