package com.payment.refund.infra.client;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.dto.rpc.AccountingEventRequest;
import com.payment.common.dto.rpc.AccountingEventResponse;
import com.payment.common.dto.rpc.AccountingEventType;
import com.payment.common.dto.rpc.AccountingSourceType;
import com.payment.refund.application.LedgerPostingGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 退款记账出站网关实现（Feature 005 / ADR-0018，031 事件化 / ADR-0077）：
 * 退款成功事实 → 经 Feign 同步 RPC 发 {@code REFUND} 事件；冲正分录（借应付商户 / 贷渠道应收）
 * 由账本按 §7.2 规则展开——本类不再持有科目常量、不再手工拼借贷分录。
 *
 * <p>记账失败**不回滚**退款成功事实（禁 2PC/XA），仅记录 {@code ledger.posting_failed}
 * 指标与告警日志，由重试/对账兜底（语义不变，ADR-0018）。</p>
 *
 * <p>幂等：不传幂等键，账本按 {@code REFUND:{refundNo}} 派生吸收重复（原则 10；
 * 030 前「REFUND: 前缀双口径」缺陷随契约内不再出现幂等键而根除）。
 * 费退还槽位（merchantFeeMinor/channelFeeMinor）MVP 传 null=0：Ledger 不猜是否退，
 * 仅当上游确认退才产生规则 2/3。</p>
 */
@Component
public class RefundFeignLedgerPostingGateway implements LedgerPostingGateway {

    private static final Logger log = LoggerFactory.getLogger(RefundFeignLedgerPostingGateway.class);
    private static final String MODULE = "refund";

    private final LedgerFeignClient ledgerClient;
    private final BusinessMetrics metrics;

    public RefundFeignLedgerPostingGateway(LedgerFeignClient ledgerClient, BusinessMetrics metrics) {
        this.ledgerClient = ledgerClient;
        this.metrics = metrics;
    }

    @Override
    public void postRefundCapture(RefundCaptureFacts facts) {
        if (facts.amountMinor() <= 0) {
            log.warn("跳过退款记账：金额为 0 或非正（账本要求分录金额 > 0），refundNo={}", facts.refundNo());
            return;
        }
        AccountingEventRequest request = new AccountingEventRequest(
                AccountingEventType.REFUND.name(), AccountingSourceType.REFUND.name(),
                facts.refundNo(), facts.currencyCode(),
                facts.amountMinor(), null, null,
                facts.merchantId(), facts.channelCode(),
                null, null, null, null, null, null, null);
        try {
            AccountingEventResponse response = ledgerClient.postEvent(request);
            metrics.counter("ledger.posting_succeeded", 1.0, "module", MODULE);
            log.info("REFUND 记账成功 refundNo={} merchantId={} channel={} postingId={} entries={}",
                    facts.refundNo(), facts.merchantId(), facts.channelCode(),
                    response.postingId(), response.entries().size());
        } catch (RuntimeException ex) {
            // 记账失败不回滚退款成功事实；记录待记账，交由重试/对账兜底（ADR-0018）
            metrics.counter("ledger.posting_failed", 1.0, "module", MODULE);
            log.error("退款记账失败，进入待记账兜底：refundNo={} reason={}",
                    facts.refundNo(), ex.getMessage());
        }
    }
}
