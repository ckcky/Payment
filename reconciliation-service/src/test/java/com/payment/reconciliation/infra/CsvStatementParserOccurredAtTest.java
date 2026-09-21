package com.payment.reconciliation.infra;

import com.payment.reconciliation.statement.StatementLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * occurredAt 归一（032 demo 实跑踩坑回归）：任意串直插 DATETIME 列会 500——
 * 可解析形态统一为 {@code yyyy-MM-dd HH:mm:ss}，不可解析 ⇒ null（列可空，raw_text 留原文），
 * 且 MUST NOT 整批 REJECTED（归一性缺陷非结构性错误，spec 032 §11 分工）。
 */
class CsvStatementParserOccurredAtTest {

    private final CsvStatementParser parser = new CsvStatementParser();

    private String v2Line(String occurredAt) {
        return "referenceType,reference,channelTxnNo,merchantId,amountMinor,feeMinor,currencyCode,status,occurredAt\n"
                + "PAYMENT,CH-X-1,CH-X-1,1,10000,0,CNY,SUCCEEDED," + occurredAt + "\n";
    }

    private String occurredOf(String occurredAt) {
        List<StatementLine> lines = parser.parse("MOCK", "2026-09-01", v2Line(occurredAt));
        assertThat(lines).hasSize(1);
        return lines.get(0).occurredAt();
    }

    @Test
    void canonicalAndIsoFormsNormalizeToMysqlDatetime() {
        assertThat(occurredOf("2026-09-01 12:34:56")).isEqualTo("2026-09-01 12:34:56");
        assertThat(occurredOf("2026-09-01T12:34:56")).isEqualTo("2026-09-01 12:34:56");
        assertThat(occurredOf("2026-09-01T12:34:56.789")).isEqualTo("2026-09-01 12:34:56");
        assertThat(occurredOf("2026-09-01T12:34:56Z")).isEqualTo("2026-09-01 12:34:56");
        assertThat(occurredOf("2026-09-01")).isEqualTo("2026-09-01 00:00:00");
    }

    @Test
    void unparseableOccurredAtBecomesNullNotRejection() {
        assertThat(occurredOf("demo-20260921222947")).isNull();
        assertThat(occurredOf("")).isNull();
        // 行本身仍保留（reference/金额在位，参与匹配），原文在 raw_text
        List<StatementLine> lines = parser.parse("MOCK", "2026-09-01", v2Line("demo-garbage"));
        assertThat(lines.get(0).reference()).isEqualTo("CH-X-1");
        assertThat(lines.get(0).rawText()).contains("demo-garbage");
    }
}
