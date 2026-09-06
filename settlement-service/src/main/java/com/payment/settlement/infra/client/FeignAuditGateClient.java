package com.payment.settlement.infra.client;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.settlement.application.AuditGateClient;
import com.payment.settlement.application.AuditGateDecision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link AuditGateClient} 的 Feign 实现。
 *
 * <p>fail-closed（宁拦勿放）：门禁开启时 reconciliation 不可达 → 拒绝结算并 ERROR 留痕；
 * {@code settlement.audit-gate.enabled=false} 时恒放行（与 spec 017 之前行为一致）。</p>
 */
@Component
public class FeignAuditGateClient implements AuditGateClient {

    private final AuditGateFeignClient feign;
    private final boolean enabled;

    public FeignAuditGateClient(AuditGateFeignClient feign,
                                @Value("${settlement.audit-gate.enabled:true}") boolean enabled) {
        this.feign = feign;
        this.enabled = enabled;
    }

    @Override
    public AuditGateDecision getSettlementGate(String period) {
        if (!enabled) {
            return AuditGateClient.disabled().getSettlementGate(period);
        }
        AuditGateFeignClient.AuditGateDto dto;
        try {
            dto = feign.getSettlementGate(period);
        } catch (RuntimeException ex) {
            // fail-closed：审计门禁不可达时拒绝建批，绝不静默放行
            throw BizException.of(ErrorCodes.STATE_TRANSITION_VIOLATION,
                    "audit settlement gate unavailable (fail-closed): " + ex.getMessage());
        }
        if (dto == null) {
            return AuditGateClient.disabled().getSettlementGate(period);
        }
        List<AuditGateDecision.BlockingDifference> diffs = dto.blockingDifferences() == null ? List.of()
                : dto.blockingDifferences().stream()
                        .map(d -> new AuditGateDecision.BlockingDifference(
                                d.kind(), d.sourceType(), d.sourceId(), d.severity(), d.amountMinor(), d.currency()))
                        .toList();
        return new AuditGateDecision(dto.decision(), dto.balanced(), diffs);
    }
}
