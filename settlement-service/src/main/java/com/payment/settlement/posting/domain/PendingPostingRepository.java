package com.payment.settlement.posting.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 出站失败台账仓储边界（领域接口，不依赖持久化实现）。
 *
 * <p>时间口径（plan §2.2 / spec 034 §12.1）：台账表只有 DB 维护的 {@code updated_at}
 * （DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP），不设 next_retry_at 列——
 * 「到期与否」由调度器按 {@code updated_at + backoff(retryCount)} 推导，retry_count 是唯一计数器。</p>
 */
public interface PendingPostingRepository {

    /** 新增一行台账（调用方先按 UNIQUE(event_type, source_id) 预检；并发撞键由实现吸收或上抛）。 */
    PendingPosting insert(PendingPosting posting);

    Optional<PendingPosting> findById(Long id);

    /**
     * 取到期待补投的 PENDING 行（{@code status=PENDING} 且
     * {@code updated_at + backoff(max(retryCount-1,0)) <= now}}；retry_count 口径 = 已失败投递次数，
     * 登记即 1，退避索引取其减一）。{@code backoff} 由调度器传入（越界取最后一项）。
     * 实现按 id 升序返回（确定性补投顺序）。
     */
    List<PendingPosting> findDuePending(Instant now, List<java.time.Duration> backoff, int limit);

    /** 全量更新（状态 / retry_count / fail_reason），乐观并发由「单实例调度 + 终态幂等吸收」保证。 */
    void update(PendingPosting posting);

    /** 按状态计数（gauge 采样用，一次 COUNT）。 */
    long countByStatus(PendingPosting.PostingStatus status);

    /** 按状态列出台账行（人工队列视图，id 升序）。 */
    List<PendingPosting> findByStatus(PendingPosting.PostingStatus status, int limit);
}
