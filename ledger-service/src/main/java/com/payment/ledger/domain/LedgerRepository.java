package com.payment.ledger.domain;

import java.util.List;
import java.util.Optional;

/**
 * 账本仓储边界（领域接口，不依赖持久化实现）。
 */
public interface LedgerRepository {

    Optional<Posting> findById(Long id);

    /** 按幂等键回查（重复记账幂等回放，FR-004）。 */
    Optional<Posting> findByIdempotencyKey(String idempotencyKey);

    /** 按业务来源回查（FR-008 追溯）。 */
    List<Posting> findBySource(LedgerSourceType sourceType, String sourceId);

    /** 全部分录（全局平衡性校验用，FR-007）。 */
    List<LedgerEntry> findAllEntries();

    /** 按来源回查分录（FR-008 追溯）。 */
    List<LedgerEntry> findEntriesBySource(LedgerSourceType sourceType, String sourceId);

    Posting save(Posting posting);
}
