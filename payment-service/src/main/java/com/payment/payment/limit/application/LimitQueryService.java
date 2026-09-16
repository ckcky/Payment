package com.payment.payment.limit.application;

import com.payment.payment.limit.domain.LimitOperation;
import com.payment.payment.limit.domain.LimitOperationRepository;
import com.payment.payment.limit.domain.LimitPeriod;
import com.payment.payment.limit.domain.LimitUsage;
import com.payment.payment.limit.domain.UserPaymentLimit;
import com.payment.payment.limit.domain.UserPaymentLimitRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 额度查询（spec 027 / FR-020，ADR-0071 D10）。
 *
 * <p>对内部端点提供「三周期 limit / used / pending / available + overrun 标记 + 最近流水」。
 * 周期边界<b>现算</b>（D10），不读定时任务维护的表。</p>
 */
@Service
public class LimitQueryService {

    private final UserPaymentLimitRepository limitRepository;
    private final LimitSettlementService settlementService;
    private final LimitOperationRepository operationRepository;

    public LimitQueryService(UserPaymentLimitRepository limitRepository,
                             LimitSettlementService settlementService,
                             LimitOperationRepository operationRepository) {
        this.limitRepository = limitRepository;
        this.settlementService = settlementService;
        this.operationRepository = operationRepository;
    }

    /**
     * 组装查询快照。
     *
     * @param userId       用户
     * @param currencyCode 币种
     * @param atUtc        判定时刻（UTC）；周期起点据此现算
     * @return 快照：配置（可能为空）+ 三周期明细 + 汇总
     */
    @Transactional(readOnly = true)
    public LimitSnapshot snapshot(String userId, String currencyCode, Instant atUtc) {
        Optional<UserPaymentLimit> maybeLimit = limitRepository.find(userId, currencyCode);
        boolean configured = maybeLimit.isPresent()
                && UserPaymentLimit.STATUS_ACTIVE.equalsIgnoreCase(maybeLimit.get().status());

        List<Map<String, Object>> periods = new ArrayList<>();
        boolean anyLimited = maybeLimit.isPresent() && maybeLimit.get().hasAnyLimit();
        for (LimitPeriod period : LimitPeriod.values()) {
            long limitMinor = maybeLimit.map(l -> l.limitOf(period)).orElse(0L);
            LimitUsage usage = settlementService.usage(userId, currencyCode, period)
                    .orElseGet(() -> LimitUsage.empty(userId, currencyCode, period,
                            period.periodStart(atUtc)));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("period", period.name());
            row.put("periodStart", period.periodStart(atUtc).toString());
            row.put("periodEnd", period.periodEnd(atUtc).toString());
            row.put("limit", limitMinor);
            row.put("used", usage.usedMinor());
            row.put("pending", usage.pendingMinor());
            row.put("occupied", usage.occupiedMinor());
            // 0 或 DISABLED = 不限：available 无意义，返回 null 让前端显示「不限」
            boolean limited = configured && limitMinor > 0;
            row.put("limited", limited);
            row.put("available", limited ? usage.availableMinor(limitMinor) : null);
            row.put("overrun", limited ? usage.overrunMinor(limitMinor) : 0L);
            periods.add(row);
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("configured", maybeLimit.isPresent());
        config.put("status", maybeLimit.map(UserPaymentLimit::status).orElse(null));
        config.put("anyLimit", anyLimited);

        return new LimitSnapshot(userId, currencyCode, atUtc, config, periods);
    }

    /** 某支付单的额度流水（排障用）。 */
    @Transactional(readOnly = true)
    public List<LimitOperation> operations(String paymentNo) {
        return operationRepository.findByBizNo(paymentNo);
    }

    /** 查询快照（供 Controller 直接序列化）。 */
    public record LimitSnapshot(String userId, String currencyCode, Instant asOf,
                                Map<String, Object> config, List<Map<String, Object>> periods) {
    }
}
