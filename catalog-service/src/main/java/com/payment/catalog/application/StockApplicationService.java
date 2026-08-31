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
import org.springframework.transaction.annotation.Transactional;

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
 */
@Service
public class StockApplicationService {

    private final StockRepository stockRepository;
    private final StockReservationRepository reservationRepository;

    public StockApplicationService(StockRepository stockRepository, StockReservationRepository reservationRepository) {
        this.stockRepository = stockRepository;
        this.reservationRepository = reservationRepository;
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
    @Transactional
    public void reserve(String reservationId, Long skuId, long quantity) {
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
        Stock stock = stockRepository.findBySkuId(skuId)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "stock not found sku=" + skuId));
        stock.reserve(quantity);
        stockRepository.save(stock);
        reservationRepository.save(new StockReservation(reservationId, skuId, quantity, ReservationStatus.PENDING, null));
    }

    /** 支付成功确认扣减：幂等于 deductId。 */
    @Transactional
    public void confirm(String reservationId, Long skuId, long quantity, String deductId) {
        StockReservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "reservation not found: " + reservationId));
        if (reservation.getStatus() == ReservationStatus.CONFIRMED) {
            return; // 幂等：重复支付成功回调直接吸收
        }
        Stock stock = stockRepository.findBySkuId(skuId)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "stock not found sku=" + skuId));
        stock.confirm(quantity);
        stockRepository.save(stock);
        reservation.confirm(deductId);
        reservationRepository.save(reservation);
    }

    /** 支付失败/超时释放：幂等；已确认的预占不回滚。 */
    @Transactional
    public void release(String reservationId, Long skuId, long quantity) {
        Optional<StockReservation> opt = reservationRepository.findById(reservationId);
        if (opt.isEmpty()) {
            return; // 没有预占记录，幂等忽略
        }
        StockReservation reservation = opt.get();
        if (reservation.getStatus() == ReservationStatus.RELEASED
                || reservation.getStatus() == ReservationStatus.CONFIRMED) {
            return; // 已释放或已确认，幂等吸收
        }
        Stock stock = stockRepository.findBySkuId(skuId)
                .orElseThrow(() -> BizException.of(ErrorCodes.NOT_FOUND, "stock not found sku=" + skuId));
        stock.release(quantity);
        stockRepository.save(stock);
        reservation.release();
        reservationRepository.save(reservation);
    }
}
