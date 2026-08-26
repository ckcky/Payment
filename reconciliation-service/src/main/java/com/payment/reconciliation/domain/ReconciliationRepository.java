package com.payment.reconciliation.domain;

import java.util.List;
import java.util.Optional;

/**
 * 对账批次仓储边界（领域接口，不依赖持久化实现）。
 */
public interface ReconciliationRepository {

    Optional<ReconciliationBatch> findById(Long id);

    Optional<ReconciliationBatch> findByPeriod(String period);

    /** 周期区间查询（period 为可比较字符串），可为空列表。 */
    List<ReconciliationBatch> findByPeriodBetween(String from, String to);

    ReconciliationBatch save(ReconciliationBatch batch);
}
