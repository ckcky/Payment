package com.payment.reconciliation.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 账单导入请求（spec 032 §10.1）：内容指纹（SHA-256）幂等——同指纹重放返回首次导入。
 * {@code sourceType} 缺省 FILE；{@code content} 为账单原文（CSV，双表头格式见 CsvStatementParser）。
 */
public record StatementImportRequest(@NotBlank String channelCode,
                                     @NotBlank String period,
                                     String sourceType,
                                     @NotBlank String content,
                                     String importedBy) {
}
