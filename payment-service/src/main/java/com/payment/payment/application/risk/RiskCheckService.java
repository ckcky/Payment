package com.payment.payment.application.risk;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.payment.domain.Payment;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 最小风控规则（ADR-0028 / FR-006）：<b>只观测，不拦截</b>。
 *
 * <p>Phase 9 的风控目标是先把「规则该挂在哪、命中后该怎么记录」的骨架立起来，而不是引入规则引擎。
 * 因此 MVP 只有两条可配阈值：单笔金额上限与窗口内笔数上限。命中的后果<b>仅是</b>打一个
 * {@code payment.risk_triggered} 指标和一条审计日志，<b>绝不改变资金主流程</b>——
 * 支付照常受理、照常扣款、照常记账。</p>
 *
 * <p>默认 {@code payment.risk.enabled=false}（本地与测试全放行）。窗口计数是<b>进程内</b>的，
 * 多实例部署下不精确，属于刻意的简化（真实风控需要独立的计数服务与 Redis；见 ADR-0028 后续演进）。</p>
 */
@Service
public class RiskCheckService {

    private static final Logger LOG = LoggerFactory.getLogger(RiskCheckService.class);
    private static final String MODULE = "payment";
    private static final long WINDOW_MS = 60_000L;

    private final boolean enabled;
    private final long singleMaxAmountMinor;
    private final int windowLimitCount;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    private final Object windowLock = new Object();
    private volatile long windowStart = System.currentTimeMillis();
    private volatile int windowCount;

    public RiskCheckService(@Value("${payment.risk.enabled:false}") boolean enabled,
                            @Value("${payment.risk.single-max-amount-minor:0}") long singleMaxAmountMinor,
                            @Value("${payment.risk.window-limit-count:0}") int windowLimitCount,
                            BusinessMetrics metrics,
                            StructuredAuditLogger auditLogger) {
        this.enabled = enabled;
        this.singleMaxAmountMinor = singleMaxAmountMinor;
        this.windowLimitCount = windowLimitCount;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    /**
     * 对一笔新受理的支付做规则检查；命中只记录，不抛异常、不改变控制流。
     *
     * @return 命中的规则名列表（未启用或全部未命中则为空）
     */
    public List<String> onPaymentCreated(Payment payment) {
        if (!enabled) {
            return List.of();
        }
        List<String> hits = new ArrayList<>();
        if (singleMaxAmountMinor > 0 && payment.getAmountMinor() > singleMaxAmountMinor) {
            hits.add("SINGLE_MAX_AMOUNT");
        }
        if (windowLimitCount > 0 && nextWindowCount() > windowLimitCount) {
            hits.add("WINDOW_LIMIT_COUNT");
        }
        if (hits.isEmpty()) {
            return List.of();
        }
        String rule = String.join(",", hits);
        metrics.counter("payment.risk_triggered", 1.0, "module", MODULE, "rule", rule);
        auditLogger.audit("payment.risk_triggered", payment.getIdempotencyKey(), payment.getAmountMinor(),
                payment.getCurrencyCode(), null, payment.getStatus().name(), "payment",
                String.valueOf(payment.getId()));
        LOG.warn("risk rule triggered: rule={} paymentId={} amountMinor={}", rule, payment.getId(),
                payment.getAmountMinor());
        return List.copyOf(hits);
    }

    /** 滑动（实为固定）窗口计数：跨窗口归零；多实例不共享，见类注释。 */
    private int nextWindowCount() {
        long now = System.currentTimeMillis();
        synchronized (windowLock) {
            if (now - windowStart >= WINDOW_MS) {
                windowStart = now;
                windowCount = 0;
            }
            return ++windowCount;
        }
    }
}
