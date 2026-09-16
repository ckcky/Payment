package com.payment.payment.limit.application;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import com.payment.common.core.id.BusinessNoType;
import com.payment.common.core.id.BusinessNos;
import com.payment.common.core.observability.BusinessMetrics;
import com.payment.payment.limit.domain.LimitExceededException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 额度预占（spec 027 / FR-006~010，ADR-0071 D3/D5/D6）。
 *
 * <p><b>核心纪律</b>：</p>
 * <ol>
 *   <li><b>判定走 DB 单条原子 UPDATE</b>——{@code UPDATE ... WHERE used + pending + a <= limit}，
 *       0 行即超限。不用分布式锁，不用 Redis 判定。</li>
 *   <li><b>三个周期各自独立判定</b>（FR-010）：任一超限即整笔拒绝，
 *       未超限的周期<b>不得部分扣减</b>。故失败的周期已加的 {@code pending} 必须被回滚/释放。</li>
 *   <li><b>金额一律取支付单金额</b>（INV-6）：调用方传 {@code payment.amountMinor()}，
 *       绝不用渠道回传的实付额。</li>
 * </ol>
 *
 * <p><b>事务边界（INV-3）</b>：预占运行在<b>调用方的事务内</b>（{@link Propagation#REQUIRED}），
 * 与建单同生共死——超限时整笔建单事务回滚，{@code payments} 表不落行。这是「未创建」而非
 * 「创建了再拒」的实现基础。</p>
 */
@Service
public class LimitReserveService {

    private static final Logger log = LoggerFactory.getLogger(LimitReserveService.class);

    private final UserPaymentLimitRepository limitRepository;
    private final LimitUsageRepository usageRepository;
    private final LimitOperationRepository operationRepository;
    private final LimitExpiryIndex expiryIndex;
    private final LimitProperties properties;
    private final BusinessMetrics metrics;

    public LimitReserveService(UserPaymentLimitRepository limitRepository,
                               LimitUsageRepository usageRepository,
                               LimitOperationRepository operationRepository,
                               LimitExpiryIndex expiryIndex,
                               LimitProperties properties,
                               BusinessMetrics metrics) {
        this.limitRepository = limitRepository;
        this.usageRepository = usageRepository;
        this.operationRepository = operationRepository;
        this.expiryIndex = expiryIndex;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 建单前预占（FR-006）。三周期依次判定；任一超限抛 {@link LimitExceededException}
     * 使调用方事务回滚。
     *
     * @param userId       用户
     * @param currencyCode 币种
     * @param amountMinor  金额（分）＝支付单金额（INV-6）
     * @param paymentNo    支付单号（流水 biz_no，ADR-0063）
     * @return 是否发生了预占（{@code false} = 无配置 / 开关关闭 / 三周期都不限，属正常路径）
     * @throws LimitExceededException 任一周期放不下本笔金额
     */
    @Transactional
    public boolean reserve(String userId, String currencyCode, long amountMinor, String paymentNo) {
        if (!properties.isEnabled()) {
            return false;
        }
        Optional<UserPaymentLimit> maybeLimit = limitRepository.find(userId, currencyCode);
        if (maybeLimit.isEmpty() || !maybeLimit.get().hasAnyLimit()) {
            // FR-012 / FR-024：无配置行 = 不限额；全 0 视同无配置。这是兼容性的硬要求。
            return false;
        }
        UserPaymentLimit limit = maybeLimit.get();
        Instant now = Instant.now();

        // 已成功预占的周期，用于「后一个周期超限」时释放（FR-010：不得部分扣减）
        List<ReservedPeriod> reserved = new ArrayList<>();

        for (LimitPeriod period : LimitPeriod.values()) {
            if (!limit.isLimited(period)) {
                continue;   // 该周期不限（0 或 DISABLED）
            }
            long periodLimit = limit.limitOf(period);
            boolean ok = tryReserveOne(userId, currencyCode, paymentNo, amountMinor,
                    period, periodLimit, now);
            if (!ok) {
                // 任一周期超限 → 整笔拒绝。已预占的周期必须释放，否则调用方事务回滚后
                // 这些 pending 会随事务一起消失（同事务），但显式释放让语义不依赖事务传播行为
                // （例如调用方将来把预占挪到 REQUIRES_NEW 时不会静默残留）。
                releaseReserved(userId, currencyCode, paymentNo, reserved);
                metrics.counter("payment.limit_exceeded", 1.0, "period", period.name());
                throw new LimitExceededException(userId, period, periodLimit,
                        occupiedOf(userId, currencyCode, period), amountMinor);
            }
            reserved.add(new ReservedPeriod(period, amountMinor));
        }

        if (!reserved.isEmpty()) {
            metrics.counter("payment.limit_reserve", 1.0, "result", "ok", "periods",
                    String.valueOf(reserved.size()));
        }
        return !reserved.isEmpty();
    }

    /**
     * 单周期原子预占：建行 → 原子 UPDATE → 幂等流水 + Redis 标记。
     *
     * @return {@code true} = 该周期预占成功
     */
    private boolean tryReserveOne(String userId, String currencyCode, String paymentNo,
                                  long amountMinor, LimitPeriod period, long periodLimit, Instant now) {
        // 幂等闸门 #2（INV-4）：同一 (paymentNo, RESERVE) 只允许一条流水。
        // 已存在说明这笔支付单之前就预占过（重放/补偿），直接视为成功、不重复加金额。
        boolean firstTime = operationRepository.insert(new LimitOperation(null,
                BusinessNos.of(BusinessNoType.LIMIT_OP), paymentNo, LimitOperationType.RESERVE,
                userId, currencyCode, period, amountMinor,
                now.plus(properties.getReserveTtl()), now));
        if (!firstTime) {
            metrics.counter("payment.limit_total", 1.0, "op", "RESERVE", "result", "duplicate",
                    "period", period.name());
            return true;
        }

        // 建行（首次）后原子预占；periodStart 现算（D10）——跨周期时旧行不匹配 → 0 行 → 重试一次
        usageRepository.ensureRow(userId, currencyCode, period, period.periodStart(now));
        int affected = usageRepository.reserveIfWithinLimit(userId, currencyCode, period,
                amountMinor, periodLimit);
        if (affected == 0) {
            // 可能是「真超限」，也可能是「行还是旧周期的」——补一次 ensureRow 后重判。
            // 重判仍 0 行时，区分二者：占用是否已达限额。
            usageRepository.ensureRow(userId, currencyCode, period, period.periodStart(Instant.now()));
            affected = usageRepository.reserveIfWithinLimit(userId, currencyCode, period,
                    amountMinor, periodLimit);
        }
        if (affected == 0) {
            // 预占失败：把刚插入的流水删掉？不删——流水是幂等闸门，删了会让重试路径重复插入。
            // 但必须让「下次同 paymentNo 再来」能被正确重判：故改为写入失败语义的标记。
            // 实际做法：删除该条 RESERVE 流水，保持「未预占」状态一致（该 paymentNo 未建单，
            // 不存在被重复引用的风险——建单事务即将回滚，paymentNo 会被丢弃）。
            rollbackReserveOperation(paymentNo, period);
            metrics.counter("payment.limit_total", 1.0, "op", "RESERVE", "result", "exceeded",
                    "period", period.name());
            return false;
        }

        metrics.counter("payment.limit_total", 1.0, "op", "RESERVE", "result", "ok",
                "period", period.name());
        // 写 Redis 在途标记（FR-036）：失败仅记指标，不影响建单
        expiryIndex.mark(paymentNo, amountMinor, properties.getReserveTtl());
        return true;
    }

    /**
     * 预占失败时清理刚插入的 RESERVE 流水。
     *
     * <p>为什么可以删：该 {@code paymentNo} 的建单事务即将整体回滚，这个单号不会被任何
     * 已提交数据引用（支付单未落库）。若不删，同一单号在重试时会被幂等闸门判定为
     * 「已预占」而<b>放行一笔实际未预占的支付</b>——那是限额静默失效。</p>
     */
    private void rollbackReserveOperation(String paymentNo, LimitPeriod period) {
        try {
            operationRepository.find(paymentNo, LimitOperationType.RESERVE, period)
                    .ifPresent(op -> operationRepository.delete(op));
        } catch (RuntimeException e) {
            // 清理失败不掩盖原始超限语义；建单事务回滚后该单号会作废
            log.warn("清理失败的 RESERVE 流水异常（建单事务将回滚，无正确性影响）paymentNo={} period={}",
                    paymentNo, period);
        }
    }

    /** 释放本次请求已成功预占的周期（FR-010：不得部分扣减）。 */
    private void releaseReserved(String userId, String currencyCode, String paymentNo,
                                 List<ReservedPeriod> reserved) {
        for (ReservedPeriod r : reserved) {
            usageRepository.release(userId, currencyCode, r.period(), r.amountMinor());
            // 只清该周期的 RESERVE 流水——UK 已带 period，按周期精确删除才与插入对称
            operationRepository.find(paymentNo, LimitOperationType.RESERVE, r.period())
                    .ifPresent(operationRepository::delete);
            metrics.counter("payment.limit_total", 1.0, "op", "RESERVE", "result", "rolled_back",
                    "period", r.period().name());
        }
        // 三周期都回滚后该单号已无任何在途，清掉 Redis 标记
        expiryIndex.clear(paymentNo);
    }

    /** 取某周期的当前占用（构造错误消息用；此时预占已失败，读到的即真实占用）。 */
    private long occupiedOf(String userId, String currencyCode, LimitPeriod period) {
        return usageRepository.find(userId, currencyCode, period)
                .map(LimitUsage::occupiedMinor)
                .orElse(0L);
    }

    private record ReservedPeriod(LimitPeriod period, long amountMinor) {
    }

    /**
     * 校验用户 + 币种参数非空（内部端点与建单路径共用）。
     */
    public static void requireUserAndCurrency(String userId, String currencyCode) {
        if (userId == null || userId.isBlank()) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "userId is required");
        }
        if (currencyCode == null || currencyCode.isBlank()) {
            throw BizException.of(ErrorCodes.INVALID_ARGUMENT, "currencyCode is required");
        }
    }
}
