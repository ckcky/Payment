package com.payment.reconciliation.infra;

import com.payment.reconciliation.statement.StatementLine;
import com.payment.reconciliation.statement.StatementParseException;
import com.payment.reconciliation.statement.StatementParser;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * CSV 账单解析实现（spec 032 T9 / plan §2.5，双表头）：
 *
 * <ul>
 *   <li><b>v2 表头</b> {@code referenceType,reference,channelTxnNo,merchantId,amountMinor,feeMinor,
 *       currencyCode,status,occurredAt}——真实渠道账单形态（渠道流水号为主键路径）；</li>
 *   <li><b>legacy 4 列表头</b> {@code reference,amountMinor,currencyCode,status}——历史 Mock 账单，
 *       referenceKind=PLATFORM_NO、referenceType 空 ⇒ 匹配走 legacy reference 单键通道。</li>
 * </ul>
 *
 * <p>结构性错误（表头不识别 / 列数不足 / 金额非数字 / 币种非法 / 状态缺失）⇒
 * {@link StatementParseException} 整批 REJECTED（§11 #2）；归一性缺陷（缺商户 / 缺键 /
 * 类型不可识别）⇒ 行保留留档，由匹配层产 {@code UNKNOWN_MAPPING} 差异（§11 #3 分工）。</p>
 */
@Component
public class CsvStatementParser implements StatementParser {

    private static final String[] V2_HEADER = {
            "referenceType", "reference", "channelTxnNo", "merchantId",
            "amountMinor", "feeMinor", "currencyCode", "status", "occurredAt"};
    private static final String[] LEGACY_HEADER = {"reference", "amountMinor", "currencyCode", "status"};
    private static final Set<String> REFERENCE_TYPES = Set.of("PAYMENT", "REFUND", "SETTLEMENT", "FEE");
    private static final DateTimeFormatter OCCURRED_AT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public List<StatementLine> parse(String channelCode, String period, String content) {
        List<StatementLine> lines = new ArrayList<>();
        String[] rawLines = content.replace("\r\n", "\n").replace('\uFEFF', ' ').split("\n", -1);
        boolean v2 = false;
        boolean legacy = false;
        int lineNo = 0;
        for (String raw : rawLines) {
            lineNo++;
            String trimmed = raw == null ? "" : raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] cols = trimmed.split(",", -1);
            if (!v2 && !legacy) {
                // 首个非空行 = 表头行（格式探测）
                String head = cols[0].trim();
                if (V2_HEADER[0].equals(head)) {
                    v2 = true;
                    continue;
                }
                if (LEGACY_HEADER[0].equals(head)) {
                    legacy = true;
                    continue;
                }
                throw new StatementParseException(lineNo, "unrecognized header: " + trimmed);
            }
            if (v2) {
                lines.add(parseV2Line(channelCode, lineNo, cols, trimmed));
            } else {
                lines.add(parseLegacyLine(channelCode, lineNo, cols, trimmed));
            }
        }
        if (!v2 && !legacy) {
            throw new StatementParseException(0, "empty statement: no header row");
        }
        return lines;
    }

    private StatementLine parseV2Line(String channelCode, int lineNo, String[] cols, String raw) {
        if (cols.length != V2_HEADER.length) {
            throw new StatementParseException(lineNo, "column count " + cols.length + " != " + V2_HEADER.length);
        }
        String referenceType = text(cols[0]);
        if (referenceType.isEmpty()) {
            throw new StatementParseException(lineNo, "referenceType is required");
        }
        String normalizedType = REFERENCE_TYPES.contains(referenceType) ? referenceType : "UNKNOWN";
        long amount = parseAmount(lineNo, "amountMinor", cols[4]);
        long fee = cols[5].isBlank() ? 0L : parseAmount(lineNo, "feeMinor", cols[5]);
        String currency = text(cols[6]);
        String status = text(cols[7]);
        if (currency.length() != 3) {
            throw new StatementParseException(lineNo, "invalid currency: " + currency);
        }
        if (status.isEmpty()) {
            throw new StatementParseException(lineNo, "status is required");
        }
        String reference = text(cols[1]);
        String channelTxnNo = text(cols[2]);
        String referenceKind = !channelTxnNo.isEmpty() ? "CHANNEL_TXN"
                : (!reference.isEmpty() ? "PLATFORM_NO" : "NONE");
        return new StatementLine(null, lineNo, channelCode, channelTxnNo.isEmpty() ? null : channelTxnNo,
                normalizedType, reference.isEmpty() ? null : reference, referenceKind,
                text(cols[3]).isEmpty() ? null : text(cols[3]),
                amount, fee, currency, status, normalizeOccurredAt(text(cols[8])), raw);
    }

    /**
     * occurredAt 归一（032 实跑踩坑：任意串直插 DATETIME 列 500）：接受
     * {@code yyyy-MM-dd HH:mm:ss} / ISO 本地日期时间（含可选毫秒与尾部 Z） / {@code yyyy-MM-dd}，
     * 统一为 {@code yyyy-MM-dd HH:mm:ss}；空或不可解析 ⇒ null（列可空，原文恒留 raw_text）。
     * 属归一性缺陷而非结构性错误（spec 032 §9①/§11 分工：不整批 REJECTED）。
     */
    private String normalizeOccurredAt(String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        String v = raw.replace('T', ' ');
        if (v.endsWith("Z")) {
            v = v.substring(0, v.length() - 1);
        }
        int dot = v.indexOf('.');
        if (dot > 0) {
            v = v.substring(0, dot);
        }
        try {
            if (v.length() == 10) {
                return LocalDate.parse(v).atStartOfDay().format(OCCURRED_AT_FMT);
            }
            return LocalDateTime.parse(v, OCCURRED_AT_FMT).format(OCCURRED_AT_FMT);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private StatementLine parseLegacyLine(String channelCode, int lineNo, String[] cols, String raw) {
        if (cols.length != LEGACY_HEADER.length) {
            throw new StatementParseException(lineNo, "column count " + cols.length + " != " + LEGACY_HEADER.length);
        }
        String reference = text(cols[0]);
        long amount = parseAmount(lineNo, "amountMinor", cols[1]);
        String currency = text(cols[2]);
        String status = text(cols[3]);
        if (currency.length() != 3) {
            throw new StatementParseException(lineNo, "invalid currency: " + currency);
        }
        if (status.isEmpty()) {
            throw new StatementParseException(lineNo, "status is required");
        }
        // legacy 4 列：referenceType 空（匹配走 legacy reference 单键通道）、referenceKind=PLATFORM_NO
        return new StatementLine(null, lineNo, channelCode, null, null,
                reference.isEmpty() ? null : reference, "PLATFORM_NO", null,
                amount, 0L, currency, status, null, raw);
    }

    private long parseAmount(int lineNo, String field, String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (RuntimeException e) {
            throw new StatementParseException(lineNo, "bad " + field + ": " + raw);
        }
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }
}
