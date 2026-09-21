package com.payment.reconciliation.testsupport;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.reconciliation.application.AutoDispositionService;
import com.payment.reconciliation.application.PaymentFactsClient;
import com.payment.reconciliation.application.ReconciliationApplicationService;
import com.payment.reconciliation.application.RefundFactsClient;
import com.payment.reconciliation.domain.ReconciliationRepository;
import com.payment.reconciliation.infra.InMemoryStatementImportRepository;
import com.payment.reconciliation.statement.StatementImport;
import com.payment.reconciliation.statement.StatementImportRepository;
import com.payment.reconciliation.statement.StatementLine;

import java.util.List;
import java.util.UUID;

/**
 * 032 测试公共夹具（内存仓储 + 保守关闭的自动处置，不起 Spring）：
 * 账单导入走 {@code InMemoryStatementImportRepository}，run 编排与 032 前语义
 * （legacy reference 单键）保持可测。
 */
public final class ReconciliationTestSupport {

    private ReconciliationTestSupport() {
    }

    /** legacy 4 列语义账单行（referenceType 空 + PLATFORM_NO ⇒ 匹配走 reference 单键通道）。 */
    public static StatementLine legacyLine(int lineNo, String reference, long amountMinor, String status) {
        String raw = reference + "," + amountMinor + ",CNY," + status;
        return new StatementLine(null, lineNo, null, null, null, reference, "PLATFORM_NO",
                null, amountMinor, 0L, "CNY", status, null, raw);
    }

    /** v2 typed 账单行（显式 referenceType + merchantId + 渠道流水号）。 */
    public static StatementLine typedLine(int lineNo, String referenceType, String reference,
                                          long amountMinor, String status) {
        String raw = String.join(",", referenceType, reference, "TXN-" + reference, "M-1",
                String.valueOf(amountMinor), "0", "CNY", status, "");
        return new StatementLine(null, lineNo, "MOCK", "TXN-" + reference, referenceType, reference,
                "CHANNEL_TXN", "M-1", amountMinor, 0L, "CNY", status, null, raw);
    }

    /** 种子一个 NORMALIZED 导入（内容指纹随机 ⇒ 不同测试场景互不碰撞，幂等语义由服务层测试覆盖）。 */
    public static StatementImport seedImport(StatementImportRepository imports, String channelCode,
                                             String period, List<StatementLine> lines) {
        StatementImport imprt = new StatementImport(channelCode, period, "FILE",
                UUID.randomUUID().toString(), "test");
        imports.save(imprt);
        imports.saveLines(imprt.getId(), channelCode, lines);
        imprt.normalize(lines.size());
        imports.save(imprt);
        return imprt;
    }

    /** 032 编排服务工厂：自动处置以默认开关（关闭）装配，账单走内存导入仓储。 */
    public static ReconciliationApplicationService service(ReconciliationRepository repository,
                                                           InMemoryStatementImportRepository imports,
                                                           PaymentFactsClient payments,
                                                           RefundFactsClient refunds,
                                                           BusinessMetrics metrics,
                                                           StructuredAuditLogger auditLogger) {
        AutoDispositionService autoDisposition = new AutoDispositionService(
                repository, null, null, null, metrics, auditLogger, false, 0L);
        return new ReconciliationApplicationService(repository, payments, refunds, imports,
                autoDisposition, metrics, auditLogger);
    }
}
