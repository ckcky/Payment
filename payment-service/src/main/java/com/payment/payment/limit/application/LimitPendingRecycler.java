package com.payment.payment.limit.application;

import com.payment.common.core.observability.BusinessMetrics;
import com.payment.payment.limit.domain.LimitOperation;
import com.payment.payment.limit.domain.LimitOperationRepository;
import com.payment.payment.limit.infra.LimitProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 在途占用惰性回收（spec 027 / FR-038，ADR-0071 D13）。
 *
 * <p><b>触发时机</b>：<b>每次 RESERVE 判定之前</b>（见 {@code LimitReserveService} 的调用方）。
 * 不做定时扫描器、不做全表轮询——那是 D13 明确否决的方案（收益与复杂度不匹配）。</p>
 *
 * <p><b>为什么「惰性」就够</b>（D13 自愈性论证）：被挂住的只有<b>该用户自己</b>的额度。
 * 他下次发起支付时必然先回收（自愈）；他不再发起则这笔占用对他毫无影响。跨用户不受影响，
 * 因此不需要全局扫描。</p>
 *
 * <p><b>算法</b>：</p>
 * <ol>
 *   <li>查该用户未结算在途（{@code RESERVE} 且无终态流水，走 {@code idx_limitop_user_type}）；
 *       结果集天然极小（0~几条）。</li>
 *   <li>{@code MGET} 判存。</li>
 *   <li>Redis 中<b>缺失</b>的即已过期 → 逐个 {@code expire}（插 {@code EXPIRED} 流水 +
 *       {@code pending = GREATEST(0, pending - a)}）。</li>
 *   <li>Redis <b>不可用</b>（{@code alive} 返回 {@code null}）→ 记指标并<b>跳过回收</b>
 *       （保守占用，INV-9.2）——绝不当作「全部已过期」。</li>
 * </ol>
 *
 * <p><b>绝不反向修改 {@code payments.status}</b>（D11/L5）：本类只碰额度表。</p>
 */
@Service
public class LimitPendingRecycler {

    private static final Logger log = LoggerFactory.getLogger(LimitPendingRecycler.class);

    private final LimitOperationRepository operationRepository;
    private final LimitSettlementService settlementService;
    private final LimitExpiryIndex expiryIndex;
    private final LimitProperties properties;
    private final BusinessMetrics metrics;

    public LimitPendingRecycler(LimitOperationRepository operationRepository,
                                LimitSettlementService settlementService,
                                LimitExpiryIndex expiryIndex,
                                LimitProperties properties,
                                BusinessMetrics metrics) {
        this.operationRepository = operationRepository;
        this.settlementService = settlementService;
        this.expiryIndex = expiryIndex;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 回收该用户的过期在途占用。
     *
     * @param userId 用户
     * @return 实际回收（EXPIRED）的笔数；Redis 不可用时返回 0
     */
    public int recycle(String userId) {
        if (!properties.isEnabled() || userId == null || userId.isBlank()) {
            return 0;
        }
        List<LimitOperation> pending;
        try {
            pending = operationRepository.findUnsettledReserves(userId);
        } catch (RuntimeException e) {
            // 回收是「优化路径」，其自身故障绝不能阻断建单（FR-024 兼容性硬要求的同一条纪律）
            log.warn("查询未结算在途失败，跳过回收（保守占用）userId={} reason={}", userId, e.getMessage());
            metrics.counter("payment.limit_recycle_error", 1.0, "phase", "query");
            return 0;
        }
        if (pending.isEmpty()) {
            return 0;
        }

        List<String> paymentNos = new ArrayList<>(pending.size());
        for (LimitOperation op : pending) {
            paymentNos.add(op.bizNo());
        }
        Set<String> alive = expiryIndex.alive(paymentNos);
        if (alive == null) {
            // Redis 不可用：无法判定 → 保守占用（INV-9.2），不释放任何一笔
            metrics.counter("payment.limit_redis_unavailable", 1.0, "op", "alive");
            log.warn("额度过期索引不可用，跳过在途回收（保守占用，不拦截支付）userId={} count={}",
                    userId, pending.size());
            return 0;
        }

        int recycled = 0;
        List<String> expiredNos = new ArrayList<>();
        for (LimitOperation op : pending) {
            if (alive.contains(op.bizNo())) {
                continue;   // 仍在 TTL 内，保持占用
            }
            // 已过期：插 EXPIRED + 释放 pending。
            // 幂等由 settle 内部的 UK(biz_no, EXPIRED) 保证——补偿扫描与回收并发跑也不会重复释放。
            try {
                boolean settled = settlementService.expire(op.userId(), op.currencyCode(),
                        op.amountMinor(), op.bizNo());
                if (settled) {
                    recycled++;
                    expiredNos.add(op.bizNo());
                    metrics.counter("payment.limit_total", 1.0, "op", "EXPIRED",
                            "result", "ok", "period", op.period().name());
                }
            } catch (RuntimeException e) {
                // 单笔回收失败不拖垮其余（与 DemoDbTraceController 的 section 隔离同思路）
                log.warn("回收单笔在途占用失败，跳过 paymentNo={} reason={}", op.bizNo(), e.getMessage());
                metrics.counter("payment.limit_recycle_error", 1.0, "phase", "settle");
            }
        }
        if (recycled > 0) {
            metrics.counter("payment.limit_compensated", (double) recycled, "kind", "expired");
            log.info("惰性回收完成：释放过期在途占用 userId={} count={} paymentNos={}",
                    userId, recycled, expiredNos);
        }
        return recycled;
    }
}
