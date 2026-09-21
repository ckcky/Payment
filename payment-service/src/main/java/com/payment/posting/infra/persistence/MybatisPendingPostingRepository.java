package com.payment.posting.infra.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.payment.posting.domain.PendingPosting;
import com.payment.posting.domain.PendingPostingRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 出站失败台账仓储 MyBatis 实现（MyBatis-Plus，不抽象通用仓储层）。
 *
 * <p><b>到期判定在 SQL</b>（plan §2.2：台账表无 next_retry_at 列，退避到点 =
 * {@code updated_at + backoff(retry_count)}）：以 CASE 表达式把 retry_count（已失败投递次数，
 * 登记即 1）映射到退避毫秒（来自 {@code payment.posting.retry-backoff} 配置，非 SQL 硬编码），
 * 单次查询取回全部到期行，按 id 升序（确定性补投顺序），无行数饥饿。</p>
 */
@Repository
public class MybatisPendingPostingRepository implements PendingPostingRepository {

    private final PendingPostingMapper mapper;

    public MybatisPendingPostingRepository(PendingPostingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PendingPosting insert(PendingPosting posting) {
        PendingPostingEntity entity = PendingPostingEntity.fromDomain(posting);
        mapper.insert(entity);
        posting.setId(entity.getId());
        return posting;
    }

    @Override
    public java.util.Optional<PendingPosting> findById(Long id) {
        PendingPostingEntity entity = mapper.selectById(id);
        return entity == null ? java.util.Optional.empty() : java.util.Optional.of(entity.toDomain());
    }

    @Override
    public List<PendingPosting> findDuePending(Instant now, List<Duration> backoff, int limit) {
        return mapper.selectList(Wrappers.<PendingPostingEntity>lambdaQuery()
                        .eq(PendingPostingEntity::getStatus, PendingPosting.PostingStatus.PENDING.name())
                        .apply(dueCondition(backoff), now.toEpochMilli())
                        .orderByAsc(PendingPostingEntity::getId)
                        .last("LIMIT " + Math.max(limit, 1)))
                .stream()
                .map(PendingPostingEntity::toDomain)
                .toList();
    }

    /** 到期条件：updated_at + backoff(retry_count) <= now（毫秒口径，UNIX_TIMESTAMP 会话时区自洽）。 */
    private static String dueCondition(List<Duration> backoff) {
        StringBuilder caseExpr = new StringBuilder("CASE");
        for (int retryCount = 1; retryCount <= backoff.size(); retryCount++) {
            caseExpr.append(" WHEN ").append(retryCount)
                    .append(" THEN ").append(backoff.get(retryCount - 1).toMillis());
        }
        caseExpr.append(" ELSE ").append(backoff.get(backoff.size() - 1).toMillis()).append(" END");
        return "UNIX_TIMESTAMP(updated_at) * 1000 + " + caseExpr + " <= {0}";
    }

    @Override
    public void update(PendingPosting posting) {
        mapper.updateById(PendingPostingEntity.fromDomain(posting));
    }

    @Override
    public long countByStatus(PendingPosting.PostingStatus status) {
        return mapper.selectCount(Wrappers.<PendingPostingEntity>lambdaQuery()
                .eq(PendingPostingEntity::getStatus, status.name()));
    }

    @Override
    public List<PendingPosting> findByStatus(PendingPosting.PostingStatus status, int limit) {
        return mapper.selectList(Wrappers.<PendingPostingEntity>lambdaQuery()
                        .eq(PendingPostingEntity::getStatus, status.name())
                        .orderByAsc(PendingPostingEntity::getId)
                        .last("LIMIT " + Math.max(limit, 1)))
                .stream()
                .map(PendingPostingEntity::toDomain)
                .toList();
    }
}
