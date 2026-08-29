package com.payment.settlement.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.observability.BusinessMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 已确认事实闸门（ADR-0023）：本地逐条强制校验，替代「委托 reconciliation 计数」的隐式假设。
 *
 * <p>任一不满足 ⇒ 抛 {@link BizException}（reason 维度）并递增 {@code settlement.gate_rejected}，
 * 调用方据此**不落任何批次**（满足「未确认事实不得结算」的可运行时强制与不静默）。</p>
 */
public final class ConfirmedFactGate {

    private static final Logger log = LoggerFactory.getLogger(ConfirmedFactGate.class);

    private ConfirmedFactGate() {
    }

    /**
     * 校验一批对账事实是否可结算。
     *
     * @param summary           对账汇总（含 facts 与 period）
     * @param expectedCurrency  批次币种（MVP 仅 CNY）
     * @param requestPeriod     结算请求周期（须与对账周期一致，否则跨周期错配）
     * @param metrics           业务指标（失败计数）
     */
    public static void gate(ReconciliationSummary summary, String expectedCurrency, String requestPeriod,
                            BusinessMetrics metrics) {
        if (!requestPeriod.equals(summary.period())) {
            reject("period_mismatch", metrics, "summary period %s != request %s",
                    summary.period(), requestPeriod);
        }
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
        }
    }

    private static void reject(String reason, BusinessMetrics metrics, String fmt, Object... args) {
        metrics.counter("settlement.gate_rejected", 1, "module", "settlement", "reason", reason);
        log.warn("settlement confirmed-fact gate rejected: reason={} detail={}", reason, String.format(fmt, args));
        throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "settlement gate rejected: " + reason);
    }
}
