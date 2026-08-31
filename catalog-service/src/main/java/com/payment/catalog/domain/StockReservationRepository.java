package com.payment.catalog.domain;

import java.util.Optional;

/**
 * 库存预占记录仓储端口。
 */
public interface StockReservationRepository {

    Optional<StockReservation> findById(String reservationId);

    StockReservation save(StockReservation reservation);

    void delete(StockReservation reservation);
}
