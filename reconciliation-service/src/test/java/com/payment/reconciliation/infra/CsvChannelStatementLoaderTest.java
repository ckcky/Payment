package com.payment.reconciliation.infra;

import com.payment.common.core.error.BizException;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.reconciliation.application.ChannelStatementLoadResult;
import com.payment.reconciliation.domain.ChannelStatementSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 渠道账单加载（spec 006 T011 / ADR-0020）：周期命中、显式回退、两者皆无与路径穿越防护。
 *
 * <p>回退 MUST 留痕（{@code fallbackUsed=true}）而非静默——否则资金运营会把「没找到本月账单」
 * 误读成「本月无差异」。</p>
 */
class CsvChannelStatementLoaderTest {

    private static final String FIXTURE_DIR = "fixtures/channel-statements";

    private CsvChannelStatementLoader loader(String dir) {
        return new CsvChannelStatementLoader(dir, "sample.csv", "", new NoopBusinessMetrics());
    }

    @Test
    void loadsPeriodFixtureWithoutFallback() {
        ChannelStatementLoadResult result = loader(FIXTURE_DIR).load("2026-08-31");

        assertThat(result.statements()).isNotEmpty();
        ChannelStatementSource source = result.source();
        assertThat(source.fallbackUsed()).isFalse();
        assertThat(source.locator()).contains("2026-08-31.csv");
        assertThat(source.entryCount()).isEqualTo(result.statements().size());
    }

    @Test
    void fallsBackToDefaultFixtureWhenPeriodMissing() {
        ChannelStatementLoadResult result = loader(FIXTURE_DIR).load("2099-01");

        ChannelStatementSource source = result.source();
        assertThat(source.fallbackUsed()).isTrue();
        assertThat(source.locator()).contains("sample.csv");
        // sample.csv 只有表头 ⇒ 0 条；回退本身必须成功，不得抛异常
        assertThat(result.statements()).isEmpty();
    }

    @Test
    void bothPeriodAndDefaultMissingIsInternalError() {
        CsvChannelStatementLoader loader = loader("fixtures/no-such-dir");

        assertThatThrownBy(() -> loader.load("2099-01"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("channel statement fixture missing");
    }

    @Test
    void periodWithSlashIsRejected() {
        CsvChannelStatementLoader loader = loader(FIXTURE_DIR);

        assertThatThrownBy(() -> loader.load("2026/08"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("period must match");
    }

    @Test
    void periodWithDotDotIsRejected() {
        CsvChannelStatementLoader loader = loader(FIXTURE_DIR);

        assertThatThrownBy(() -> loader.load(".."))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("period must match");
    }

    @Test
    void nullPeriodIsRejected() {
        CsvChannelStatementLoader loader = loader(FIXTURE_DIR);

        assertThatThrownBy(() -> loader.load(null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("period must match");
    }
}
