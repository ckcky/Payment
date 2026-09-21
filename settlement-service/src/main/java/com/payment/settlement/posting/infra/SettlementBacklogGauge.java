package com.payment.settlement.posting.infra;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.settlement.domain.SettlementBatch;
import com.payment.settlement.domain.SettlementRepository;
import com.payment.settlement.domain.SettlementStatus;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 结算积压 gauge（spec 035 §5.2 / A-15）：{@code settlement_pending_amount{state=...}} =
 * 未收口批次（PENDING/CALCULATING/READY → pending，EXECUTING → processing，UNKNOWN → unknown）
 * 的净额绝对值之和（<b>金额而非条数</b>——结算的痛是钱压着；最小货币单位整数，N-3）。
 *
 * <p>抓取期采样（同 {@link PostingGaugeBinder} 纪律）：DB 抖动返回 null（Micrometer 记 NaN），
 * 不传播异常。SUCCEEDED/FAILED/CLOSED 为已收口终态，不计入积压。</p>
 */
@Component
public class SettlementBacklogGauge {

    private static final Map<String, Set<SettlementStatus>> STATES = Map.of(
            "pending", Set.of(SettlementStatus.PENDING, SettlementStatus.CALCULATING,
                    SettlementStatus.READY),
            "processing", Set.of(SettlementStatus.EXECUTING),
            "unknown", Set.of(SettlementStatus.UNKNOWN));

    public SettlementBacklogGauge(SettlementRepository repository, BusinessMetrics metrics) {
        STATES.forEach((state, statuses) -> metrics.gauge("settlement_pending_amount", () -> {
            try {
                List<SettlementBatch> batches = repository.listBatches(null, null);
                long sum = 0L;
                for (SettlementBatch batch : batches) {
                    if (statuses.contains(batch.getStatus())) {
                        sum += Math.abs(batch.getNetMinor());
                    }
                }
                return sum;
            } catch (RuntimeException ex) {
                return null;
            }
        }, "module", "settlement", "state", state));
    }
}
