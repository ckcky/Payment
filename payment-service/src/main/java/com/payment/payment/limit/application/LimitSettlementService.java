package com.payment.payment.limit.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.id.BusinessNoType;
import com.payment.common.core.id.BusinessNos;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.common.core.observability.StructuredAuditLogger;
import com.payment.payment.limit.domain.LimitOperation;
import com.payment.payment.limit.domain.LimitOperationRepository;
import com.payment.payment.limit.domain.LimitOperationType;
import com.payment.payment.limit.domain.LimitPeriod;
import com.payment.payment.limit.domain.LimitUsage;
import com.payment.payment.limit.domain.LimitUsageRepository;
import com.payment.payment.limit.domain.UserPaymentLimit;
import com.payment.payment.limit.domain.UserPaymentLimitRepository;
import com.payment.payment.limit.infra.LimitProperties;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 额度结算（CONFIRM / RELEASE / EXPIRED）（spec 027 / FR-013~017，ADR-0071 D4/D6/D12）。
 *
 * <p><b>三道闸门</b>（D4）在这里落地第 2 道（幂等流水 UK）：每个操作先插
 * {@code limit_operations}，撞键即<b>跳过金额变更</b>。这挡住两类靠状态机挡不住的问题：</p>
 * <ol>
 *   <li>事务外的额度结算崩了（支付已 SUCCEEDED 但 used 未加）→ 补偿扫描重跑时必须不重复累加；</li>
 *   <li>UNKNOWN 收敛/回调重放导致同一笔被结算多次。</li>
 * </ol>
 *
 * <p><b>结算失败不回滚支付事实</b>（G4 / ADR-0009 哲学）：结算的调用点位于支付状态迁移<b>之后</b>，
 * 且走独立短事务（{@link Propagation#REQUIRES_NEW}）——支付成功是已发生的外部事实，
 * 不能因为记账/额度记账失败而回滚。残留的不一致由补偿扫描收敛。</p>
 */
@Service
public class LimitSettlementService {

    private static final Logger log = LoggerFactory.getLogger(LimitSettlementService.class);

    private final UserPaymentLimitRepository limitRepository;
    private final LimitUsageRepository usageRepository;
    private final LimitOperationRepository operationRepository;
    private final LimitExpiryIndex expiryIndex;
    private final LimitProperties properties;
    private final BusinessMetrics metrics;
    private final StructuredAuditLogger auditLogger;

    public LimitSettlementService(UserPaymentLimitRepository limitRepository,
                                  LimitUsageRepository usageRepository,
                                  LimitOperationRepository operationRepository,
                                  LimitExpiryIndex expiryIndex,
                                  LimitProperties properties,
                                  BusinessMetrics metrics,
                                  StructuredAuditLogger auditLogger) {
        this.limitRepository = limitRepository;
        this.usageRepository = usageRepository;
        this.operationRepository = operationRepository;
        this.expiryIndex = expiryIndex;
        this.properties = properties;
        this.metrics = metrics;
        this.auditLogger = auditLogger;
    }

    /**
     * 确认一笔支付（SUCCEEDED）：{@code used += a}、{@code pending -= a}。
     *
     * <p><b>无条件累加</b>（D12 软超限）：即使 {@code used > limit} 也<b>不拒绝、不 clamp、
     * 不回滚</b>。典型触发：TTL 已释放后支付才成功；限额被调低而当日已付超额。
     * 处理不是消除偏差而是让它可见——产 {@code payment_limit_overrun} 指标 +
     * {@code limit.overrun} 审计。</p>
     *
     * @return {@code true} = 本次真正结算（首次）；{@code false} = 幂等跳过（已有 CONFIRM）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean confirm(String userId, String currencyCode, long amountMinor, String paymentNo) {
        return settle(LimitOperationType.CONFIRM, userId, currencyCode, amountMinor, paymentNo, null);
    }

    /**
     * 释放一笔在途（FAILED / CLOSED）：{@code pending = GREATEST(0, pending - a)}。
     *
     * <p>{@code GREATEST(0, …)} 是下限保护（INV-7）：即使前面所有防线都被绕过，
     * 也绝不让 {@code pending} 变成负数把额度撑大。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean release(String userId, String currencyCode, long amountMinor, String paymentNo) {
        return settle(LimitOperationType.RELEASE, userId, currencyCode, amountMinor, paymentNo, null);
    }

    /**
     * 过期释放（TTL 到期，FR-038 / D11）：金额效果同 RELEASE，语义不同。
     *
     * <p><b>绝不反向修改 {@code payments.status}</b>（L5「不猜成败」）：本方法只碰额度表，
     * 调用方不得据此改支付状态。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expire(String userId, String currencyCode, long amountMinor, String paymentNo) {
        return settle(LimitOperationType.EXPIRED, userId, currencyCode, amountMinor, paymentNo, null);
    }

    /**
     * 结算主流程：对三周期各做一次「幂等插入 + 金额变更」。
     *
     * <p><b>三个周期都要结算</b>——与预占的「任一超限即全拒」不同：预占时只要有一个周期放不下
     * 就整笔不创建（三周期都没被扣），故已发生的预占必然三周期都有；结算时三周期都必须归位。</p>
     */
    private boolean settle(LimitOperationType opType, String userId, String currencyCode,
                           long amountMinor, String paymentNo, Instant expiresAt) {
        if (!properties.isEnabled()) {
            return false;
        }
        Optional<UserPaymentLimit> maybeLimit = limitRepository.find(userId, currencyCode);
        if (maybeLimit.isEmpty() || !maybeLimit.get().hasAnyLimit()) {
            // 无配置（或全 0）：不限额，也就不存在预占需要结算。
            // 注意：这里直接返回是安全的，因为「无配置」在预占时同样跳过，
            // 不存在「预占了却没配置可结」的窗口（配置被删除时 pending 会残留，
            // 但删除配置本身即运维宣告「此用户不再受限」，残留 pending 不影响判定——
            // isLimited() 为 false 时判定路径根本不会读 pending）。
            return false;
        }
        UserPaymentLimit limit = maybeLimit.get();
        boolean anySettled = false;
        for (LimitPeriod period : LimitPeriod.values()) {
            if (!limit.isLimited(period)) {
                continue;
            }
            if (settleOne(opType, userId, currencyCode, amountMinor, paymentNo, period,
                    limit.limitOf(period), expiresAt)) {
                anySettled = true;
            }
        }
        return anySettled;
    }

    /** 单周期结算：幂等插入 → 金额变更 → 指标 / 软超限留痕。 */
    private boolean settleOne(LimitOperationType opType, String userId, String currencyCode,
                              long amountMinor, String paymentNo, LimitPeriod period,
                              long periodLimit, Instant expiresAt) {
        // 闸门 #2：同一 (paymentNo, opType) 只允许一条流水
        boolean firstTime = operationRepository.insert(new LimitOperation(null,
                BusinessNos.of(BusinessNoType.LIMIT_OP), paymentNo, opType,
                userId, currencyCode, period, amountMinor, expiresAt, Instant.now()));
        if (!firstTime) {
            metrics.counter("payment.limit_total", 1.0, "op", opType.name(),
                    "result", "duplicate", "period", period.name());
            log.debug("额度过期/结算流水已存在，跳过金额变更 op={} paymentNo={} period={}",
                    opType, paymentNo, period);
            return false;
        }

        int affected = switch (opType) {
            case CONFIRM -> usageRepository.confirm(userId, currencyCode, period, amountMinor);
            case RELEASE, EXPIRED -> usageRepository.release(userId, currencyCode, period, amountMinor);
            case RESERVE -> 0;   // RESERVE 不走本路径（见 LimitReserveService）
        };
        if (affected == 0) {
            // 行不存在：该周期从未建过行（理论上预占必建行）。流水已插入，重跑不会再补——
            // 这是有意的：金额未发生，重跑也不该凭空造出占用。记指标供排查。
            metrics.counter("payment.limit_settle_no_row", 1.0, "op", opType.name(),
                    "period", period.name());
            log.warn("额度结算无对应占用行（预占应当已建行）op={} paymentNo={} user={} period={}",
                    opType, paymentNo, userId, period);
            return true;
        }

        metrics.counter("payment.limit_total", 1.0, "op", opType.name(), "result", "ok",
                "period", period.name());
        expiryIndex.clear(paymentNo);

        if (opType == LimitOperationType.CONFIRM) {
            // 软超限可见化（FR-039 / D12）：不消除偏差，只让它可见
            long overrun = usageRepository.find(userId, currencyCode, period)
                    .map(u -> u.overrunMinor(periodLimit))
                    .orElse(0L);
            if (overrun > 0) {
                metrics.counter("payment.limit_overrun", 1.0, "period", period.name());
                auditLogger.audit("limit.overrun", paymentNo, amountMinor, currencyCode,
                        String.valueOf(periodLimit), String.valueOf(overrun), "limit", userId);
                log.warn("额度软超限：支付成功的事实必须如实累加，不拒绝不回滚 paymentNo={} user={}"
                        + " period={} limit={} overrun={}", paymentNo, userId, period, periodLimit, overrun);
            }
        }
        return true;
    }

    /**
     * 按支付单号推断用户与币种后结算（补偿扫描用）。
     *
     * <p>补偿扫描以 <b>payment 为事实源</b>（D4 第 3 条）：扫到「payment 已终态但只有 RESERVE」
     * 时，用它自己的 userId / currencyCode / amountMinor 补齐结算。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean settleByPayment(String paymentNo, String userId, String currencyCode,
                                   long amountMinor, boolean succeeded) {
        return succeeded
                ? confirm(userId, currencyCode, amountMinor, paymentNo)
                : release(userId, currencyCode, amountMinor, paymentNo);
    }

    /** 该支付单是否已结算（补偿扫描判定；跳过 UNKNOWN 由调用方负责）。 */
    public boolean isSettled(String paymentNo) {
        // 按「单据维度」判定（period=null = 任一周期命中即算）：
        // 结算是一次覆盖三周期的动作，三周期流水同时写入；只要存在任一终态流水，
        // 该支付单就不该再被补偿扫描重复处理（FR-016）。
        return operationRepository.exists(paymentNo, LimitOperationType.CONFIRM, null)
                || operationRepository.exists(paymentNo, LimitOperationType.RELEASE, null)
                || operationRepository.exists(paymentNo, LimitOperationType.EXPIRED, null);
    }

    /** 读某周期占用（查询接口用）。 */
    public Optional<LimitUsage> usage(String userId, String currencyCode, LimitPeriod period) {
        return usageRepository.find(userId, currencyCode, period);
    }

    /** 读该用户全部周期占用（查询接口用）。 */
    public List<LimitUsage> usages(String userId, String currencyCode) {
        return usageRepository.findAll(userId, currencyCode);
    }

    /** 参数校验（内部端点共用）。 */
    public static void requireUserAndCurrency(String userId, String currencyCode) {
        if (userId == null || userId.isBlank()) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "userId is required");
        }
        if (currencyCode == null || currencyCode.isBlank()) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "currencyCode is required");
        }
    }
}
