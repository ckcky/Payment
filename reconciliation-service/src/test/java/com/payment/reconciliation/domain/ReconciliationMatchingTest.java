package com.payment.reconciliation.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 纯匹配函数测试（T059）：一致、金额差异、状态差异、平台独有、渠道独有五种结果。
 */
class ReconciliationMatchingTest {

    @Test
    void consistentMatchWhenAmountAndStatusEqual() {
        ReconciliationMatchingResult result = ReconciliationMatching.match(
                List.of(new PlatformFact("ref-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED")),
                List.of(new ChannelStatement("ref-1", 1000L, "CNY", "SUCCEEDED")));

        assertThat(result.matches()).hasSize(1);
        assertThat(result.differences()).isEmpty();

        Match match = result.matches().get(0);
        assertThat(match.reference()).isEqualTo("ref-1");
        assertThat(match.type()).isEqualTo("PAYMENT");
        assertThat(match.amountMinor()).isEqualTo(1000L);
        assertThat(match.currencyCode()).isEqualTo("CNY");
    }

    @Test
    void amountMismatchWhenAmountsDiffer() {
        ReconciliationMatchingResult result = ReconciliationMatching.match(
                List.of(new PlatformFact("ref-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED")),
                List.of(new ChannelStatement("ref-1", 2000L, "CNY", "SUCCEEDED")));

        assertThat(result.matches()).isEmpty();
        assertThat(result.differences()).hasSize(1);

        Difference d = result.differences().get(0);
        assertThat(d.getType()).isEqualTo(DifferenceType.AMOUNT_MISMATCH);
        assertThat(d.getPlatformAmountMinor()).isEqualTo(1000L);
        assertThat(d.getChannelAmountMinor()).isEqualTo(2000L);
    }

    @Test
    void statusMismatchWhenAmountEqualButStatusDiffers() {
        ReconciliationMatchingResult result = ReconciliationMatching.match(
                List.of(new PlatformFact("ref-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED")),
                List.of(new ChannelStatement("ref-1", 1000L, "CNY", "FAILED")));

        assertThat(result.matches()).isEmpty();
        assertThat(result.differences()).hasSize(1);

        Difference d = result.differences().get(0);
        assertThat(d.getType()).isEqualTo(DifferenceType.STATUS_MISMATCH);
        assertThat(d.getPlatformStatus()).isEqualTo("SUCCEEDED");
        assertThat(d.getChannelStatus()).isEqualTo("FAILED");
    }

    @Test
    void platformOnlyWhenChannelHasNoMatchingReference() {
        ReconciliationMatchingResult result = ReconciliationMatching.match(
                List.of(new PlatformFact("ref-1", "PAYMENT", 1000L, "CNY", "SUCCEEDED")),
                List.of());

        assertThat(result.matches()).isEmpty();
        assertThat(result.differences()).hasSize(1);

        Difference d = result.differences().get(0);
        assertThat(d.getType()).isEqualTo(DifferenceType.PLATFORM_ONLY);
        assertThat(d.getPlatformAmountMinor()).isEqualTo(1000L);
        assertThat(d.getChannelAmountMinor()).isNull();
        assertThat(d.getChannelStatus()).isNull();
    }

    @Test
    void channelOnlyWhenPlatformHasNoMatchingReference() {
        ReconciliationMatchingResult result = ReconciliationMatching.match(
                List.of(),
                List.of(new ChannelStatement("channel-extra-1", 999L, "CNY", "SUCCEEDED")));

        assertThat(result.matches()).isEmpty();
        assertThat(result.differences()).hasSize(1);

        Difference d = result.differences().get(0);
        assertThat(d.getType()).isEqualTo(DifferenceType.CHANNEL_ONLY);
        assertThat(d.getReference()).isEqualTo("channel-extra-1");
        assertThat(d.getPlatformAmountMinor()).isNull();
        assertThat(d.getChannelAmountMinor()).isEqualTo(999L);
    }
}
