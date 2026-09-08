package com.payment.reconciliation.infra;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.reconciliation.application.ChannelStatementLoadResult;
import com.payment.reconciliation.application.ChannelStatementLoader;
import com.payment.reconciliation.domain.ChannelStatement;
import com.payment.reconciliation.domain.ChannelStatementSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 渠道账单加载实现（ADR-0020）：按周期定位本地 Mock/预置 CSV fixture。
 *
 * <p>优先加载 {@code {dir}/{period}.csv}；未命中则回退到默认 {@code {dir}/sample.csv}，
 * 回退 MUST 留痕（指标 + WARN，绝不静默）。两者皆无 ⇒ {@code INTERNAL_ERROR}。
 * {@code period} 仅含 {@code [A-Za-z0-9._-]}（防路径穿越）；账单行非法 MUST WARN 并跳过（不静默丢弃）。</p>
 *
 * <p><b>文件系统覆盖目录（spec 022 / T421，E2E 差异注入专用）</b>：配置
 * {@code reconciliation.statement-dir-override}（默认空 = 关闭）时，先探测
 * {@code {override}/{period}.csv} 是否存在——存在则优先生效（E2E 运行时落盘注入账单差异，
 * 对账口径与 classpath fixture 完全一致）；不存在则走原有 classpath 逻辑，行为零变化。</p>
 */
@Component
public class CsvChannelStatementLoader implements ChannelStatementLoader {

    private static final Logger log = LoggerFactory.getLogger(CsvChannelStatementLoader.class);
    private static final Pattern PERIOD_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

    private final String dir;
    /** 周期 fixture 未命中时的回退默认账单文件（spec 006 T018：外置为配置键，默认 sample.csv）。 */
    private final String defaultFile;
    /** E2E 差异注入覆盖目录（spec 022 / T421；默认空 = 关闭，生产行为零变化）。 */
    private final String overrideDir;
    private final BusinessMetrics metrics;

    public CsvChannelStatementLoader(@Value("${reconciliation.statement-dir:fixtures/channel-statements}")
                                     String statementDir,
                                     @Value("${reconciliation.statement.default-file:sample.csv}")
                                     String defaultFile,
                                     @Value("${reconciliation.statement-dir-override:}") String overrideDir,
                                     BusinessMetrics metrics) {
        this.dir = statementDir.endsWith("/") ? statementDir : statementDir + "/";
        this.defaultFile = (defaultFile == null || defaultFile.isBlank()) ? "sample.csv" : defaultFile.trim();
        this.overrideDir = overrideDir == null ? "" : overrideDir.trim();
        this.metrics = metrics;
    }

    @Override
    public ChannelStatementLoadResult load(String period) {
        // 路径穿越防护（spec 006 T011 / FR-018）：禁目录分隔符，也禁 ".."（即便不含分隔符，
        // 仍可能在未来拼接逻辑里被解释成上级目录，提前拒掉）。
        if (period == null || !PERIOD_PATTERN.matcher(period).matches() || period.contains("..")) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT,
                    "period must match [A-Za-z0-9._-] and must not contain '..': " + period);
        }

        // spec 022 / T421：E2E 覆盖目录优先（文件存在才生效）
        if (!overrideDir.isEmpty()) {
            java.nio.file.Path overrideFile = java.nio.file.Path.of(overrideDir, period + ".csv");
            if (java.nio.file.Files.exists(overrideFile)) {
                List<ChannelStatement> statements = parseFile(overrideFile, overrideFile.toString());
                return new ChannelStatementLoadResult(statements,
                        ChannelStatementSource.fixture(overrideFile.toString(), statements.size(), false));
            }
        }

        String periodLocator = dir + period + ".csv";
        ClassPathResource periodResource = new ClassPathResource(periodLocator);
        if (periodResource.exists()) {
            List<ChannelStatement> statements = parse(periodResource, periodLocator);
            return new ChannelStatementLoadResult(statements,
                    ChannelStatementSource.fixture(periodLocator, statements.size(), false));
        }

        // 显式回退：周期 fixture 未命中 → 默认 sample.csv，留痕但不静默。
        String defaultLocator = dir + defaultFile;
        ClassPathResource defaultResource = new ClassPathResource(defaultLocator);
        if (!defaultResource.exists()) {
            throw BizException.of(ErrorCodes.INTERNAL_ERROR,
                    "channel statement fixture missing for period " + period + " and default " + defaultLocator);
        }
        metrics.counter("reconciliation.statement_fallback", 1, "module", "reconciliation", "period", period);
        log.warn("channel statement fallback: period={} missing, using default locator={}", period, defaultLocator);
        List<ChannelStatement> statements = parse(defaultResource, defaultLocator);
        return new ChannelStatementLoadResult(statements,
                ChannelStatementSource.fixture(defaultLocator, statements.size(), true));
    }

    /** 文件系统 CSV 解析（spec 022 / T421 E2E 覆盖目录）：与 classpath 解析同一套容错语义。 */
    private List<ChannelStatement> parseFile(java.nio.file.Path file, String locator) {
        List<ChannelStatement> statements = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(java.nio.file.Files.newInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (header) {
                    header = false;
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String[] parts = trimmed.split(",", -1);
                if (parts.length < 4) {
                    log.warn("channel statement malformed line skipped: locator={} line={} (cols<4)", locator, lineNo);
                    continue;
                }
                try {
                    statements.add(new ChannelStatement(parts[0].trim(), Long.parseLong(parts[1].trim()),
                            parts[2].trim(), parts[3].trim()));
                } catch (NumberFormatException e) {
                    log.warn("channel statement malformed line skipped: locator={} line={} (bad amount)",
                            locator, lineNo);
                }
            }
        } catch (IOException e) {
            throw new BizException(ErrorCodes.INTERNAL_ERROR, "failed to load channel statements: " + locator, e);
        }
        return statements;
    }

    private List<ChannelStatement> parse(ClassPathResource resource, String locator) {
        List<ChannelStatement> statements = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (header) {
                    header = false;
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                String[] parts = trimmed.split(",", -1);
                if (parts.length < 4) {
                    log.warn("channel statement malformed line skipped: locator={} line={} (cols<4)", locator, lineNo);
                    continue;
                }
                try {
                    statements.add(new ChannelStatement(parts[0].trim(), Long.parseLong(parts[1].trim()),
                            parts[2].trim(), parts[3].trim()));
                } catch (NumberFormatException e) {
                    log.warn("channel statement malformed line skipped: locator={} line={} (bad amount)",
                            locator, lineNo);
                }
            }
        } catch (IOException e) {
            throw new BizException(ErrorCodes.INTERNAL_ERROR, "failed to load channel statements: " + locator, e);
        }
        return statements;
    }
}
