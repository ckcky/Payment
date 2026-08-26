package com.payment.reconciliation.infra;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.reconciliation.application.ChannelStatementLoader;
import com.payment.reconciliation.domain.ChannelStatement;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 渠道账单加载实现：读取本地 Mock/预置 CSV fixture。
 * 头行：reference,amountMinor,currencyCode,status；跳过空行，同步读取，不引入额外库。
 */
@Component
public class CsvChannelStatementLoader implements ChannelStatementLoader {

    private static final String FIXTURE = "fixtures/channel-statements/sample.csv";

    @Override
    public List<ChannelStatement> load(String period) {
        List<ChannelStatement> statements = new ArrayList<>();
        ClassPathResource resource = new ClassPathResource(FIXTURE);
        if (!resource.exists()) {
            throw BizException.of(ErrorCodes.INTERNAL_ERROR, "channel statement fixture missing: " + FIXTURE);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
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
                    continue;
                }
                statements.add(new ChannelStatement(parts[0].trim(), Long.parseLong(parts[1].trim()),
                        parts[2].trim(), parts[3].trim()));
            }
        } catch (IOException e) {
            throw new BizException(ErrorCodes.INTERNAL_ERROR, "failed to load channel statements", e);
        }
        return statements;
    }
}
