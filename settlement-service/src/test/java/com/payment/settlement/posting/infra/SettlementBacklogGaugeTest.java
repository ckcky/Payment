package com.payment.settlement.posting.infra;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.core.observability.MicrometerBusinessMetrics;
import com.payment.settlement.domain.SettlementBatch;
import com.payment.settlement.domain.SettlementRepository;
import com.payment.settlement.domain.SettlementStatus;
import com.payment.settlement.infra.InMemorySettlementRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * spec 035 / T33：结算积压 Gauge（A-15 信号源）——未收口批次净额按 state 分组求和，
 * 已收口终态（SUCCEEDED/FAILED/CLOSED）不进积压；抓取期 DB 异常 ⇒ NaN 不外抛。
 */
class SettlementBacklogGaugeTest {

    private final InMemorySettlementRepository repository = new InMemorySettlementRepository();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private void bind() {
        new SettlementBacklogGauge(repository, new MicrometerBusinessMetrics(registry));
    }

    private static SettlementBatch batch(SettlementStatus status, long netMinor) {
        return SettlementBatch.rehydrate(null, "SB-" + status + netMinor, "1", "2026-09", "CNY",
                0L, 0L, 0L, netMinor, status, List.of(), "idem-" + status + netMinor, 0, 0, "2026-09");
    }

    private double value(String state) {
        Gauge gauge = registry.find("settlement_pending_amount").tag("state", state).gauge();
        assertThat(gauge).as("state=%s 序列已注册", state).isNotNull();
        return gauge.value();
    }

    @Test
    @DisplayName("未收口三态分组净额（绝对值求和）；已收口终态不进积压")
    void groupsOpenBatchesByStateAndExcludesSettled() {
        repository.save(batch(SettlementStatus.PENDING, 100L));
        repository.save(batch(SettlementStatus.CALCULATING, 50L));
        repository.save(batch(SettlementStatus.READY, -200L));   // 退款净额为负 ⇒ 绝对值计入
        repository.save(batch(SettlementStatus.EXECUTING, 700L));
        repository.save(batch(SettlementStatus.UNKNOWN, 90L));
        repository.save(batch(SettlementStatus.SUCCEEDED, 9999L));
        repository.save(batch(SettlementStatus.CLOSED, 8888L));
        bind();

        assertThat(value("pending")).isEqualTo(350.0);   // 100+50+|−200|
        assertThat(value("processing")).isEqualTo(700.0);
        assertThat(value("unknown")).isEqualTo(90.0);
    }

    @Test
    @DisplayName("空库=全 0；抓取期仓储异常 ⇒ NaN（不向抓取线程传播）")
    void emptyIsZeroAndDbFailureDegradesToNaN() {
        bind();
        assertThat(value("pending")).isZero();

        SettlementRepository broken = new InMemorySettlementRepository() {
            @Override
            public List<SettlementBatch> listBatches(String merchantId, String period) {
                throw new IllegalStateException("db down");
            }
        };
        SimpleMeterRegistry registry2 = new SimpleMeterRegistry();
        new SettlementBacklogGauge(broken, new MicrometerBusinessMetrics(registry2));
        Gauge gauge = registry2.find("settlement_pending_amount").tag("state", "pending").gauge();
        assertThat(Double.isNaN(gauge.value())).as("采样失败记 NaN，不抛异常").isTrue();
    }
}
