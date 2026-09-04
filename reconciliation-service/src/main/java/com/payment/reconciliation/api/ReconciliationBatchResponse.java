package com.payment.reconciliation.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payment.reconciliation.domain.ChannelStatementSource;
import com.payment.reconciliation.domain.ReconciliationBatch;

/**
 * 对账批次响应 DTO。状态用枚举名（String）暴露，避免 API 层与领域枚举耦合。
 * 携带关闭溯源（closedBy/closedAt）与账单来源溯源（statementSource，ADR-0019/0020）。
 */
public record ReconciliationBatchResponse(Long id, String batchNo, String period, String source, String status,
                                          int matchCount, int differenceCount,
                                          String closedBy, String closedAt, String statementSource) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static ReconciliationBatchResponse from(ReconciliationBatch batch) {
        String sourceJson = null;
        ChannelStatementSource src = batch.getStatementSource();
        if (src != null) {
            try {
                sourceJson = MAPPER.writeValueAsString(src);
            } catch (JsonProcessingException e) {
                sourceJson = null;
            }
        }
        return new ReconciliationBatchResponse(
                batch.getId(),
                batch.getBatchNo(),
                batch.getPeriod(),
                batch.getSource(),
                batch.getStatus().name(),
                batch.getMatches().size(),
                batch.getDifferences().size(),
                batch.getClosedBy(),
                batch.getClosedAt(),
                sourceJson);
    }
}
