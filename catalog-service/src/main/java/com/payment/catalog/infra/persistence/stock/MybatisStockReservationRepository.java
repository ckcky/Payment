package com.payment.catalog.infra.persistence.stock;

import com.payment.catalog.domain.StockReservation;
import com.payment.catalog.domain.StockReservationRepository;
import com.payment.catalog.domain.ReservationStatus;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 库存预占记录仓储 MyBatis 实现：按 reservation_id 主键读写。
 */
@Repository
public class MybatisStockReservationRepository implements StockReservationRepository {

    private final StockReservationMapper mapper;

    public MybatisStockReservationRepository(StockReservationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<StockReservation> findById(String reservationId) {
        StockReservationEntity entity = mapper.selectById(reservationId);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public StockReservation save(StockReservation reservation) {
        StockReservationEntity entity = toEntity(reservation);
        if (mapper.selectById(reservation.getReservationId()) == null) {
            mapper.insert(entity);
        } else {
            mapper.updateById(entity);
        }
        return reservation;
    }

    @Override
    public void delete(StockReservation reservation) {
        mapper.deleteById(reservation.getReservationId());
    }

    private StockReservation toDomain(StockReservationEntity entity) {
        return new StockReservation(entity.getReservationId(), entity.getSkuId(), entity.getQuantity(),
                ReservationStatus.valueOf(entity.getStatus()), entity.getDeductId());
    }

    private StockReservationEntity toEntity(StockReservation reservation) {
        StockReservationEntity entity = new StockReservationEntity();
        entity.setReservationId(reservation.getReservationId());
        entity.setSkuId(reservation.getSkuId());
        entity.setQuantity(reservation.getQuantity());
        entity.setStatus(reservation.getStatus().name());
        entity.setDeductId(reservation.getDeductId());
        return entity;
    }
}
