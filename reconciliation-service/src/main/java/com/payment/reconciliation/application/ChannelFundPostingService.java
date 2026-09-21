package com.payment.reconciliation.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.common.dto.rpc.AccountingEventRequest;
import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.common.dto.rpc.AccountingSourceType;
import com.payment.reconciliation.domain.ChannelFundFact;
import com.payment.reconciliation.domain.ReconciliationMatching;
import com.payment.reconciliation.statement.StatementImport;
import com.payment.reconciliation.statement.StatementImportRepository;
import com.payment.reconciliation.statement.TypedStatementLine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 渠道资金事实入账编排（spec 032 §10.2，G3）：以最新 NORMALIZED 账单为外部事实来源，
 * 聚合「实收净额 / 手续费」并发 CHANNEL_SETTLEMENT / CHANNEL_FEE 事件到 ledger。
 *
 * <p>双计防线（plan §2.6）：①{@code sourceId} 由「渠道+周期」确定性派生
 * （{@code CS-{channel}-{period}} / {@code CF-{channel}-{period}}），同一账单更正重放经
 * ledger 幂等键 {@code {eventType}:{sourceId}} 天然吸收；②渠道费唯一合法来源 = FEE 类型账单行；
 * ③PERIOD_CLOSED（031 §11）原样上抛，更正走下一期间 ADJUSTMENT，绝不就地补记。</p>
 */
@Service
public class ChannelFundPostingService {

    private static final Logger log = LoggerFactory.getLogger(ChannelFundPostingService.class);

    private final StatementImportRepository importRepository;
    private final FundPostingGateway fundPostingGateway;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    public ChannelFundPostingService(StatementImportRepository importRepository,
                                     FundPostingGateway fundPostingGateway,
                                     BusinessMetrics metrics,
                                     StructuredAuditLogger auditLogger) {
        this.importRepository = importRepository;
        this.fundPostingGateway = fundPostingGateway;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    /** 入账结果（可查可重放）。 */
    public record PostingOutcome(ChannelFundFact fact, List<String> postedEvents) {
    }

    /**
     * 触发渠道资金事实入账（幂等）：零净额跳过（不产空事件）；
     * 无可用账单 ⇒ 400 STATEMENT_UNAVAILABLE（§11 #1，与 run 同一口径）。
     */
    @Transactional
    public PostingOutcome postChannelFundFacts(String channelCode, String period) {
        StatementImport imprt = importRepository.findLatestNormalized(period, channelCode)
                .orElseThrow(() -> {
                    metrics.counter("reconciliation.statement_unavailable", 1,
                            "channel", channelCode, "period", period);
                    return BizException.of(ErrorCodes.STATEMENT_UNAVAILABLE,
                            "no normalized statement import for channel " + channelCode
                                    + " period " + period + "; upload a statement first");
                });

        List<ReconciliationMatching.StatementLineView> lines = importRepository.findLines(imprt.getId())
                .stream().map(TypedStatementLine::new).map(l -> (ReconciliationMatching.StatementLineView) l).toList();
        ChannelFundFact fact = ChannelFundFact.aggregate(channelCode, period, lines);

        List<String> posted = new ArrayList<>();
        if (fact.netReceivedMinor() != 0) {
            String sourceId = "CS-" + channelCode + "-" + period;
            post(sourceId, AccountingEventType.CHANNEL_SETTLEMENT, channelCode, period,
                    fact.currency(), fact.netReceivedMinor());
            posted.add("CHANNEL_SETTLEMENT:" + sourceId);
        } else {
            log.info("channel fund settlement skipped (zero net): channel={} period={}", channelCode, period);
        }
        if (fact.channelFeeMinor() != 0) {
            String sourceId = "CF-" + channelCode + "-" + period;
            post(sourceId, AccountingEventType.CHANNEL_FEE, channelCode, period,
                    fact.currency(), fact.channelFeeMinor());
            posted.add("CHANNEL_FEE:" + sourceId);
        } else {
            log.info("channel fund fee skipped (zero fee): channel={} period={}", channelCode, period);
        }
        return new PostingOutcome(fact, List.copyOf(posted));
    }

    private void post(String sourceId, AccountingEventType eventType, String channelCode, String period,
                      String currency, long amountMinor) {
        // 槽位：CHANNEL_SETTLEMENT 走 netAmountMinor（带符号实付净额）；CHANNEL_FEE 走 amountMinor。
        AccountingEventRequest request = eventType == AccountingEventType.CHANNEL_SETTLEMENT
                ? new AccountingEventRequest(eventType.name(), AccountingSourceType.RECONCILIATION.name(),
                sourceId, currency, null, null, null, null, channelCode, amountMinor,
                null, null, null, null, null, null)
                : new AccountingEventRequest(eventType.name(), AccountingSourceType.RECONCILIATION.name(),
                sourceId, currency, null, null, null, null, channelCode, null,
                null, null, null, amountMinor, null, null);
        fundPostingGateway.postEvent(request);
        metrics.counter("ledger.channel_fund_posting", 1, "channel", channelCode, "eventType", eventType.name());
        auditLogger.audit("channel_fund_post", sourceId, amountMinor, currency, "AGGREGATED", "POSTED",
                "reconciliation", eventType.name() + ":" + channelCode + ":" + period);
    }
}
