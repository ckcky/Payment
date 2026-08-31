package com.payment.catalog.domain;

import com.payment.common.core.error.BizException;
import com.payment.common.core.error.ErrorCodes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 库存聚合不变量与三段式状态迁移测试（total = available + reserved + sold）。
 */
class StockAggregateTest {

    private Stock newStock(long total) {
        return new Stock(1L, total);
    }

    @Test
    void newStockHasAllAvailable() {
        Stock stock = newStock(10);
        assertThat(stock.getTotal()).isEqualTo(10);
        assertThat(stock.getAvailable()).isEqualTo(10);
        assertThat(stock.getReserved()).isZero();
        assertThat(stock.getSold()).isZero();
    }

    @Test
    void reserveReducesAvailableAndIncreasesReserved() {
        Stock stock = newStock(10);
        stock.reserve(3);
        assertThat(stock.getAvailable()).isEqualTo(7);
        assertThat(stock.getReserved()).isEqualTo(3);
        assertThat(stock.getSold()).isZero();
    }

    @Test
    void reserveBeyondAvailableThrowsConflict() {
        Stock stock = newStock(2);
        assertThatThrownBy(() -> stock.reserve(5))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.CONFLICT));
    }

    @Test
    void confirmMovesReservedToSold() {
        Stock stock = newStock(10);
        stock.reserve(3);
        stock.confirm(3);
        assertThat(stock.getAvailable()).isEqualTo(7);
        assertThat(stock.getReserved()).isZero();
        assertThat(stock.getSold()).isEqualTo(3);
    }

    @Test
    void confirmMoreThanReservedThrowsConflict() {
        Stock stock = newStock(10);
        stock.reserve(3);
        assertThatThrownBy(() -> stock.confirm(5))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ErrorCodes.CONFLICT));
    }

    @Test
    void releaseReturnsReservedToAvailable() {
        Stock stock = newStock(10);
        stock.reserve(4);
        stock.release(4);
        assertThat(stock.getAvailable()).isEqualTo(10);
        assertThat(stock.getReserved()).isZero();
        assertThat(stock.getSold()).isZero();
    }

    @Test
    void fullLifecyclePreservesInvariant() {
        Stock stock = newStock(10);
        stock.reserve(4);
        stock.confirm(4);
        stock.reserve(2);
        stock.release(2);
        // available 10-4-2+2 = 6, reserved 0, sold 4
        assertThat(stock.getAvailable()).isEqualTo(6);
        assertThat(stock.getReserved()).isZero();
        assertThat(stock.getSold()).isEqualTo(4);
        assertThat(stock.getTotal()).isEqualTo(stock.getAvailable() + stock.getReserved() + stock.getSold());
    }
}
