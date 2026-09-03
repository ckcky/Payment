package com.payment.catalog.application;

import com.payment.catalog.domain.ReservationStatus;
import com.payment.catalog.domain.Stock;
import com.payment.catalog.domain.StockReservation;
import com.payment.catalog.domain.StockRepository;
import com.payment.catalog.domain.StockReservationRepository;
import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 库存应用服务三段式 + 幂等测试（内存 fake 仓储，不依赖 DB）。
 */
class StockApplicationServiceTest {

    private FakeStockRepository stockRepo;
    private FakeReservationRepository reservationRepo;
    private StockApplicationService service;

    @BeforeEach
    void setUp() {
        stockRepo = new FakeStockRepository();
        reservationRepo = new FakeReservationRepository();
        service = new StockApplicationService(stockRepo, reservationRepo, noopTxManager());
        stockRepo.save(new Stock(1L, 10));
    }

    /** 测试用无操作事务管理器：fake 仓储不依赖真实数据库事务。 */
    private org.springframework.transaction.PlatformTransactionManager noopTxManager() {
        return new org.springframework.transaction.PlatformTransactionManager() {
            @Override
            public org.springframework.transaction.TransactionStatus getTransaction(
                    org.springframework.transaction.TransactionDefinition definition) {
                return new org.springframework.transaction.support.SimpleTransactionStatus();
            }
            @Override
            public void commit(org.springframework.transaction.TransactionStatus status) { }
            @Override
            public void rollback(org.springframework.transaction.TransactionStatus status) { }
        };
    }

    @Test
    void seedDoesNotOverwriteExistingStock() {
        service.seedStock(1L, 99);
        assertThat(stockRepo.findBySkuId(1L).orElseThrow().getAvailable()).isEqualTo(10);
        service.seedStock(2L, 5);
        assertThat(stockRepo.findBySkuId(2L).orElseThrow().getAvailable()).isEqualTo(5);
    }

    @Test
    void reserveThenConfirmMovesReservedToSold() {
        service.reserve("order:1:sku:1", 1L, 3);
        assertThat(stockRepo.findBySkuId(1L).orElseThrow().getReserved()).isEqualTo(3);

        service.confirm("order:1:sku:1", 1L, 3, "pay-1");
        Stock stock = stockRepo.findBySkuId(1L).orElseThrow();
        assertThat(stock.getReserved()).isZero();
        assertThat(stock.getSold()).isEqualTo(3);
        assertThat(reservationRepo.findById("order:1:sku:1").orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void doubleReserveIsIdempotent() {
        service.reserve("order:1:sku:1", 1L, 3);
        service.reserve("order:1:sku:1", 1L, 3); // 重复预占直接吸收
        assertThat(stockRepo.findBySkuId(1L).orElseThrow().getReserved()).isEqualTo(3);
    }

    @Test
    void confirmIsIdempotentViaDeductId() {
        service.reserve("order:1:sku:1", 1L, 3);
        service.confirm("order:1:sku:1", 1L, 3, "pay-1");
        service.confirm("order:1:sku:1", 1L, 3, "pay-1"); // 重复确认直接吸收
        assertThat(stockRepo.findBySkuId(1L).orElseThrow().getSold()).isEqualTo(3);
    }

    @Test
    void releaseReturnsReservedToAvailable() {
        service.reserve("order:1:sku:1", 1L, 3);
        service.release("order:1:sku:1", 1L, 3);
        Stock stock = stockRepo.findBySkuId(1L).orElseThrow();
        assertThat(stock.getAvailable()).isEqualTo(10);
        assertThat(stock.getReserved()).isZero();
        assertThat(reservationRepo.findById("order:1:sku:1").orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void reserveInsufficientThrowsConflict() {
        assertThatThrownBy(() -> service.reserve("order:9:sku:1", 1L, 100))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.CONFLICT));
    }

    // ---- 内存 fake 仓储 ----

    private static final class FakeStockRepository implements StockRepository {
        private final Map<Long, Stock> bySku = new HashMap<>();

        @Override
        public Optional<Stock> findBySkuId(Long skuId) {
            return Optional.ofNullable(bySku.get(skuId));
        }

        @Override
        public Stock save(Stock stock) {
            bySku.put(stock.getSkuId(), stock);
            return stock;
        }
    }

    private static final class FakeReservationRepository implements StockReservationRepository {
        private final Map<String, StockReservation> byId = new HashMap<>();

        @Override
        public Optional<StockReservation> findById(String reservationId) {
            return Optional.ofNullable(byId.get(reservationId));
        }

        @Override
        public StockReservation save(StockReservation reservation) {
            byId.put(reservation.getReservationId(), reservation);
            return reservation;
        }

        @Override
        public void delete(StockReservation reservation) {
            byId.remove(reservation.getReservationId());
        }
    }
}
