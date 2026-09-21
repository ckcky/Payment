package com.payment.reconciliation.domain;

import java.util.List;
import java.util.Optional;

/**
 * 对账批次仓储边界（领域接口，不依赖持久化实现）。
 *
 * <p>032 起差异为独立台账（reconciliation_differences）：新批次差异只写台账
 * （differences_json 停写不停读），批次聚合加载时由实现负责水合。</p>
 */
public interface ReconciliationRepository {

    Optional<ReconciliationBatch> findById(Long id);

    /**
     * 按周期取批次（032 起「最新批」语义：同周期可能并存多份导入批次，返回 id 最大者）。
     */
    Optional<ReconciliationBatch> findByPeriod(String period);

    /**
     * 执行核对的幂等键回查（spec §8.3）：同一渠道 + 同一导入的重跑返回首次批次。
     */
    Optional<ReconciliationBatch> findByPeriodAndImport(String period, String channelCode, Long importId);

    /** 周期区间查询（period 为可比较字符串），可为空列表。 */
    List<ReconciliationBatch> findByPeriodBetween(String from, String to);

    ReconciliationBatch save(ReconciliationBatch batch);

    // ---- 差异台账（spec 032 §9 ③，differences_json 停写不停读）----

    /** 新差异入台账；撞 uk_diff_identity（同周期同类同引用已存在）⇒ 返回已存在的台账行（幂等吸收）。 */
    ReconciliationDifference insertDifference(ReconciliationDifference difference);

    Optional<ReconciliationDifference> findDifferenceByNo(String diffNo);

    /** 台账行更新（resolve / suspend / adjust-failed 的状态与处置回写）。 */
    ReconciliationDifference updateDifference(ReconciliationDifference difference);

    List<ReconciliationDifference> findDifferencesByBatch(Long batchId);

    /**
     * 差异分页查询（spec §10.1 GET differences）：条件均可空，按 id 倒序，total 为总数。
     */
    DifferencePage findDifferences(String period, String status, String merchantId, String kind,
                                   int page, int size);

    /** 分页结果（拆表后能力：可索引、可分页）。 */
    record DifferencePage(List<ReconciliationDifference> items, long total) {
    }
}
