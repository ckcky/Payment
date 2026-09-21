package com.payment.settlement.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 已确认事实闸门（ADR-0023）：本地逐条强制校验，替代「委托 reconciliation 计数」的隐式假设。
 *
 * <p>任一不满足 ⇒ 抛 {@link BizException}（reason 维度）并递增 {@code settlement.gate_rejected}，
 * 调用方据此**不落任何批次**（满足「未确认事实不得结算」的可运行时强制与不静默）。</p>
 *
 * <p>032/G4 + AC-3 商户校验：事实缺 {@code merchantId}（归属未知）⇒ 拒绝（未知归属不得结算）；
 * 归属他商户的事实**过滤出**结算口径（{@code settleableFacts(period, merchantId)} 商户维度），
 * 返回本商户的可结算事实，调用方据此计算净额与快照。</p>
 */
public final class ConfirmedFactGate {

    private static final Logger log = LoggerFactory.getLogger(ConfirmedFactGate.class);

    private ConfirmedFactGate() {
    }

    /**
     * 校验一批对账事实是否可结算，并返回本商户的可结算事实。
     *
     * @param summary           对账汇总（含 facts 与 period）
     * @param expectedCurrency  批次币种（MVP 仅 CNY）
     * @param requestPeriod     结算请求周期（须与对账周期一致，否则跨周期错配）
     * @param requestMerchantId 结算请求商户（AC-3 商户校验口径）
     * @param metrics           业务指标（失败计数）
     * @return 本商户的可结算事实（已滤除归属他商户的事实）
     */
    public static List<SettlementFact> gate(ReconciliationSummary summary, String expectedCurrency,
                                            String requestPeriod, String requestMerchantId,
                                            BusinessMetrics metrics) {
        if (!requestPeriod.equals(summary.period())) {
            reject("period_mismatch", metrics, "summary period %s != request %s",
                    summary.period(), requestPeriod);
        }
        int otherMerchant = 0;
        for (SettlementFact f : summary.facts()) {
            if (!"PAYMENT".equals(f.type()) && !"REFUND".equals(f.type())) {
                reject("unknown_fact_type", metrics, "fact %s type %s not in {PAYMENT,REFUND}",
                        f.reference(), f.type());
            }
            if (f.amountMinor() < 0) {
                reject("negative_amount", metrics, "fact %s negative amount %s", f.reference(), f.amountMinor());
            }
            if (!expectedCurrency.equals(f.currencyCode())) {
                reject("currency_mismatch", metrics, "fact %s currency %s != %s",
                        f.reference(), f.currencyCode(), expectedCurrency);
            }
            if (f.merchantId() == null || f.merchantId().isBlank()) {
                reject("merchant_missing", metrics, "fact %s has no merchantId", f.reference());
            }
            if (!Objects.equals(f.merchantId(), requestMerchantId)) {
                otherMerchant++;
            }
        }
        if (otherMerchant > 0) {
            // 归属他商户的事实不进本商户结算口径（settleableFacts(period, merchantId)），留痕不静默。
            metrics.counter("settlement.gate_filtered_other_merchant", otherMerchant, "module", "settlement");
            log.info("settlement gate filtered other-merchant facts: merchant={} filtered={}",
                    requestMerchantId, otherMerchant);
        }
        return summary.facts().stream()
                .filter(f -> Objects.equals(f.merchantId(), requestMerchantId))
                .toList();
    }

    private static void reject(String reason, BusinessMetrics metrics, String fmt, Object... args) {
        metrics.counter("settlement.gate_rejected", 1, "module", "settlement", "reason", reason);
        log.warn("settlement confirmed-fact gate rejected: reason={} detail={}", reason, String.format(fmt, args));
        throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "settlement gate rejected: " + reason);
    }
}
