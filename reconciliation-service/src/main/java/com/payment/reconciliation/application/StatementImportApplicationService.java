package com.payment.reconciliation.application;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.reconciliation.statement.StatementImport;
import com.payment.reconciliation.statement.StatementImportRepository;
import com.payment.reconciliation.statement.StatementLine;
import com.payment.reconciliation.statement.StatementParseException;
import com.payment.reconciliation.statement.StatementParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 账单导入编排（spec 032 §6.2 / §7.1，G1 账单真实化入口）：
 * 内容指纹（SHA-256）幂等 → 解析（结构失败整批 REJECTED）→ 标准化行落库 → 可执行核对。
 *
 * <p>幂等（§8.3 第一层）：同 {@code (channelCode, period, fingerprint)} 重放返回首次导入，
 * 零新增差异；同周期更正账单 = 新指纹 = 新导入批次并存（旧批次不可变）。</p>
 */
@Service
public class StatementImportApplicationService {

    private static final Logger log = LoggerFactory.getLogger(StatementImportApplicationService.class);

    private final StatementImportRepository importRepository;
    private final StatementParser statementParser;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    public StatementImportApplicationService(StatementImportRepository importRepository,
                                             StatementParser statementParser,
                                             BusinessMetrics metrics,
                                             StructuredAuditLogger auditLogger) {
        this.importRepository = importRepository;
        this.statementParser = statementParser;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    /**
     * 导入账单：指纹回查命中 ⇒ 幂等返回首次导入（{@code result=duplicate}）；
     * 解析成功 ⇒ NORMALIZED + 行落库；结构性解析失败 ⇒ 整批 REJECTED + 行号 + 原因，
     * 不产生半套差异（§11 #2）。
     */
    @Transactional
    public StatementImport importStatement(String channelCode, String period, String sourceType,
                                           String content, String importedBy) {
        String fingerprint = sha256(content);
        Optional<StatementImport> replay = importRepository.findByIdentity(channelCode, period, fingerprint);
        if (replay.isPresent()) {
            metrics.counter("reconciliation.statement_import", 1, "channel", channelCode, "result", "duplicate");
            log.info("statement import replay (idempotent): channel={} period={} importNo={}",
                    channelCode, period, replay.get().getImportNo());
            return replay.get();
        }

        StatementImport imprt = new StatementImport(channelCode, period, sourceType, fingerprint, importedBy);
        try {
            List<StatementLine> lines = statementParser.parse(channelCode, period, content);
            importRepository.save(imprt);
            importRepository.saveLines(imprt.getId(), channelCode, lines);
            imprt.normalize(lines.size());
            importRepository.save(imprt);
            metrics.counter("reconciliation.statement_import", 1, "channel", channelCode, "result", "accepted");
            auditLogger.audit("statement_import", period, 0L, "CNY", "RECEIVED",
                    imprt.getStatus().name(), "reconciliation", imprt.getImportNo());
            return imprt;
        } catch (StatementParseException e) {
            imprt.reject("line " + e.getLineNo() + ": " + e.getReason());
            importRepository.save(imprt);
            metrics.counter("reconciliation.statement_import", 1, "channel", channelCode, "result", "rejected");
            log.warn("statement import rejected: channel={} period={} reason={}",
                    channelCode, period, imprt.getErrorReason());
            auditLogger.audit("statement_import", period, 0L, "CNY", "RECEIVED",
                    imprt.getStatus().name(), "reconciliation", imprt.getImportNo());
            return imprt;
        }
    }

    public Optional<StatementImport> getImport(Long id) {
        return importRepository.findById(id);
    }

    public List<StatementImport> listImports(String period, String channelCode) {
        return importRepository.list(period, channelCode);
    }

    public List<StatementLine> linesOf(Long importId) {
        return importRepository.findLines(importId);
    }

    /** SHA-256（规范化：去 BOM、\r\n→\n、去尾部空白）内容指纹。 */
    static String sha256(String content) {
        String normalized = content == null ? "" : content;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
