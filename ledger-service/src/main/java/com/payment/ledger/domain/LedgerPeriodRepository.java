package com.payment.ledger.domain;

import java.util.List;
import java.util.Optional;

/**
 * 期间仓储边界（spec 031 §11）：缺行 = OPEN；关账置 CLOSED（幂等重复关账返回既有事实）。
 */
public interface LedgerPeriodRepository {

    /** 缺行即 OPEN（保守口径由调用方 {@link #isClosed} 承载）。 */
    Optional<LedgerPeriod> find(String period, String currency);

    List<LedgerPeriod> findAll();

    default boolean isClosed(String period, String currency) {
        return find(period, currency).map(p -> p.getStatus() == LedgerPeriod.Status.CLOSED).orElse(false);
    }

    /** 关账：insert-or-update 为 CLOSED（带 closedAt/closedBy）。 */
    void close(String period, String currency, String closedBy);
}
