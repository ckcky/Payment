package com.payment.catalog.application;

import com.payment.catalog.domain.ReservationStatus;
import com.payment.catalog.domain.Stock;
import com.payment.catalog.domain.StockReservation;
import com.payment.catalog.domain.StockRepository;
import com.payment.catalog.domain.StockReservationRepository;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 库存应用服务（013）：实现「下单预占 → 支付成功确认扣减 → 失败/超时释放」三段式库存控制。
 *
 * <p>幂等保证：
 * <ul>
 *   <li>预占幂等于 {@code reservationId}（订单维度）；重复预占且仍 PENDING 直接吸收。</li>
 *   <li>确认幂等于 {@code deductId}（支付单号）；重复确认直接吸收。</li>
 *   <li>释放幂等；已确认的预占不可回滚（退款属另一期范围）。</li>
 * </ul>
 * 不变量 {@code total = available + reserved + sold} 由领域层强制。
 *
 * <p>热点 SKU 的乐观锁版本冲突（{@link ErrorCodes#CONCURRENT_UPDATE}）采用<b>跨事务有界重试</b>：
 * 每次尝试都是独立事务（TransactionTemplate）。注意不能在同一个 @Transactional 事务内重试——
 * InnoDB REPEATABLE READ 下重读拿到的仍是本事务快照，永远看不见他人提交的新版本
 * （2026-09-04 压测踩坑：同事务重试 5 次全部复用旧版本，冲突率不降反升）。</p>
 */
@Service
public class StockApplicationService {

    /** 乐观锁冲突重试上限与退避：热点 SKU 单行写竞争下快速重放（2026-09-04 压测：20VU 冲突率 ~34%）。 */
    private static final int MAX_RETRIES = 8;
    private static final long RETRY_BACKOFF_MILLIS = 10;

    private final StockRepository stockRepository;
    private final StockReservationRepository reservationRepository;
    private final TransactionTemplate tx;

    public StockApplicationService(StockRepository stockRepository, StockReservationRepository reservationRepository,
                                   PlatformTransactionManager transactionManager) {
        this.stockRepository = stockRepository;
        this.reservationRepository = reservationRepository;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** 初始化/补足库存（演示与测试用）：若 SKU 尚无库存记录则创建 total 条可用库存。 */
    @Transactional
    public void seedStock(Long skuId, long total) {
        if (stockRepository.findBySkuId(skuId).isPresent()) {
            return;
        }
        stockRepository.save(new Stock(skuId, total));
    }

    /** 下单预占：幂等于 reservationId。库存不足抛 CONFLICT（→ HTTP 409）。 */
    public void reserve(String reservationId, Long skuId, long quantity) {
        retryOnVersionConflict(() -> doReserve(reservationId, skuId, quantity));
    }

    private void doReserve(String reservationId, Long skuId, long quantity) {
        tx.executeWithoutResult(status -> {
            Optional<StockReservation> existing = reservationRepository.findById(reservationId);
            if (existing.isPresent()) {
                StockReservation r = existing.get();
                if (r.getStatus() == ReservationStatus.PENDING) {
                    return; // 幂等：同一订单维度的重复预占直接吸收
                }
                if (r.getStatus() == ReservationStatus.RELEASED) {
                    reservationRepository.delete(r); // 允许重新预占（之前已释放）
                } else {
                    throw BizException.of(ErrorCodes.CONFLICT, "reservation already confirmed: " + reservationId);
                }
            }
            mutateStock(skuId, quantity, Stock::reserve);
            reservationRepository.save(new StockReservation(reservationId, skuId, quantity, ReservationStatus.PENDING, null));
        });
    }

    /** 支付成功确认扣减：幂等于 deductId。 */
    public void confirm(String reservationId, Long skuId, long quantity, String deductId) {
        retryOnVersionConflict(() -> tx.executeWithoutResult(status -> {
            StockReservation reservation = reservationRepository.findById(reservationId)
                    .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "reservation not found: " + reservationId));
            if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
                return; // 幂等：重复支付成功回调直接吸收
            }
            mutateStock(skuId, quantity, Stock::confirm);
            reservation.confirm(deductId);
            reservationRepository.save(reservation);
        }));
    }

    /** 支付失败/超时释放：幂等；已确认的预占不回滚。 */
    public void release(String reservationId, Long skuId, long quantity) {
        retryOnVersionConflict(() -> tx.executeWithoutResult(status -> {
            Optional<StockReservation> opt = reservationRepository.findById(reservationId);
            if (opt.isEmpty()) {
                return; // 没有预占记录，幂等忽略
            }
            StockReservation reservation = opt.get();
            if (reservation.getStatus() == ReservationStatus.RELEASED
                    || reservation.getStatus() == ReservationStatus.CONFIRMED) {
                return; // 已释放或已确认，幂等吸收
            }
            mutateStock(skuId, quantity, Stock::release);
            reservation.release();
            reservationRepository.save(reservation);
        }));
    }

    /**
     * 读-改-写库存聚合：重读聚合并应用领域变更。
     * 遇乐观锁版本冲突抛 {@code CONCURRENT_UPDATE}（供上层跨事务重试）；
     * 库存不足等业务性拒绝（CONFLICT）不重试，直接上抛。
     */
    private void mutateStock(Long skuId, long quantity, java.util.function.BiConsumer<Stock, Long> mutation) {
        Stock stock = stockRepository.findBySkuId(skuId)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "stock not found sku=" + skuId));
        mutation.accept(stock, quantity);
        stockRepository.save(stock);
    }

    /** 跨事务有界重试：仅吞 {@code CONCURRENT_UPDATE}，最多 {@value MAX_RETRIES} 次、线性退避。 */
    private void retryOnVersionConflict(Runnable action) {
        for (int attempt = 1; ; attempt++) {
            try {
                action.run();
                return;
            } catch (BizException ex) {
                if (!ErrorCodes.CONCURRENT_UPDATE.equals(ex.getCode()) || attempt >= MAX_RETRIES) {
                    throw ex;
                }
            }
            try {
                Thread.sleep(RETRY_BACKOFF_MILLIS * attempt); // 线性退避，打散热点行竞争
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw BizException.of(ErrorCodes.INTERNAL_ERROR, "stock mutation interrupted");
            }
        }
    }
}
