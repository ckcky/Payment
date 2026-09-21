package com.payment.ledger.infra.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 会计期间 Mapper（复合主键，自定义 SQL；缺行 = OPEN 的保守口径在仓储层）。
 */
@Mapper
public interface LedgerPeriodMapper {

    @Select("SELECT period, currency, status, closed_at, closed_by FROM ledger_periods "
            + "WHERE period = #{period} AND currency = #{currency}")
    LedgerPeriodEntity findByKey(@Param("period") String period, @Param("currency") String currency);

    @Select("SELECT period, currency, status, closed_at, closed_by FROM ledger_periods "
            + "ORDER BY period, currency")
    List<LedgerPeriodEntity> findAll();

    @Update("INSERT INTO ledger_periods (period, currency, status, closed_at, closed_by) "
            + "VALUES (#{period}, #{currency}, 'CLOSED', #{closedAt}, #{closedBy})")
    int insertClosed(@Param("period") String period, @Param("currency") String currency,
                     @Param("closedAt") java.time.Instant closedAt, @Param("closedBy") String closedBy);

    @Update("UPDATE ledger_periods SET status = 'CLOSED', closed_at = #{closedAt}, "
            + "closed_by = #{closedBy} WHERE period = #{period} AND currency = #{currency}")
    int markClosed(@Param("period") String period, @Param("currency") String currency,
                   @Param("closedAt") java.time.Instant closedAt, @Param("closedBy") String closedBy);
}
