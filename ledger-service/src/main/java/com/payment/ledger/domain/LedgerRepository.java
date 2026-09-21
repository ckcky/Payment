package com.payment.ledger.domain;

import com.payment.common.dto.rpc.AccountingEventType;

import java.util.List;
import java.util.Optional;

/**
 * 账本仓储边界（领域接口，不依赖持久化实现）。
 *
 * <p>031 起 {@link #save} MUST 在**同一本地事务**内写入交易、分录与余额投影
 * （spec §10：投影与分录永不漂移的构造性保证）；失败整体回滚、不留半套分录。</p>
 */
public interface LedgerRepository {

    Optional<Posting> findById(Long id);

    /** 按幂等键回查（重复记账幂等回放，FR-004）。 */
    Optional<Posting> findByIdempotencyKey(String idempotencyKey);

    /** 按事件回查（原则 10 双唯一约束之事件级；ADJUSTMENT 红冲取原交易）。 */
    Optional<Posting> findByEvent(AccountingEventType eventType, String sourceId);

    /** 按业务来源回查（FR-008 追溯；一笔 payment 可对应多事件）。 */
    List<Posting> findBySource(LedgerSourceType sourceType, String sourceId);

    /** 全部记账批次及分录（spec 017：审计账证/账账核对只读入口，按 id 倒序，上限由调用方控制）。 */
    List<Posting> findAllPostings();

    /**
     * 试算平衡（§10 ②）：按「账户实例 × 币种」聚合**分录**（Source of Truth）。
     *
     * @param period {@code YYYY-MM} 过滤；null = 全期间
     */
    List<TrialBalanceRow> trialBalance(String period);

    /** 期间内是否残留 PENDING 交易（关账前置校验，§11）。 */
    boolean existsPendingPosting(String period);

    Posting save(Posting posting);
}
