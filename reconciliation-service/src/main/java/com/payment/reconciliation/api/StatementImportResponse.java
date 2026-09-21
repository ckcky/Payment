package com.payment.reconciliation.api;

import com.payment.reconciliation.statement.StatementImport;

/**
 * 账单导入响应（spec 032 §10.1）：{@code status=RECEIVED|NORMALIZED|REJECTED}；
 * REJECTED 带 {@code errorReason}（行号 + 原因），不产生半套差异。
 */
public record StatementImportResponse(Long id, String importNo, String channelCode, String period,
                                      String sourceType, int rowCount, String status, String errorReason,
                                      String importedBy) {

    public static StatementImportResponse from(StatementImport imprt) {
        return new StatementImportResponse(imprt.getId(), imprt.getImportNo(), imprt.getChannelCode(),
                imprt.getPeriod(), imprt.getSourceType(), imprt.getRowCount(), imprt.getStatus().name(),
                imprt.getErrorReason(), imprt.getImportedBy());
    }
}
