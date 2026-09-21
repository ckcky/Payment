package com.payment.reconciliation.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.observability.NoopBusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.reconciliation.infra.InMemoryStatementImportRepository;
import com.payment.reconciliation.statement.StatementImport;
import com.payment.reconciliation.statement.StatementImportStatus;
import com.payment.reconciliation.statement.StatementLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 账单导入应用服务（spec 032 §8.3 第一层幂等 + §11 #2）：SHA-256 指纹三层幂等之导入层、
 * 结构性错误整批 REJECTED、归一缺陷行留档；台账查询可回放导入历史。
 */
class StatementImportApplicationServiceTest {

    private final InMemoryStatementImportRepository imports = new InMemoryStatementImportRepository();
    private final StatementImportApplicationService service = new StatementImportApplicationService(
            imports, new com.payment.reconciliation.infra.CsvStatementParser(),
            new NoopBusinessMetrics(), new StructuredAuditLogger());

    private static final String V2_CSV = """
            referenceType,reference,channelTxnNo,merchantId,amountMinor,feeMinor,currencyCode,status,occurredAt
            PAYMENT,CH-1,TXN-CH-1,M-1,1000,0,CNY,SUCCEEDED,2026-08-01T10:00:00Z
            REFUND,CH-2,TXN-CH-2,M-1,300,0,CNY,SUCCEEDED,2026-08-02T10:00:00Z
            """;

    @Test
    void importAcceptsV2StatementAndNormalizesLines() {
        StatementImport imprt = service.importStatement("MOCK", "2026-08", "FILE", V2_CSV, "ops-1");

        assertThat(imprt.getStatus()).isEqualTo(StatementImportStatus.NORMALIZED);
        assertThat(imprt.getRowCount()).isEqualTo(2);
        List<StatementLine> lines = imports.findLines(imprt.getId());
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).referenceType()).isEqualTo("PAYMENT");
        assertThat(lines.get(0).merchantId()).isEqualTo("M-1");
        assertThat(lines.get(0).channelTxnNo()).isEqualTo("TXN-CH-1");
        // 原始行留档（争议取证）
        assertThat(lines.get(0).rawText()).contains("PAYMENT,CH-1");
    }

    @Test
    void duplicateFingerprintReplayReturnsFirstImportWithoutNewLines() {
        StatementImport first = service.importStatement("MOCK", "2026-08", "FILE", V2_CSV, "ops-1");
        int linesBefore = imports.findLines(first.getId()).size();

        StatementImport replay = service.importStatement("MOCK", "2026-08", "FILE", V2_CSV, "ops-2");

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(replay.getImportNo()).isEqualTo(first.getImportNo());
        assertThat(imports.findLines(replay.getId())).hasSize(linesBefore);
        assertThat(imports.list("2026-08", "MOCK")).hasSize(1);
    }

    @Test
    void structuralParseFailureRejectsWholeBatchWithoutLines() {
        String broken = """
                referenceType,reference,channelTxnNo,merchantId,amountMinor,feeMinor,currencyCode,status,occurredAt
                PAYMENT,CH-1,TXN-CH-1,M-1,1000,CNY,SUCCEEDED
                """;

        StatementImport rejected = service.importStatement("MOCK", "2026-08", "FILE", broken, "ops-1");

        assertThat(rejected.getStatus()).isEqualTo(StatementImportStatus.REJECTED);
        assertThat(rejected.getErrorReason()).contains("column count");
        assertThat(imports.findLines(rejected.getId())).isEmpty();
    }

    @Test
    void unrecognizedHeaderIsRejected() {
        StatementImport rejected = service.importStatement("MOCK", "2026-08", "FILE", "foo,bar\n1,2", "ops-1");

        assertThat(rejected.getStatus()).isEqualTo(StatementImportStatus.REJECTED);
        assertThat(rejected.getErrorReason()).contains("unrecognized header");
        assertThat(imports.list("2026-08", "MOCK")).hasSize(1);
    }
}
